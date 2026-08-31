package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lists files and directories at the given path.
 *
 * Relative paths resolve inside the current app-writable workspace.
 * Output format: one entry per line: type + size + name.
 * Sorted alphabetically, directories first.
 */
class ListDirTool : Tool {

    override val name = "list_dir"
    override val description = "List files and directories in a directory. " +
            "Returns one entry per line with type (d/-) + size + name. Sorted alphabetically, directories first."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Directory path (relative to the current workspace, or an absolute Android path)")
            })
        })
        put("required", JSONArray().apply { put("path") })
    }

    companion object {
        private const val MAX_OUTPUT = 4000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("无法解析路径: $path")
        SelfProtect.refuse(safePath)?.let { return@withContext ToolResult.Error(it) }

        val dir = File(safePath)
        if (!dir.exists()) return@withContext ToolResult.Error("目录不存在: $path")
        if (!dir.isDirectory) return@withContext ToolResult.Error("不是目录: $path")

        try {
            val files = dir.listFiles() ?: emptyArray()
            val listing = files
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .joinToString("\n") { f ->
                    val type = if (f.isDirectory) "d" else "-"
                    val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                    val trail = if (f.isDirectory) "/" else ""
                    "$type$size ${f.name}$trail"
                }

            val output = listing.ifEmpty { "(空目录)" }
            val truncated = if (output.length > MAX_OUTPUT) {
                output.take(MAX_OUTPUT / 2) +
                    "\n[...已截断...]\n" +
                    output.takeLast(MAX_OUTPUT / 2)
            } else output

            ToolResult.Success(truncated)
        } catch (e: Exception) {
            ToolResult.Error("列表异常: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        else -> "${bytes / (1024 * 1024)}M"
    }
}
