package com.xincode.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xincode.app.root.ExecResult
import com.xincode.app.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「内置 Linux 环境」——用 root + chroot 把一个真正的 Ubuntu(apt)用户态跑在 App 私有目录里。
 *
 * 为什么这么做:
 *  - Android 用 bionic 而非 glibc,普通 Linux 二进制不能直接跑;要一个真 Ubuntu 用户态才有 apt 生态。
 *  - 已 root → 直接用内核 chroot 进 rootfs,【不需要】打包任何无法验证的 proot 二进制。
 *  - rootfs 有几十 MB、工具链上 GB,GitHub 单文件上限 100MB、仓库也不该放 GB 二进制;
 *    所以采用「首次运行自动下载一次官方 Ubuntu base,之后 apt 现装」的方式(像游戏首启下载资源包)。
 *
 * 部署一次后常驻 App 私有目录,离线可用;工具经 [runInEnv] 在环境内用 apt 安装。
 */
object LinuxEnvironment {
    private const val TAG = "LinuxEnv"

    /**
     * Ubuntu base rootfs(arm64)候选列表——【全部国内镜像】(清华 TUNA / 南京大学 NJU / 北外 BFSU),
     * 逐个尝试取第一个 200 的。这些镜像同步自官方 cdimage,国内下载快且稳定;点释放版本会变动
     * (旧文件会被移除),若都失效可加新版本号。已实测各链接可下载且为合法 gzip。
     */
    val ROOTFS_URLS = listOf(
        // 24.04(noble)——主力,三镜像互备
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
        // 22.04(jammy)兜底(长期支持,更稳定)
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
        "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz"
    )

    enum class State { NOT_SETUP, SETTING_UP, READY, ERROR }

    var state by mutableStateOf(State.NOT_SETUP)
        private set
    /** 部署进度日志(尾部),供 UI 展示。 */
    var setupLog by mutableStateOf("")
        private set

    private var rootfsDir: File? = null
    // M2:部署互斥,防止并发/重复部署把纯净 base 重解压进正被 apt 修改的目录。
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 冷启动初始化:定位 rootfs 目录,若已部署过则标记 READY。 */
    fun init(context: Context) {
        val dir = File(context.filesDir, "ubuntu")
        rootfsDir = dir
        state = if (File(dir, ".xincode_ready").exists()) State.READY else State.NOT_SETUP
    }

    fun isReady(): Boolean = state == State.READY && rootfsDir?.let { File(it, ".xincode_ready").exists() } == true

    /** 输出汇聚端(可视终端订阅):log() 与部署/安装的流式输出都会推到这里。 */
    @Volatile
    var outputSink: ((String) -> Unit)? = null

    /** 自定义环境变量(由 EnvVarManager 注入，作用于终端与构建)。 */
    @Volatile
    var customEnvVars: List<com.xincode.app.ide.EnvVar> = emptyList()

    /** 供调用方覆盖的 env 前缀提供器(优先级高于 customEnvVars，例如按 scope 过滤)。 */
    @Volatile
    var customEnvProvider: (() -> String)? = null

    private fun log(line: String) {
        Log.i(TAG, line)
        setupLog = (setupLog + line + "\n").takeLast(4000)
        try { outputSink?.invoke(line) } catch (_: Exception) {}
    }

    /**
     * 部署基础 Ubuntu 环境:下载官方 base rootfs → root 解包 → 写 DNS/软件源 → apt update。
     * 需要 root。进度经 [setupLog]/[state] 反馈。
     */
    /**
     * @param force true 时即使已检测到有效环境也【重新部署】(先删旧再装)。默认 false:已存在则跳过下载/解包。
     */
    suspend fun bootstrap(context: Context, rootfsUrls: List<String> = ROOTFS_URLS, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // M2:原子互斥——已有部署在跑就直接拒绝(避免并发解压破坏 rootfs)。
        if (!busy.compareAndSet(false, true)) return@withContext false
        state = State.SETTING_UP
        setupLog = ""
        try {
            if (!RootShellManager.verifyRoot()) {
                log("需要 root 才能部署 Linux 环境(当前不可用)。")
                state = State.ERROR; return@withContext false
            }
            val dir = File(context.filesDir, "ubuntu").apply { mkdirs() }
            rootfsDir = dir

            // 检测:若已存在有效环境(有 /bin/bash 且标记就绪),【跳过】下载/解包,直接就绪 ——
            // 不用重新部署;工具也由安装页按 command -v 逐个检测跳过已装项。
            // 用 App 可读的 .xincode_ready 标记判断已部署(bin/bash 属 root,App 进程 stat 不到,不能用)。
            if (!force && File(dir, ".xincode_ready").exists()) {
                log("检测到已部署的 Linux 环境,跳过下载/解包。")
                state = State.READY
                log("Linux 环境已就绪 ✓")
                return@withContext true
            }
            if (force) {
                log("重新部署:清理旧环境…")
                RootShellManager.execute("for m in dev/pts dev proc sys sdcard storage data; do umount \"${dir.absolutePath}/\$m\" 2>/dev/null; done; rm -rf '${dir.absolutePath}'/*")
                File(dir, ".xincode_ready").delete()
            }
            val tarball = File(context.filesDir, "ubuntu-base.tar.gz")

            log("下载 Ubuntu base rootfs…")
            var got = false
            for (url in rootfsUrls) {
                log("尝试:${url.substringAfterLast('/')}")
                if (download(url, tarball)) { got = true; break }
                tarball.delete() // M3:失败/无效文件不残留,换下一个源
            }
            if (!got) { log("所有源都下载失败或返回内容无效(检查网络/代理)。"); state = State.ERROR; return@withContext false }
            log("下载完成:${tarball.length() / 1024 / 1024}MB,开始解包(root)…")

            val r = dir.absolutePath
            val t = tarball.absolutePath
            // 以 root 解包,保留权限/符号链接/设备节点。逐法兜底,但【每次尝试前先清空目录】——
            // 否则上一种方法部分解包留下的残留会让后续方法也失败(这正是之前的回归)。
            // 顺序:先用最稳的 gzip 管道 tar(用户设备验证可用),再 tar -xzf,最后 busybox。
            log("解包中(root,较慢请稍候)…")
            val extractCmds = listOf(
                "gzip -dc '$t' | tar -xf -" to "gzip|tar",
                "tar -xzf '$t'" to "tar -xzf",
                "busybox gzip -dc '$t' | busybox tar -xf -" to "busybox"
            )
            var extracted = false
            var lastErr = ""
            for ((cmd, label) in extractCmds) {
                // 清空(保留隐藏标记文件不影响;这里目录内容此刻应为空或残留)
                RootShellManager.execute("rm -rf '$r'/* '$r'/.[!.]* 2>/dev/null; true")
                // 关键:成功判据用【root shell 的 test -f bin/bash】—— rootfs 属主是 root、权限严,
                // App 进程(普通 uid)去 File.exists() 会因读不到而误判失败(这正是"exit=0 却判失败"的原因)。
                val ex = RootShellManager.execute("cd '$r' && ($cmd) && test -f bin/bash && echo EXTRACT_OK")
                if (ex.exitCode == 0 && ex.stdout.contains("EXTRACT_OK")) {
                    log("解包成功($label)。")
                    extracted = true; break
                }
                lastErr = (ex.stderr.ifBlank { "exit=${ex.exitCode}" }).take(200)
                log("解包法 $label 未成:$lastErr,换下一种…")
            }
            if (!extracted) {
                log("解包失败(gzip|tar / tar -xzf / busybox 均未成): $lastErr")
                state = State.ERROR; return@withContext false
            }
            tarball.delete()
            log("解包完成,写入 DNS 与软件源(国内镜像)…")
            // DNS + 保证 apt 可用(resolv.conf 常为空)。国内优先:阿里 DNS / 腾讯 DNSPod,末尾留一个公共兜底。
            RootShellManager.execute("printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 180.76.76.76\\n' > '$r/etc/resolv.conf'")
            writeAptSources(r)
            writePipConf(r)
            // 标记就绪 —— 到这一步 rootfs 已解包完好即视为部署成功。
            // 之后的 apt update 只是"预热",【绝不允许】它把状态翻回失败(加终端前用非流式不易抛,
            // 改流式后 chroot/apt 抛异常会被外层 catch 误判为部署失败——这里彻底隔离)。
            File(dir, ".xincode_ready").writeText("1")
            state = State.READY
            log("Linux 环境就绪 ✓")

            // 预热 apt(可失败:可能暂时没网/源慢),独立 try,不影响已就绪状态。
            try {
                log("初始化 apt(首次 update,可能较慢,失败可稍后重试)…")
                val up = runInEnvStreaming("apt-get update -y") { outputSink?.invoke(it) }
                if (up.exitCode != 0) log("apt update 未完成(不影响部署,联网后可重试)。")
                else log("apt 源已就绪 ✓")
            } catch (e: Exception) {
                log("apt 预热跳过(${e.message?.take(80)});环境仍可用。")
            }
            true
        } catch (e: Exception) {
            log("部署失败: ${e.message}")
            state = State.ERROR
            false
        } finally {
            busy.set(false) // M2:无论成功失败都释放部署锁
        }
    }

    /**
     * 写入 apt 软件源——【全部指向国内镜像】(清华 TUNA 的 ubuntu-ports,arm64)。用 http 而非 https,
     * 以避免裸 base rootfs 尚未装 ca-certificates 时 apt 走 TLS 失败的鸡生蛋问题。
     *  - 官方 base 自带的源(24.04 用 deb822 的 ubuntu.sources,22.04 用经典 sources.list)会把主机
     *    从 ports.ubuntu.com / archive.ubuntu.com / security.ubuntu.com 就地替换为国内镜像;
     *  - 若两者都缺/为空,则写一份国内经典 sources.list。
     * 镜像地址已实测可用。
     */
    private suspend fun writeAptSources(r: String) {
        val script = buildString {
            append("M='mirrors.tuna.tsinghua.edu.cn/ubuntu-ports'; ")
            // 1) 已自带源:把官方主机替换成国内镜像(http/https、ports/archive/security 都覆盖)。
            append("for f in '$r/etc/apt/sources.list' '$r/etc/apt/sources.list.d/ubuntu.sources'; do ")
            append("[ -f \"\$f\" ] && sed -i ")
            append("-e \"s#http://ports.ubuntu.com/ubuntu-ports#http://\$M#g\" ")
            append("-e \"s#https://ports.ubuntu.com/ubuntu-ports#http://\$M#g\" ")
            append("-e \"s#http://archive.ubuntu.com/ubuntu#http://\$M#g\" ")
            append("-e \"s#https://archive.ubuntu.com/ubuntu#http://\$M#g\" ")
            append("-e \"s#http://security.ubuntu.com/ubuntu#http://\$M#g\" ")
            append("-e \"s#https://security.ubuntu.com/ubuntu#http://\$M#g\" \"\$f\"; ")
            append("done; ")
            // 2) 两者都无/为空 → 写一份国内经典源(读版本代号,缺省 noble=24.04)。
            append("if ! test -s '$r/etc/apt/sources.list' && ! test -s '$r/etc/apt/sources.list.d/ubuntu.sources'; then ")
            append(". '$r/etc/os-release' 2>/dev/null; C=\"\${VERSION_CODENAME:-noble}\"; ")
            append("{ echo \"deb http://\$M \$C main restricted universe multiverse\"; ")
            append("echo \"deb http://\$M \$C-updates main restricted universe multiverse\"; ")
            append("echo \"deb http://\$M \$C-security main restricted universe multiverse\"; ")
            append("} > '$r/etc/apt/sources.list'; fi; echo APT_SRC_DONE")
        }
        try { RootShellManager.execute(script) } catch (_: Exception) {}
    }

    /**
     * 写入 pip 全局配置 /etc/pip.conf——指向国内 PyPI 镜像(清华 TUNA),让环境内所有 pip 安装走国内源。
     * 镜像地址已实测可用。
     */
    private suspend fun writePipConf(r: String) {
        val script = buildString {
            append("mkdir -p '$r/etc'; ")
            append("printf '[global]\\n")
            append("index-url = https://pypi.tuna.tsinghua.edu.cn/simple\\n")
            append("[install]\\n")
            append("trusted-host = pypi.tuna.tsinghua.edu.cn\\n' > '$r/etc/pip.conf'; echo PIP_CONF_DONE")
        }
        try { RootShellManager.execute(script) } catch (_: Exception) {}
    }

    /** 构造 chroot 执行脚本(幂等绑定挂载 /dev /proc /sys /dev/pts + 宿主存储 后进入环境跑 cmd)。 */
    private fun buildChrootScript(r: String, cmd: String, scope: String? = null): String = buildString {
        append("R='").append(r).append("'; ")
        // 基础伪文件系统
        append("for m in dev proc sys dev/pts; do mkdir -p \"\$R/\$m\" 2>/dev/null; ")
        append("mountpoint -q \"\$R/\$m\" 2>/dev/null || mount --bind \"/\$m\" \"\$R/\$m\" 2>/dev/null; done; ")
        // 宿主存储透传：让 chroot 内可访问项目目录（/sdcard /storage /data）
        // 不逐一硬编码子路径，整块 bind，失败静默（部分ROM无/sdcard）
        append("for m in sdcard storage data; do if [ -e \"/\$m\" ]; then mkdir -p \"\$R/\$m\" 2>/dev/null; mountpoint -q \"\$R/\$m\" 2>/dev/null || mount --bind \"/\$m\" \"\$R/\$m\" 2>/dev/null; fi; done; ")
        val customPrefix = when {
            scope != null -> envPrefixForScope(scope)
            customEnvProvider != null -> customEnvProvider?.invoke() ?: ""
            customEnvVars.isNotEmpty() -> customEnvVars.joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
            else -> ""
        }
        val envAssignments = buildString {
            append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            append("HOME=/root TERM=xterm DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 ")
            if (customPrefix.isNotBlank()) append(customPrefix).append(" ")
        }
        append("chroot \"\$R\" /usr/bin/env -i ").append(envAssignments)
        // scope 非空时 env -i 已注入过滤后变量，不再二次 export；否则按原逻辑 export all
        val wrapped = if (scope != null) cmd else wrapWithCustomEnv(cmd)
        append("/bin/bash -lc ").append(shellQuote(wrapped))
    }

    private fun wrapWithCustomEnv(cmd: String): String {
        // 若调用方通过 customEnvProvider 提供了按 scope 过滤的 env 前缀（env -i 已注入），则不再在 bash 内重复 export，避免 all/terminal/build 作用域泄漏
        if (customEnvProvider != null) return cmd
        if (customEnvVars.isEmpty()) return cmd
        val exports = customEnvVars.joinToString("; ") { "export ${it.key}=${shellQuote(it.value)}" }
        return if (exports.isBlank()) cmd else "$exports; $cmd"
    }

    /** 按 scope 过滤获取 env 前缀，供调用方临时设置 customEnvProvider 使用（all/terminal/build） */
    fun envPrefixForScope(scope: String?): String {
        if (customEnvVars.isEmpty()) return ""
        val filtered = if (scope == null) customEnvVars else customEnvVars.filter { it.scope == "all" || it.scope == scope }
        if (filtered.isEmpty()) return ""
        return filtered.joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
    }

    /** 在指定 scope 下执行（自动处理 provider 的设置/还原，避免泄漏）- 已废弃，保留兼容但存在并发竞态，建议直接用 runInEnv(scope) */
    @Deprecated("改用 runInEnv(cmd, scope) 以避免全局竞争")
    suspend fun <T> withScopeEnv(scope: String?, block: suspend () -> T): T {
        val prev = customEnvProvider
        return try {
            if (scope != null && customEnvVars.isNotEmpty()) {
                val prefix = envPrefixForScope(scope)
                customEnvProvider = { prefix }
            }
            block()
        } finally {
            customEnvProvider = prev
        }
    }

    /** 在环境内执行命令(root chroot),阻塞返回汇总。scope 用于 env 作用域过滤（terminal/build/null=all） */
    suspend fun runInEnv(cmd: String, scope: String? = null): ExecResult = withContext(Dispatchers.IO) {
        val dir = rootfsDir ?: return@withContext ExecResult("", "环境未初始化", 1, 0L, false)
        RootShellManager.execute(buildChrootScript(dir.absolutePath, cmd, scope))
    }

    /** 在环境内【流式】执行命令,输出逐行回调 [onLine](可视终端/部署进度用)。scope 用于 env 作用域过滤 */
    suspend fun runInEnvStreaming(cmd: String, scope: String? = null, onLine: (String) -> Unit): ExecResult = withContext(Dispatchers.IO) {
        val dir = rootfsDir ?: return@withContext ExecResult("", "环境未初始化", 1, 0L, false)
        RootShellManager.executeStreaming(buildChrootScript(dir.absolutePath, cmd, scope), onLine)
    }

    /** 卸载/清空环境(释放空间)。 */
    suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        val dir = rootfsDir ?: return@withContext true
        val r = dir.absolutePath
        // 先尝试卸载挂载点,避免误删宿主 /dev 等。
        RootShellManager.execute("for m in dev/pts dev proc sys sdcard storage data; do umount \"$r/\$m\" 2>/dev/null; done; rm -rf '$r'")
        state = State.NOT_SETUP
        setupLog = ""
        true
    }

    // ---- helpers ----

    private fun download(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "curl/8.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { log("下载失败 HTTP ${resp.code}"); return false }
                val body = resp.body ?: run { log("下载失败:空响应"); return false }
                dest.outputStream().use { out -> body.byteStream().copyTo(out, 1 shl 16) }
            }
            // M3:校验确实是 gzip(magic 0x1f 0x8b)——防止代理/被劫持镜像返回 200 的 HTML 被当成功以 root 解压。
            if (dest.length() < 1024 || !isGzip(dest)) {
                log("下载内容无效(非 gzip,可能是代理/登录页)。")
                return false
            }
            true
        } catch (e: Exception) {
            log("下载异常: ${e.message}"); false
        }
    }

    /** 检查文件头两字节是否为 gzip magic。 */
    private fun isGzip(f: File): Boolean = try {
        f.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }
    } catch (_: Exception) { false }

    /** 单引号安全转义,供 bash -lc '<cmd>' 使用。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
