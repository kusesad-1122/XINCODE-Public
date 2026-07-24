package com.xincode.app

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Detects root / su availability on the device.
 *
 * ## Detection method:
 * Executes `su -c id` with a5s timeout. Returns one of four states:
 * - ROOT_GRANTED: su available and command executed successfully (exit0)
 * - ROOT_DENIED: su exists but command failed (exit≠0 or user denied)
 * - NO_ROOT: no su binary found in PATH
 * - TIMEOUT: command timed out after5s
 *
 * Detection runs once on construction, can be re-triggered via [recheck].
 */
class RootDetector {

    enum class Status(val label: String) {
        CHECKING("检测中…"),
        ROOT_GRANTED("● root 已授权"),
        ROOT_DENIED("○ root 未授权"),
        NO_ROOT("✗ 无 root"),
        TIMEOUT("[!] 检测超时")
    }

    private var _status: Status = Status.CHECKING
    val status: Status get() = _status

    private var _detail: String = ""
    val detail: String get() = _detail

    fun start() { Thread { detect() }.start() }

    fun recheck() {
        _status = Status.CHECKING
        _detail = ""
        Thread { detect() }.start()
    }

    private fun detect() {
        try {
            val suPath = findSu()
            if (suPath == null) {
                _status = Status.NO_ROOT
                _detail = "未找到 su 二进制文件"
                return
            }
            // Execute su -c id with timeout
            val result = executeWithTimeout(suPath)
            when {
                result == null -> {
                    _status = Status.TIMEOUT
                    _detail = "su -c id 执行超时 (5s)"
                }
                result.exitCode ==0 -> {
                    _status = Status.ROOT_GRANTED
                    _detail = result.stdout.trim().take(200)
                }
                else -> {
                    _status = Status.ROOT_DENIED
                    _detail = "exit=${result.exitCode} ${result.stderr.trim().take(200)}"
                }
            }
        } catch (e: Exception) {
            _status = Status.NO_ROOT
            _detail = "检测异常: ${e.message}"
        }
    }

    // ---- internal ----

    private fun findSu(): String? {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/su/bin/su",
            "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
            "/data/local/bin/su", "/data/local/xbin/su", "/magisk/.core/bin/su"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        // Fallback: try which su via shell
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which su"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (out.isNotEmpty() && File(out).exists()) out else null
        } catch (_: Exception) { null }
    }

    private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun executeWithTimeout(suPath: String): ExecResult? {
        val proc = Runtime.getRuntime().exec(arrayOf(suPath, "-c", "id"))
        val stdout = proc.inputStream.bufferedReader().use { it.readText() }
        val stderr = proc.errorStream.bufferedReader().use { it.readText() }
        val completed = proc.waitFor(5, TimeUnit.SECONDS)
        return if (completed) ExecResult(proc.exitValue(), stdout, stderr) else {
            proc.destroyForcibly()
            null
        }
    }
}