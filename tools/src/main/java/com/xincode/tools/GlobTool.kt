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
 * gap-04 glob 通配文件匹配(对标 grok 的 glob 工具)。
 *
 * 按通配模式递归匹配工作区内的文件路径(模式示例见 description),按修改时间倒序返回,
 * 便于模型先定位再读或编辑。
 */
class GlobTool : Tool {

    override val name = "glob"
    override val description =
        "按通配模式递归匹配工作区内的文件路径(如 **/*.kt、app/**/*.xml)。" +
        "参数:pattern(必填)、path(限定起始目录,可选)。返回按修改时间倒序的相对路径列表。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().apply { put("type", "string"); put("description", "glob 模式,如 **/*.kt") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "起始目录(工作区内,可选,默认工作区根)") })
        })
        put("required", JSONArray().apply { put("pattern") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val pattern = params["pattern"]?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Error("缺少 pattern 参数")
        val baseRaw = params["path"]?.takeIf { it.isNotBlank() } ?: PathResolver.WORKSPACE_ROOT
        val base = PathResolver.resolve(baseRaw) ?: return@withContext ToolResult.Error("路径不在工作区内: $baseRaw")
        val baseDir = File(base)
        if (!baseDir.exists()) return@withContext ToolResult.Error("路径不存在: $baseRaw")

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return@withContext ToolResult.Error("glob 模式无效: ${e.message}")
        }

        val rootPath = File(PathResolver.WORKSPACE_ROOT).toPath()
        val matches = ArrayList<Pair<String, Long>>()
        walk(baseDir) { f ->
            val relToRoot = runCatching { rootPath.relativize(f.toPath()) }.getOrNull() ?: return@walk
            if (matcher.matches(relToRoot) || matcher.matches(f.toPath().fileName)) {
                matches.add(relToRoot.toString() to f.lastModified())
            }
        }
        matches.sortByDescending { it.second }
        val out = if (matches.isEmpty()) "(无匹配)"
        else matches.take(500).joinToString("\n") { it.first } +
            if (matches.size > 500) "\n… 共 ${matches.size} 个,已截断到 500" else ""
        ToolResult.Success(out)
    }

    private inline fun walk(dir: File, onFile: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.name.startsWith(".git")) continue
                if (c.isDirectory) stack.addLast(c) else onFile(c)
            }
        }
    }
}
