package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * gap-01 外科式文件编辑(str_replace)。
 *
 * 把文件中【唯一】出现的 old_string 替换为 new_string;若 old_string 出现多次,必须
 * replace_all=true 才会全部替换,否则报错要求补充上下文以定位唯一位置。old_string 为空串
 * 时视为“新建文件”(写入 new_string)。这是比整文件覆写(file_write)安全得多的最小改动方式。
 */
class EditTool : Tool {

    override val name = "file_edit"
    override val description =
        "对已有文件做局部替换:把文件中唯一出现的 old_string 替换为 new_string。" +
        "old_string 必须在文件中唯一;若要替换所有出现,设 replace_all=true。" +
        "old_string 为空串则新建文件写入 new_string。路径须在工作区内。比 file_write 整文件覆写更安全。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string"); put("description", "要编辑的文件路径(工作区内)")
            })
            put("old_string", JSONObject().apply {
                put("type", "string"); put("description", "要被替换的原文(须唯一;空串=新建文件)")
            })
            put("new_string", JSONObject().apply {
                put("type", "string"); put("description", "替换后的新文本")
            })
            put("replace_all", JSONObject().apply {
                put("type", "boolean"); put("description", "是否替换全部出现(默认 false)")
            })
        })
        put("required", JSONArray().apply { put("path"); put("old_string"); put("new_string") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val oldStr = params["old_string"] ?: ""
        val newStr = params["new_string"] ?: ""
        val replaceAll = (params["replace_all"] ?: "false").trim().lowercase() in setOf("true", "1", "yes")

        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("路径不在工作区内: $path")
        val file = File(safePath)

        // old_string 空 → 新建文件
        if (oldStr.isEmpty()) {
            if (file.exists() && file.isFile && file.readText().isNotEmpty()) {
                return@withContext ToolResult.Error("old_string 为空表示新建文件,但 $path 已存在且非空;请提供要替换的 old_string。")
            }
            return@withContext try {
                file.parentFile?.mkdirs()
                file.writeText(newStr)
                ToolResult.Success("已新建文件 $path(${newStr.length} 字符)")
            } catch (e: Exception) {
                ToolResult.Error("新建失败: ${e.message}")
            }
        }

        if (oldStr == newStr) return@withContext ToolResult.Error("old_string 与 new_string 相同,无需编辑")
        if (!file.exists() || !file.isFile) return@withContext ToolResult.Error("文件不存在或不是普通文件: $path")

        val original = file.readText()
        val count = countOccurrences(original, oldStr)
        when {
            count == 0 -> return@withContext ToolResult.Error("在 $path 中未找到 old_string(0 次);请核对原文精确内容(含空白/缩进)。")
            count > 1 && !replaceAll -> return@withContext ToolResult.Error(
                "old_string 在 $path 中出现 $count 次,不唯一。请扩大 old_string 上下文以定位唯一位置,或设 replace_all=true 替换全部。"
            )
        }
        val updated = if (replaceAll) original.replace(oldStr, newStr)
        else original.replaceFirst(oldStr, newStr)
        return@withContext try {
            file.writeText(updated)
            val replaced = if (replaceAll) count else 1
            ToolResult.Success("已替换 $replaced 处 → $path(现 ${updated.length} 字符)\n--- 片段 ---\n${snippet(updated, newStr)}")
        } catch (e: Exception) {
            ToolResult.Error("写入失败: ${e.message}")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var idx = haystack.indexOf(needle); var n = 0
        while (idx >= 0) { n++; idx = haystack.indexOf(needle, idx + needle.length) }
        return n
    }

    /** 返回 new_string 附近的一小段上下文,便于模型确认改动落点。 */
    private fun snippet(text: String, anchor: String): String {
        val at = text.indexOf(anchor).coerceAtLeast(0)
        val start = (at - 120).coerceAtLeast(0)
        val end = (at + anchor.length + 120).coerceAtMost(text.length)
        return text.substring(start, end)
    }
}
