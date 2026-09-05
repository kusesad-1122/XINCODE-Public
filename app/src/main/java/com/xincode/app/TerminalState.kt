package com.xincode.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import android.content.Context
import com.xincode.app.privilege.PrivilegedExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 可视终端(Application 级)。三路输出汇聚到这里、实时上屏:
 *  - 用户在终端页手输的命令;
 *  - Linux 环境【部署 / 工具安装】的流式输出(LinuxEnvironment.outputSink);
 *  - AI 通过 env_exec 工具在环境里跑的命令(让用户看到 AI 在操作什么)。
 */
class TerminalState {
    private var appContext: Context? = null
    fun attachContext(ctx: Context) { appContext = ctx.applicationContext }

    val lines: SnapshotStateList<String> = mutableStateListOf(
        "XINCODE 终端 — 已就绪时命令在内置 Ubuntu 环境(chroot)执行,否则按 Root>Shizuku>普通 自动降级。",
        ""
    )
    var running by mutableStateOf(false)
        private set

    private val maxLines = 4000
    private val pidMarker = "__XINCODE_TERM_PID__:"
    @Volatile private var activePid: Long? = null
    @Volatile private var stopRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun addLinesOnMain(list: List<String>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            list.forEach { lines.add(it) }
            trimExcess()
        } else {
            mainHandler.post {
                list.forEach { lines.add(it) }
                trimExcess()
            }
        }
    }

    private fun trimExcess() {
        if (lines.size > maxLines) {
            val excess = lines.size - maxLines
            repeat(excess) { if (lines.isNotEmpty()) lines.removeAt(0) }
        }
    }

    fun appendChunk(s: String) {
        if (s.isEmpty()) {
            addLinesOnMain(listOf(""))
            return
        }
        val parts = s.split('\n')
        addLinesOnMain(parts)
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) lines.clear()
        else mainHandler.post { lines.clear() }
    }

    /**
     * 给 apt 类命令自动加 -y + 非交互环境,避免卡在 Y/N 确认上
     * （终端页同样支持运行中手动输入 Y/N,见 [sendInput]）。
     */
    private fun autoYes(cmd: String): String {
        val t = cmd.trim()
        val aptOp = Regex("""^(sudo\s+)?apt(-get)?\s+.*\b(install|remove|upgrade|dist-upgrade|autoremove|purge)\b""")
            .containsMatchIn(t)
        if (!aptOp) return cmd
        if (Regex("""(^|\s)-[a-zA-Z]*y""").containsMatchIn(t)) return cmd
        val head = Regex("""^(sudo\s+)?apt(-get)?""").find(t) ?: return cmd
        val injected = t.substring(0, head.range.last + 1) + " -y" + t.substring(head.range.last + 1)
        return "DEBIAN_FRONTEND=noninteractive $injected"
    }

    /**
     * 向正在运行的命令 stdin 发送一行输入（如 apt 的 Y/N 确认）。
     * 普通/Shizuku 路径直写进程 stdin；Root/libsu 路径无 stdin 句柄,返回 false。
     */
    fun sendInput(text: String): Boolean {
        if (!running) return false
        val line = text.trimEnd() + "\n"
        return try {
            com.xincode.app.privilege.PrivilegedExecutor.sendInput(line)
        } catch (_: Exception) { false }
    }

    /** 执行一条命令:环境就绪→在 Ubuntu 内,否则→ Root>Shizuku>普通 自动降级。输出流式上屏。 */
    suspend fun run(cmd: String) {
        val c = autoYes(cmd.trim())
        if (c.isEmpty()) return
        withContext(Dispatchers.Main) { running = true }
        activePid = null
        stopRequested = false
        appendChunk("$ $c")
        // 输出外层 shell PID，stop() 可以从另一条特权命令终止卡住的命令。
        val wrapped = "echo " + pidMarker + Char(36) + Char(36) + "; " + c
        val onLine: (String) -> Unit = { line ->
            val pid = line.trim().removePrefix(pidMarker).toLongOrNull()
            if (pid != null) activePid = pid else appendChunk(line)
        }
        try {
            val res = withContext(Dispatchers.IO) {
                if (LinuxEnvironment.isReady())
                    LinuxEnvironment.runInEnvStreaming(wrapped, scope = "terminal", onLine = onLine)
                else
                    PrivilegedExecutor.executeStreaming(wrapped, onLine, appContext)
            }
            if (stopRequested) appendChunk("[已终止]") else appendChunk("[exit " + res.exitCode + "]")
        } catch (e: kotlinx.coroutines.CancellationException) {
            appendChunk("[已终止]")
        } catch (e: Exception) {
            appendChunk("[错误] " + e.message)
        } finally {
            activePid = null
            withContext(Dispatchers.Main) { running = false }
        }
    }

    /** 终止当前命令:先杀进程组，再让 run() 收尾，避免终端卡死在 waitFor/readLine。 */
    suspend fun stop() {
        if (!running) return
        stopRequested = true
        val pid = activePid ?: withTimeoutOrNull(500L) {
            while (activePid == null && running) delay(10L)
            activePid
        }
        appendChunk("[终止] " + if (pid != null) "正在结束 PID " + pid else "正在取消命令")
        if (pid != null) {
            val result = PrivilegedExecutor.terminate(pid, appContext)
            if (!result.success && result.stderr.isNotBlank()) appendChunk("[终止失败] " + result.stderr.take(160))
        }
    }
}
