package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Creates or overwrites a file within the workspace.
 *
 * Path must resolve within /storage/emulated/0/XINCODE.
 * Parent directories are created automatically.
 */
class FileWriteTool : Tool {

    override val name = "file_write"
    override val description = "Create or overwrite a file. Path must be within workspace " +
            "(/storage/emulated/0/XINCODE). Parent directories are created automatically."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path to write (relative to workspace or absolute within workspace)")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "Content to write into the file")
            })
        })
        put("required", JSONArray().apply { put("path"); put("content") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val content = params["content"] ?: return@withContext ToolResult.Error("缺少 content 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("路径不在工作区内: $path")

        try {
            val file = File(safePath)
            // Reject if path points to an existing directory
            if (file.exists() && file.isDirectory) {
                return@withContext ToolResult.Error("路径是目录，不能作为文件写入: $path")
            }
            // Create parent directories
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult.Success("已写入 ${file.length()} 字节 → $path")
        } catch (e: Exception) {
            ToolResult.Error("写入异常: ${e.message}")
        }
    }
}