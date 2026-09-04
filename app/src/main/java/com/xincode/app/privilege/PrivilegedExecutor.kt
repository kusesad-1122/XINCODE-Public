package com.xincode.app.privilege

import android.content.Context
import com.xincode.app.root.ExecResult
import com.xincode.app.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 1.22 Shizuku/ADB 分级：Root > Shizuku > 普通 自动降级执行
 * 终端已接管，QUERY_ALL_PACKAGES 移除，仅保留 Shizuku 查询
 */
enum class PrivilegeTier(val label: String, val desc: String) {
    ROOT("Root", "uid=0 · 完全权限"),
    SHIZUKU("Shizuku", "ADB 授权 · 无需 Root"),
    NORMAL("普通", "应用沙盒 · Git/文件可用")
}

object PrivilegedExecutor {

    fun currentTier(context: Context? = null): PrivilegeTier {
        return when {
            RootShellManager.rootStatus == com.xincode.app.root.RootStatus.OK -> PrivilegeTier.ROOT
            ShizukuShell.isAvailable(context) && ShizukuShell.isPermissionGranted(context) -> PrivilegeTier.SHIZUKU
            ShizukuShell.isAvailable(context) -> PrivilegeTier.NORMAL // 已安装但未授权，仍显示 Shizuku 可请求，但执行走普通
            else -> PrivilegeTier.NORMAL
        }
    }

    fun tierLabel(context: Context? = null): String {
        // 若 Shizuku 已安装但未授权，仍提示可授权
        if (ShizukuShell.isAvailable(context) && !ShizukuShell.isPermissionGranted(context)) {
            return "Shizuku 未授权 · 点击授权"
        }
        return currentTier(context).label
    }

    suspend fun execute(command: String, context: Context? = null): ExecResult {
        // 1. Root 优先
        if (RootShellManager.rootStatus == com.xincode.app.root.RootStatus.OK) {
            try {
                val r = RootShellManager.execute(command)
                // 若 Root 执行失败且可能是权限问题，尝试降级；否则直接返回
                if (r.success || r.exitCode != -1) return r
            } catch (_: Exception) {}
        }
        // 2. Shizuku
        if (ShizukuShell.isAvailable(context) && ShizukuShell.isPermissionGranted(context)) {
            val r = ShizukuShell.execute(command, context)
            if (r.success || r.exitCode != -1 && !r.stderr.contains("未授权")) return r
        }
        // 3. 普通 sh -c
        return executeNormal(command)
    }

    /** Best-effort hard stop for a terminal shell process and its process group. */
    suspend fun terminate(pid: Long, context: Context? = null): ExecResult {
        if (pid <= 1L) return ExecResult("", "拒绝终止非法 PID", -1, 0L, false)
        val safePid = pid.toString()
        val command = "kill -TERM -$safePid 2>/dev/null; kill -TERM $safePid 2>/dev/null; sleep 1; kill -KILL -$safePid 2>/dev/null; kill -KILL $safePid 2>/dev/null"
        return execute(command, context)
    }

    suspend fun executeStreaming(command: String, onLine: (String) -> Unit, context: Context? = null): ExecResult {
        if (RootShellManager.rootStatus == com.xincode.app.root.RootStatus.OK) {
            try {
                // 尝试 Root 流式，若成功直接返回；若抛异常则降级
                return RootShellManager.executeStreaming(command, onLine)
            } catch (_: Exception) {}
        }
        if (ShizukuShell.isAvailable(context) && ShizukuShell.isPermissionGranted(context)) {
            val r = ShizukuShell.executeStreaming(command, onLine, context)
            // Shizuku 返回 -1 且提示未授权则降级到普通
            if (!r.stderr.contains("未授权")) return r
        }
        // 普通 shell 流式
        return executeNormalStreaming(command, onLine)
    }

    private suspend fun executeNormal(command: String): ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(false).start()
            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            ExecResult(stdout, stderr, code, System.currentTimeMillis() - start, code == 0)
        } catch (e: Exception) {
            ExecResult("", e.message ?: "执行异常", -1, System.currentTimeMillis() - start, false)
        }
    }

    private suspend fun executeNormalStreaming(command: String, onLine: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(false).start()
            val outT = Thread {
                try { p.inputStream.bufferedReader().forEachLine { onLine(it) } } catch (_: Exception) {}
            }
            val errT = Thread {
                try { p.errorStream.bufferedReader().forEachLine { onLine(it) } } catch (_: Exception) {}
            }
            outT.start(); errT.start()
            val code = p.waitFor()
            outT.join(2000); errT.join(2000)
            ExecResult("", "", code, System.currentTimeMillis() - start, code == 0)
        } catch (e: Exception) {
            onLine("[异常] ${e.message}")
            ExecResult("", e.message ?: "", -1, System.currentTimeMillis() - start, false)
        }
    }
}
