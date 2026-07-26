package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes a shell command.
 *
 * 当设备已 root（RootShellManager.rootStatus == OK）时，自动走 libsu（root 权限）。
 * 否则走普通 `sh -c`（app 自身 uid）。
 *
 * Safety: 30s hard timeout.
 * Output truncated to 4000 chars (stdout) / 2000 chars (stderr).
 */
class ShellExecTool : Tool {

    override val name = "shell_exec"
    override val description = "Execute a shell command (auto root: uses root privileges if device is rooted). " +
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

        // 设备 root 之后这里会【自动】走 root,一个 chmod/chown 落进 App 自己的 databases/
        // 就能让文件变成 root 所有、App 的 uid 反而读不了 —— 下次启动直接开不了库,
        // 用户全部数据丢失。这条路真的被踩过,所以在跑之前先拦。见 SelfProtect。
        SelfProtect.refuseCommand(command)?.let { return@withContext ToolResult.Error(it) }

        // When root is available, use libsu (RootShellManager)
        if (RootShellManager.rootStatus == RootStatus.OK) {
            return@withContext executeViaRoot(command)
        }

        // Fallback: regular sh -c (non-root, untrusted_app sandbox)
        return@withContext executeViaSh(command)
    }

    /** Execute via libsu (root). */
    private suspend fun executeViaRoot(command: String): ToolResult {
        val result = RootShellManager.execute(command)
        val truncatedStdout = result.stdout.let {
            if (it.length > MAX_STDOUT) it.take(MAX_STDOUT / 2) +
                "\n[...已截断 ${it.length - MAX_STDOUT} 字符...]\n" +
                it.takeLast(MAX_STDOUT / 2)
            else it
        }
        return if (result.exitCode != 0) {
            ToolResult.Error(
                message = "命令退出码 ${result.exitCode}",
                exitCode = result.exitCode,
                stderr = result.stderr
            )
        } else {
            ToolResult.Success(truncatedStdout.ifBlank { "(no output)" })
        }
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