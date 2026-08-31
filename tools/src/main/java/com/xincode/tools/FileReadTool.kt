package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads a file by path with optional line range.
 *
 * Path is resolved against the current app-writable workspace root.
 * Relative paths (starting without /) are treated as relative to workspace root.
 * Absolute paths are accepted and remain subject to Android filesystem permissions.
 */
class FileReadTool : Tool {

    override val name = "file_read"
    override val description = "Read a file by path. Optionally specify startLine/endLine (1-based) for partial read. " +
            "Relative paths use the current workspace; absolute paths are read as provided. Root is not required."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path (relative to the current workspace, or an absolute Android path)")
            })
            put("startLine", JSONObject().apply {
                put("type", "integer")
                put("description", "Start line number (1-based), default 1")
            })
            put("endLine", JSONObject().apply {
                put("type", "integer")
                put("description", "End line number (inclusive), omit to read to end")
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

        val file = File(safePath)
        if (!file.exists()) return@withContext ToolResult.Error("文件不存在: $path")
        if (!file.isFile) return@withContext ToolResult.Error("不是文件: $path")
        if (!file.canRead()) return@withContext ToolResult.Error("无读取权限: $path")
        if (file.length() > 10L * 1024 * 1024) return@withContext ToolResult.Error("文件过大(>10MB)，请用 grep 分段读取: $path")

        try {
            val startLine = params["startLine"]?.toIntOrNull() ?: 1
            val endLine = params["endLine"]?.toIntOrNull()

            val lines = file.readLines()
            if (startLine > lines.size) {
                return@withContext ToolResult.Error("起始行 $startLine 超出文件总行数 ${lines.size}")
            }

            val selected = if (endLine != null) {
                val end = endLine.coerceAtMost(lines.size)
                lines.drop(startLine - 1).take(end - startLine + 1)
            } else {
                lines.drop(startLine - 1)
            }

            val output = selected.joinToString("\n")
            val truncated = if (output.length > MAX_OUTPUT) {
                output.take(MAX_OUTPUT / 2) +
                    "\n[...已截断 ${output.length - MAX_OUTPUT} 字符...]\n" +
                    output.takeLast(MAX_OUTPUT / 2)
            } else output

            ToolResult.Success(truncated.ifEmpty { "(空行)" })
        } catch (e: Exception) {
            ToolResult.Error("读取异常: ${e.message}")
        }
    }
}
