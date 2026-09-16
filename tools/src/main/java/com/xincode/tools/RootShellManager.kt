package com.xincode.tools

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * XINCODE root shell 管理器，基于 libsu。
 *
 * 不要再用 Runtime.exec("su") 或 ProcessBuilder("su")。
 * 全部走 libsu 的 Shell API。
 *
 * 使用前必须调用 init() 一次（在 Application.onCreate）。
 *
 * ## ⚠️ 这是**唯一**的一份实现（2026-09-16 合并）
 *
 * 原先 `app/src/main/java/com/xincode/app/root/RootShellManager.kt` 有一份近重复的拷贝，
 * 结果是**两个 object、两套独立 `rootStatus`**：
 *   - `XincodeApplication.onCreate` 调的是**本类**的 `init()`
 *   - `PrivilegedExecutor` 读的却是**那份拷贝**的 `rootStatus`，而它的 `init()` 从来没被调用过
 *   → `rootStatus` 恒为 UNKNOWN，`PrivilegedExecutor` **永远拿不到 ROOT 档**
 *     （除非用户恰好进过 Linux 环境页，那条路径会顺带刷新拷贝的状态）。
 * 已删除那份拷贝，`LinuxEnvironment` / `PrivilegedExecutor` 改为引用本类。
 * **不要再复制一份出去。**
 */
object RootShellManager {

    private const val TAG = "RootShellManager"

    /** 流式执行的兜底超时。 */
    private const val STREAM_TIMEOUT_MS = 300_000L

    @Volatile
    var rootStatus: RootStatus = RootStatus.UNKNOWN
        private set

    /**
     * 初始化 libsu。在 Application.onCreate 里调用，只调一次。
     */
    fun init() {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR.inv() and Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )
        Log.i(TAG, "libsu builder initialized")
    }

    /**
     * 验证 root 是否真正可用。返回 true 表示 uid=0。
     * 返回值必须被尊重，不能 try/catch 后默认 true。
     */
    suspend fun verifyRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("id -u").exec()
            val out = result.out.joinToString("\n").trim()
            val code = result.code

            Log.i(TAG, "verifyRoot: code=$code, out='$out'")

            if (code != 0) {
                rootStatus = RootStatus.NOT_AVAILABLE
                Log.e(TAG, "verifyRoot FAILED: id -u exit code $code")
                return@withContext false
            }

            if (out != "0") {
                rootStatus = RootStatus.NOT_ROOT
                Log.e(TAG, "verifyRoot FAILED: id -u returned '$out', expected '0'")
                return@withContext false
            }

            rootStatus = RootStatus.OK
            Log.i(TAG, "verifyRoot OK: uid=0 confirmed")
            return@withContext true
        } catch (e: Exception) {
            rootStatus = RootStatus.ERROR
            Log.e(TAG, "verifyRoot EXCEPTION", e)
            return@withContext false
        }
    }

    /**
     * 执行一条 root 命令。这是 AgentCore 调工具时应该走的唯一路径。
     */
    suspend fun execute(command: String): ExecResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val result = Shell.cmd(command).exec()
            val durationMs = System.currentTimeMillis() - startMs

            ExecResult(
                stdout = result.out.joinToString("\n"),
                stderr = result.err.joinToString("\n"),
                exitCode = result.code,
                durationMs = durationMs,
                success = result.isSuccess
            )
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            Log.e(TAG, "execute EXCEPTION: command='$command'", e)
            ExecResult(
                stdout = "",
                stderr = "RootShellManager exception: ${e.message}\n${e.stackTraceToString()}",
                exitCode = -1,
                durationMs = durationMs,
                success = false
            )
        }
    }

    /**
     * 流式执行：命令输出【逐行实时】回调 [onLine]（stdout+stderr 合并），用于可视终端/部署进度。
     * 返回退出码等汇总（out/err 不再累积，已经流式给了 onLine）。
     *
     * 从原 `app/root/` 拷贝合并过来 —— 那份有、这份没有，正是两份漂移的证据。
     */
    suspend fun executeStreaming(command: String, onLine: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            try {
                val sink = object : com.topjohnwu.superuser.CallbackList<String>() {
                    override fun onAddElement(e: String?) { if (e != null) onLine(e) }
                }
                val r = Shell.cmd(command).to(sink, sink).exec()
                ExecResult(
                    stdout = "",
                    stderr = "",
                    exitCode = r.code,
                    durationMs = System.currentTimeMillis() - startMs,
                    success = r.isSuccess
                )
            } catch (e: Exception) {
                Log.e(TAG, "executeStreaming EXCEPTION: command='$command'", e)
                onLine("[异常] ${e.message}")
                ExecResult("", e.message ?: "", -1, System.currentTimeMillis() - startMs, false)
            }
        }
        if (result == null) {
            onLine("[超时] 命令超过 ${STREAM_TIMEOUT_MS / 1000}s 未结束")
            ExecResult("", "命令超时 (${STREAM_TIMEOUT_MS / 1000}s)", -1, System.currentTimeMillis() - startMs, false)
        } else result
    }

    /**
     * 并发测试：连续跑 5 条不同命令，验证输出是否互相串流。
     * 临时方法，验收完成后可删除。
     */
    suspend fun stressTest(): String = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val commands = listOf("id", "whoami", "pwd", "echo TEST_$1", "date")
        commands.forEachIndexed { idx, cmd ->
            val r = execute(cmd)
            results.add("[$idx] $cmd -> exit=${r.exitCode} | stdout='${r.stdout.trim()}'")
        }
        results.joinToString("\n")
    }
}

enum class RootStatus {
    UNKNOWN,
    OK,
    NOT_AVAILABLE,
    NOT_ROOT,
    ERROR,
}

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val success: Boolean
)