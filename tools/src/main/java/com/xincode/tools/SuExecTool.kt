package com.xincode.tools

import android.util.Log
import com.xincode.tools.RootShellManager
import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * su_exec tool powered by libsu via RootShellManager.
 *
 * All root commands go through RootShellManager.execute().
 */
class SuExecTool : Tool {

    companion object {
        private const val TAG = "SuExecTool"
        private const val MAX_OUTPUT = 4000
    }

    override val name = "su_exec"
    override val description = buildString {
        appendLine("Execute a command with root privileges using libsu.")
        appendLine("Timeout: 30s per command (set by libsu builder).")
        appendLine()
        appendLine("Always requires security gate confirmation. Full output is audited.")
    }

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "The shell command to execute as root")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val command = params["command"] ?: return@withContext ToolResult.Error("缺少 command 参数")

        // Check root status first
        if (RootShellManager.rootStatus != RootStatus.OK) {
            return@withContext ToolResult.Error(
                "Root 不可用 (${RootShellManager.rootStatus}). " +
                "请检查设备 root 状态后重试。"
            )
        }

        return@withContext try {
            Log.i(TAG, "Executing via libsu: ${command.take(80)}")

            val result = RootShellManager.execute(command)

            // Truncate output for token budget
            val truncatedStdout = result.stdout.let {
                if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT / 2) +
                    "\n[...已截断 ${it.length - MAX_OUTPUT} 字符...]\n" +
                    it.takeLast(MAX_OUTPUT / 2)
                else it
            }

            Log.i(TAG, "Result: exitCode=${result.exitCode}, stdout=${result.stdout.take(60)}...")

            if (result.exitCode != 0) {
                ToolResult.Error(
                    message = "su_exec 退出码 ${result.exitCode}",
                    exitCode = result.exitCode,
                    stderr = result.stderr
                )
            } else {
                ToolResult.Success(truncatedStdout.ifBlank { "(no output)" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            ToolResult.Error("su_exec 异常: ${e.message}")
        }
    }
}