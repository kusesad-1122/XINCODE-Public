package com.xincode.app.privilege

import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Shizuku 反射封装：编译期不依赖 api，避免 Maven 拉取；运行时若设备安装 Shizuku 则通过反射调用。
 */
object ShizukuShell {

    @Volatile private var activeStreamingProcess: Process? = null

    fun terminateCurrentProcess(): Boolean {
        val process = activeStreamingProcess ?: return false
        runCatching { process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
        activeStreamingProcess = null
        return true
    }

    private fun shizukuClass(): Class<*>? = try { Class.forName("rikka.shizuku.Shizuku") } catch (_: Exception) { null }

    private fun isShizukuPackagesInstalled(pm: PackageManager): Boolean {
        val candidates = listOf("moe.shizuku.privileged.api", "rikka.shizuku")
        for (pkg in candidates) {
            try {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    fun isAvailable(context: android.content.Context? = null): Boolean {
        // 优先用反射 pingBinder（需 api 在 classpath），否则退化为包可见性探测，避免恒 false
        try {
            val c = shizukuClass()
            if (c != null) {
                val m = c.getMethod("pingBinder")
                if (m.invoke(null) as? Boolean == true) return true
            }
        } catch (_: Exception) {}
        // 包名探测：优先用 PackageManager，其次 shell pm，避免无 context 时恒 false
        return try {
            if (context != null) return isShizukuPackagesInstalled(context.packageManager)
            // 无 context 时退化为 shell pm 查询（普通 shell 即可）
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm list packages 2>/dev/null | grep -qiE 'shizuku|privileged\\.api' && echo yes"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.equals("yes", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun isPermissionGranted(context: android.content.Context? = null): Boolean {
        return try {
            val c = shizukuClass()
            if (c != null) {
                val m = c.getMethod("checkSelfPermission")
                val r = m.invoke(null) as? Int ?: PackageManager.PERMISSION_DENIED
                if (r == PackageManager.PERMISSION_GRANTED) return true
            }
            // 反射不可用时无法精确判授权，保守返回 false，交由调用方按 NORMAL 降级
            false
        } catch (_: Exception) { false }
    }

    fun requestPermission(code: Int = 1001) {
        try {
            val c = shizukuClass() ?: return
            val m = c.getMethod("requestPermission", Int::class.javaPrimitiveType)
            m.invoke(null, code)
        } catch (_: Exception) {}
    }

    suspend fun execute(command: String, context: android.content.Context? = null): com.xincode.app.root.ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            if (!isAvailable(context) || !isPermissionGranted(context)) {
                return@withContext com.xincode.app.root.ExecResult("", "Shizuku 未授权或未运行", -1, System.currentTimeMillis() - start, false)
            }
            val c = shizukuClass() ?: return@withContext com.xincode.app.root.ExecResult("", "Shizuku 类不存在", -1, 0, false)
            val m = c.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            // newProcess(cmd, env, dir) -> Process
            val process = m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return@withContext com.xincode.app.root.ExecResult("", "Shizuku 启动进程失败", -1, 0, false)
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val code = process.waitFor()
            com.xincode.app.root.ExecResult(stdout.trim(), stderr.trim(), code, System.currentTimeMillis() - start, code == 0)
        } catch (e: Exception) {
            com.xincode.app.root.ExecResult("", "Shizuku 执行异常: ${e.message}", -1, System.currentTimeMillis() - start, false)
        }
    }

    suspend fun executeStreaming(command: String, onLine: (String) -> Unit, context: android.content.Context? = null): com.xincode.app.root.ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            if (!isAvailable(context) || !isPermissionGranted(context)) {
                onLine("[Shizuku 未授权]")
                return@withContext com.xincode.app.root.ExecResult("", "Shizuku 未授权", -1, System.currentTimeMillis() - start, false)
            }
            val c = shizukuClass() ?: return@withContext com.xincode.app.root.ExecResult("", "Shizuku 类不存在", -1, 0, false)
            val m = c.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            val process = m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return@withContext com.xincode.app.root.ExecResult("", "Shizuku 启动失败", -1, 0, false)
            activeStreamingProcess = process
            val outT = Thread {
                try { process.inputStream.bufferedReader().forEachLine { onLine(it) } } catch (_: Exception) {}
            }
            val errT = Thread {
                try { process.errorStream.bufferedReader().forEachLine { onLine(it) } } catch (_: Exception) {}
            }
            outT.start(); errT.start()
            val code = process.waitFor()
            outT.join(2000); errT.join(2000)
            if (activeStreamingProcess === process) activeStreamingProcess = null
            com.xincode.app.root.ExecResult("", "", code, System.currentTimeMillis() - start, code == 0)
        } catch (e: Exception) {
            onLine("[Shizuku 异常] ${e.message}")
            com.xincode.app.root.ExecResult("", e.message ?: "", -1, System.currentTimeMillis() - start, false)
        }
    }
}
