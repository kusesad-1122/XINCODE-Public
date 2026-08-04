package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes a shell command.
 *
 * 永远走普通 `sh -c`（app 自身 uid）。需要 root 的动作必须显式调用 `su_exec`。
 *
 * Safety: 30s hard timeout.
 * Output truncated to 4000 chars (stdout) / 2000 chars (stderr).
 */
class ShellExecTool : Tool {

    override val name = "shell_exec"
    override val description = "Execute a shell command as the app user; root is not required or used. " +
            "Returns stdout on success (exitCode=0). On failure returns exitCode + stderr. " +
            "Use for commands like ls, cat, pwd, grep, id."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "The shell command to execute")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_STDOUT = 4000
        private const val MAX_STDERR = 2000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val command = params["command"] ?: return@withContext ToolResult.Error("缺少 command 参数")

        // 普通 shell 永不提权。这样产出文件始终归当前 App UID 所有,重装/切换 root
        // 状态也不会把普通工作流悄悄变成 root 工作流。
        SelfProtect.refuseCommand(command)?.let { return@withContext ToolResult.Error(it) }
        return@withContext executeViaSh(command)
    }

    /** Execute via sh -c (non-root fallback). */
    private suspend fun executeViaSh(command: String): ToolResult {
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            return@executeViaSh withTimeout(TIMEOUT_SECONDS * 1000) {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    ToolResult.Success(stdout.trim().let {
                        if (it.length > MAX_STDOUT) it.take(MAX_STDOUT / 2) +
                            "\n[...已截断 ${it.length - MAX_STDOUT} 字符...]\n" +
                            it.takeLast(MAX_STDOUT / 2)
                        else it
                    })
                } else {
                    ToolResult.Error(
                        message = "命令退出码 $exitCode",
                        exitCode = exitCode,
                        stderr = stderr.trim().let {
                            if (it.length > MAX_STDERR) it.take(MAX_STDERR / 2) +
                                "\n[...已截断...]\n" + it.takeLast(MAX_STDERR / 2)
                            else it
                        }.ifBlank { "(无 stderr 输出)" }
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            killProcessGroup(process)
            return@executeViaSh ToolResult.Error("命令超时 (${TIMEOUT_SECONDS}s)，已强杀进程组")
        } catch (e: Exception) {
            killProcessGroup(process)
            return@executeViaSh ToolResult.Error("执行异常: ${e.message}")
        }
    }

    /** Kill process group to prevent orphan child processes. */
    private fun killProcessGroup(process: Process?) {
        if (process == null) return
        try {
            val pid = getPid(process)
            if (pid > 0) {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid"))
                    .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            process.destroyForcibly()
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }

    private fun getPid(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
