package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.FileSystems

/**
 * gap-03 grep 内容搜索(一等公民工具,对标 grok/ripgrep)。
 *
 * 在工作区内按正则搜索文件内容。Android 无 ripgrep,这里用纯 JVM 递归遍历 + Kotlin Regex,
 * 跳过二进制/超大文件。支持 output_mode(content/files_with_matches/count)、大小写不敏感、
 * glob 文件过滤、结果条数上限。
 */
class GrepTool : Tool {

    override val name = "grep"
    override val description =
        "在工作区内按【正则表达式】搜索文件内容。参数:pattern(正则,必填)、path(限定子目录,可选)、" +
        "glob(文件名通配过滤如 *.kt,可选)、output_mode(content=带行号匹配行/files_with_matches=只列文件/count=计数,默认 content)、" +
        "case_insensitive(true/false)、head_limit(最多返回多少条,默认 200)。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().apply { put("type", "string"); put("description", "正则表达式") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "限定搜索的子目录(工作区内,可选)") })
            put("glob", JSONObject().apply { put("type", "string"); put("description", "文件名通配过滤,如 *.kt 或 **/*.xml(可选)") })
            put("output_mode", JSONObject().apply { put("type", "string"); put("description", "content | files_with_matches | count") })
            put("case_insensitive", JSONObject().apply { put("type", "boolean"); put("description", "大小写不敏感(默认 false)") })
            put("head_limit", JSONObject().apply { put("type", "integer"); put("description", "最多返回条数(默认 200)") })
        })
        put("required", JSONArray().apply { put("pattern") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val patternStr = params["pattern"]?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Error("缺少 pattern 参数")
        val mode = (params["output_mode"] ?: "content").trim().ifEmpty { "content" }
        val ci = (params["case_insensitive"] ?: "false").trim().lowercase() in setOf("true", "1", "yes")
        val headLimit = params["head_limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: 200
        val globStr = params["glob"]?.takeIf { it.isNotBlank() }

        val baseRaw = params["path"]?.takeIf { it.isNotBlank() } ?: PathResolver.WORKSPACE_ROOT
        val base = PathResolver.resolve(baseRaw) ?: return@withContext ToolResult.Error("路径不在工作区内: $baseRaw")
        SelfProtect.refuse(base)?.let { return@withContext ToolResult.Error(it) }
        val baseDir = File(base)
        if (!baseDir.exists()) return@withContext ToolResult.Error("路径不存在: $baseRaw")

        val regex = try {
            if (ci) Regex(patternStr, RegexOption.IGNORE_CASE) else Regex(patternStr)
        } catch (e: Exception) {
            return@withContext ToolResult.Error("正则无效: ${e.message}")
        }
        val matcher = globStr?.let { runCatching { FileSystems.getDefault().getPathMatcher("glob:$it") }.getOrNull() }

        val files = ArrayList<File>()
        collect(baseDir, files)

        val contentLines = ArrayList<String>()
        val matchedFiles = LinkedHashSet<String>()
        var totalCount = 0

        outer@ for (f in files) {
            if (matcher != null) {
                val rel = File(PathResolver.WORKSPACE_ROOT).toURI().relativize(f.toURI()).path
                if (!matcher.matches(File(rel).toPath()) && !matcher.matches(File(f.name).toPath())) continue
            }
            if (!isProbablyText(f)) continue
            val rel = relPath(f)
            val lines = try { f.readLines() } catch (_: Exception) { continue }
            for ((i, line) in lines.withIndex()) {
                if (regex.containsMatchIn(line)) {
                    totalCount++
                    matchedFiles.add(rel)
                    if (mode == "content") {
                        contentLines.add("$rel:${i + 1}: ${line.trimEnd().take(400)}")
                        if (contentLines.size >= headLimit) break@outer
                    }
                    if (mode == "count" && totalCount >= headLimit * 50) break@outer
                }
            }
            if (mode == "files_with_matches" && matchedFiles.size >= headLimit) break
        }

        val out = when (mode) {
            "files_with_matches" -> if (matchedFiles.isEmpty()) "(无匹配)" else matchedFiles.joinToString("\n")
            "count" -> "匹配行数: $totalCount(命中文件 ${matchedFiles.size} 个)"
            else -> if (contentLines.isEmpty()) "(无匹配)" else contentLines.joinToString("\n") +
                if (contentLines.size >= headLimit) "\n… 已截断到 $headLimit 条" else ""
        }
        ToolResult.Success(out)
    }

    private fun collect(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.name.startsWith(".git")) continue
            if (c.isDirectory) collect(c, out) else out.add(c)
        }
    }

    private fun relPath(f: File): String {
        val root = File(PathResolver.WORKSPACE_ROOT).path
        return if (f.path.startsWith(root)) f.path.removePrefix(root).trimStart('/') else f.path
    }

    /** 粗略判断文本文件:跳过 >2MB 与含 NUL 的文件。 */
    private fun isProbablyText(f: File): Boolean {
        if (!f.isFile || f.length() > 2L * 1024 * 1024) return false
        return try {
            val head = ByteArray(minOf(4096, f.length().toInt()))
            f.inputStream().use { it.read(head) }
            head.none { it.toInt() == 0 }
        } catch (_: Exception) { false }
    }
}
