package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
            val p = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            process = p

            return@executeViaSh coroutineScope {
                // 并发读 stdout/stderr,避免「先读完 stdout 再读 stderr」的管道写满死锁
                // (命令 stderr 一多,子进程写阻塞,父进程读不到 EOF → 卡到超时)。
                // 两边都只保留最近 cap 字符,既持续排空管道,又不让大输出撑爆内存。
                val stdout = StringBuilder()
                val stderr = StringBuilder()
                val readers = listOf(
                    async(Dispatchers.IO) {
                        p.inputStream.bufferedReader().use { r ->
                            while (true) {
                                val line = r.readLine() ?: break
                                appendBounded(stdout, line, MAX_STDOUT * 2)
                            }
                        }
                    },
                    async(Dispatchers.IO) {
                        p.errorStream.bufferedReader().use { r ->
                            while (true) {
                                val line = r.readLine() ?: break
                                appendBounded(stderr, line, MAX_STDERR * 2)
                            }
                        }
                    }
                )

                val exited = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!exited) {
                    killProcessGroup(p)
                    readers.forEach { reader -> withTimeoutOrNull(2000) { reader.join() } }
                    ToolResult.Error("命令超时 (${TIMEOUT_SECONDS}s)，已强杀进程组")
                } else {
                    readers.forEach { reader -> withTimeoutOrNull(2000) { reader.join() } }
                    buildResult(p.exitValue(), stdout.toString(), stderr.toString())
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

    private fun buildResult(exitCode: Int, stdoutRaw: String, stderrRaw: String): ToolResult {
        val stdout = stdoutRaw.trim()
        val stderr = stderrRaw.trim()
        return if (exitCode == 0) {
            ToolResult.Success(truncate(stdout, MAX_STDOUT))
        } else {
            ToolResult.Error(
                message = "命令退出码 $exitCode",
                exitCode = exitCode,
                stderr = truncate(stderr, MAX_STDERR).ifBlank { "(无 stderr 输出)" }
            )
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length > max) s.take(max / 2) + "\n[...已截断 ${s.length - max} 字符...]\n" + s.takeLast(max / 2)
        else s

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

/** 向 StringBuilder 追加一行并只保留最近 [cap] 字符,持续排空管道的同时限制内存。 */
internal fun appendBounded(sb: StringBuilder, line: String, cap: Int) {
    sb.append(line).append('\n')
    if (sb.length > cap) sb.delete(0, sb.length - cap)
}
