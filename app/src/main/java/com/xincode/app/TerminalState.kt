package com.xincode.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.xincode.app.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可视终端(Application 级)。三路输出汇聚到这里、实时上屏:
 *  - 用户在终端页手输的命令;
 *  - Linux 环境【部署 / 工具安装】的流式输出(LinuxEnvironment.outputSink);
 *  - AI 通过 env_exec 工具在环境里跑的命令(让用户看到 AI 在操作什么)。
 */
class TerminalState {
    val lines: SnapshotStateList<String> = mutableStateListOf(
        "XINCODE 终端 — 已就绪时命令在内置 Ubuntu 环境(chroot)执行,否则在 root shell。",
        ""
    )
    var running by mutableStateOf(false)
        private set

    private val maxLines = 4000

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

    /** 执行一条命令:环境就绪→在 Ubuntu 内,否则→root shell。输出流式上屏。 */
    suspend fun run(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        // running 必须在主线程变更（Compose state 约束）
        withContext(Dispatchers.Main) { running = true }
        appendChunk("$ $c")
        try {
            val res = withContext(Dispatchers.IO) {
                if (LinuxEnvironment.isReady())
                    LinuxEnvironment.runInEnvStreaming(c, scope = "terminal") { appendChunk(it) }
                else
                    RootShellManager.executeStreaming(c) { appendChunk(it) }
            }
            appendChunk("[exit ${res.exitCode}]")
        } catch (e: Exception) {
            appendChunk("[错误] ${e.message}")
        } finally {
            withContext(Dispatchers.Main) { running = false }
        }
    }
}
