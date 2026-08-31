package com.xincode.app

import android.util.Log

/**
 * 「环境配置」内置开发环境/工具的目录 + 安装引擎。
 *
 * 运行在 [LinuxEnvironment] 提供的内置 Ubuntu(apt)用户态里(root + chroot)。检测与安装命令都在
 * 该环境内执行:检测用 `command -v`,安装统一走 apt / 语言官方安装器。环境未部署时,UI 先引导部署。
 */

/** 单个可安装项。 */
data class EnvTool(
    val id: String,
    val name: String,
    val desc: String,
    /** 环境内检测是否已安装:退出码 0 = 已安装。 */
    val detectCmd: String,
    /** 环境内安装命令(在 Ubuntu 里执行)。 */
    val installCmd: String
)

/** 一组同类工具。 */
data class EnvCategory(
    val title: String,
    val subtitle: String,
    val required: Boolean,
    val tools: List<EnvTool>
)

object EnvCatalog {
    val categories: List<EnvCategory> = listOf(
        EnvCategory("Node.js 环境", "Node.js 和前端开发环境", required = true, tools = listOf(
            EnvTool("node", "Node.js", "JavaScript 运行时",
                "command -v node", "apt-get install -y nodejs npm"),
            EnvTool("pnpm", "PNPM", "快速的包管理器和 TypeScript",
                "command -v pnpm",
                "npm install -g pnpm typescript || (apt-get install -y npm && npm install -g pnpm typescript)")
        )),
        EnvCategory("Python 环境", "Python 开发环境", required = true, tools = listOf(
            EnvTool("python_link", "Python 链接", "将 python 命令链接到 python3",
                "command -v python",
                "apt-get install -y python3 && ln -sf \"\$(command -v python3)\" /usr/local/bin/python"),
            EnvTool("venv", "虚拟环境", "Python 虚拟环境支持",
                "python3 -m venv --help >/dev/null 2>&1", "apt-get install -y python3-venv"),
            EnvTool("pip", "Pip", "Python 包管理器",
                "command -v pip3 || command -v pip", "apt-get install -y python3-pip"),
            EnvTool("uv", "uv", "一个用 Rust 编写的极速 Python 包安装器",
                "command -v uv",
                // 走国内 PyPI(/etc/pip.conf 已配清华源;再显式带 -i 兜底)。
                "apt-get install -y python3-pip && (pip3 install -U uv || pip install -U uv || pip3 install -U -i https://pypi.tuna.tsinghua.edu.cn/simple uv)")
        )),
        EnvCategory("SSH 工具", "SSH 客户端和密码认证工具", required = false, tools = listOf(
            EnvTool("ssh", "SSH 客户端", "SSH 连接客户端",
                "command -v ssh", "apt-get install -y openssh-client"),
            EnvTool("sshpass", "sshpass", "SSH 密码认证工具",
                "command -v sshpass", "apt-get install -y sshpass"),
            EnvTool("sshd", "OpenSSH 服务器", "用于反向隧道挂载本地文件系统",
                "command -v sshd", "apt-get install -y openssh-server")
        )),
        EnvCategory("Java 环境", "Java 11/17 双版本 + Gradle", required = false, tools = listOf(
            EnvTool("jdk11", "OpenJDK 11", "Java 11 (兼容旧版 Gradle/AGP)",
                "test -d /usr/lib/jvm/java-11-openjdk-arm64 && /usr/lib/jvm/java-11-openjdk-arm64/bin/java -version >/dev/null 2>&1",
                "apt-get install -y openjdk-11-jdk-headless && update-alternatives --set java /usr/lib/jvm/java-11-openjdk-arm64/bin/java 2>/dev/null || true"),
            EnvTool("jdk17", "OpenJDK 17", "Java 17 开发环境 (默认)",
                "test -d /usr/lib/jvm/java-17-openjdk-arm64 && /usr/lib/jvm/java-17-openjdk-arm64/bin/java -version >/dev/null 2>&1",
                "apt-get install -y openjdk-17-jdk-headless && update-alternatives --set java /usr/lib/jvm/java-17-openjdk-arm64/bin/java 2>/dev/null || true"),
            EnvTool("gradle", "Gradle", "现代化的构建自动化工具",
                "command -v gradle", "apt-get install -y gradle")
        )),
        EnvCategory("Rust (Cargo) 环境", "Rust 开发环境和包管理器", required = false, tools = listOf(
            EnvTool("rust", "Rust & Cargo", "通过 rustup 安装 Rust 工具链",
                "command -v cargo",
                // 走国内 rsproxy 镜像:rustup-init 脚本 + 工具链下载 + cargo crates 索引全部国内;失败回退 apt(国内源)。
                "(apt-get install -y curl ca-certificates && " +
                    "export RUSTUP_DIST_SERVER=https://rsproxy.cn && export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup && " +
                    "curl --proto '=https' --tlsv1.2 -sSf https://rsproxy.cn/rustup-init.sh | sh -s -- -y && " +
                    "mkdir -p /root/.cargo && printf '[source.crates-io]\\nreplace-with = \"rsproxy-sparse\"\\n" +
                    "[source.rsproxy-sparse]\\nregistry = \"sparse+https://rsproxy.cn/index/\"\\n" +
                    "[net]\\ngit-fetch-with-cli = true\\n' > /root/.cargo/config.toml && " +
                    "ln -sf /root/.cargo/bin/* /usr/local/bin/) || apt-get install -y rustc cargo")
        )),
        EnvCategory("Go 环境", "Go 语言开发环境", required = false, tools = listOf(
            EnvTool("go", "Go", "Go 编程语言",
                "command -v go", "apt-get install -y golang-go")
        )),
        EnvCategory("Android 构建", "编译 APK 用的 SDK / NDK(下载较大,约 2GB)", required = false, tools = listOf(
            EnvTool("android_ndk", "Android SDK/NDK", "编译 APK 的命令行工具、build-tools、平台与 NDK",
                "ls /opt/android-sdk/ndk 2>/dev/null | grep -q .",
                "apt-get install -y wget unzip openjdk-17-jdk-headless && mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp && " +
                    // 命令行工具从国内腾讯 AndroidSDK 镜像下载(已实测可用);dl.google 作最后兜底。
                    "(wget -qO cmd.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip || " +
                    "wget -qO cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip) && " +
                    "unzip -oq cmd.zip -d /opt/android-sdk/cmdline-tools && " +
                    "(mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest 2>/dev/null || true) && " +
                    "yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk " +
                    "'platform-tools' 'build-tools;34.0.0' 'platforms;android-34' 'ndk;26.1.10909125' && " +
                    "printf 'export ANDROID_HOME=/opt/android-sdk\\nexport ANDROID_SDK_ROOT=/opt/android-sdk\\n" +
                    "export PATH=\$PATH:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin\\n' > /etc/profile.d/android.sh")
        ))
    )

    val allTools: List<EnvTool> get() = categories.flatMap { it.tools }
}

object JdkManager {
    const val JDK11_HOME = "/usr/lib/jvm/java-11-openjdk-arm64"
    const val JDK17_HOME = "/usr/lib/jvm/java-17-openjdk-arm64"

    data class JdkInfo(val version: String, val home: String, val installed: Boolean, val active: Boolean)

    suspend fun list(): List<JdkInfo> {
        if (!LinuxEnvironment.isReady()) return emptyList()
        val activeHome = getActiveHome()
        return listOf(
            check("11", JDK11_HOME, activeHome),
            check("17", JDK17_HOME, activeHome)
        )
    }

    private suspend fun check(ver: String, home: String, activeHome: String): JdkInfo {
        val installed = try { LinuxEnvironment.runInEnv("test -d $home && test -x $home/bin/java").exitCode == 0 } catch (_: Exception) { false }
        return JdkInfo(ver, home, installed, home == activeHome && installed)
    }

    suspend fun getActiveHome(): String = try {
        val r = LinuxEnvironment.runInEnv("readlink -f \$(readlink -f /etc/alternatives/java 2>/dev/null || command -v java) 2>/dev/null | sed 's|/bin/java||'")
        r.stdout.trim().takeIf { it.isNotBlank() } ?: ""
    } catch (_: Exception) { "" }

    suspend fun getActiveVersion(): String = try {
        val r = LinuxEnvironment.runInEnv("java -version 2>&1 | head -n1")
        Regex("\"([^\"]+)\"").find(r.stdout)?.groupValues?.get(1)?.substringBefore(".") ?: r.stdout.trim()
    } catch (_: Exception) { "" }

    suspend fun switchTo(version: String): Boolean {
        val home = when (version) { "11" -> JDK11_HOME; "17" -> JDK17_HOME; else -> return false }
        return try {
            val cmd = "update-alternatives --set java $home/bin/java 2>/dev/null; " +
                "update-alternatives --set javac $home/bin/javac 2>/dev/null; " +
                "echo \"export JAVA_HOME=$home\" > /etc/profile.d/jdk.sh; echo \"export PATH=\\\$JAVA_HOME/bin:\\\$PATH\" >> /etc/profile.d/jdk.sh; " +
                "export JAVA_HOME=$home; java -version"
            val r = LinuxEnvironment.runInEnvStreaming(cmd) { LinuxEnvironment.outputSink?.invoke(it) }
            r.exitCode == 0
        } catch (_: Exception) { false }
    }
}

/** 安装引擎:在内置 Ubuntu 环境(root chroot)内检测/安装,状态经 UI 反馈。 */
object EnvSetupManager {
    private const val TAG = "EnvSetup"

    /** 环境内检测单个工具是否已安装(退出码 0)。环境未就绪时一律视为未安装。 */
    suspend fun isInstalled(tool: EnvTool): Boolean {
        if (!LinuxEnvironment.isReady()) return false
        return try {
            LinuxEnvironment.runInEnv(tool.detectCmd).exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "detect ${tool.id} failed: ${e.message}"); false
        }
    }

    /** 环境内安装单个工具;返回 (成功, 日志尾部)。安装前确保 apt 索引已更新。输出【流式】进可视终端。 */
    suspend fun install(tool: EnvTool): Pair<Boolean, String> {
        if (!LinuxEnvironment.isReady()) return false to "Linux 环境尚未部署"
        return try {
            val sink = LinuxEnvironment.outputSink
            sink?.invoke("\n$ 安装 ${tool.name} …")
            val tailBuf = StringBuilder()
            val r = LinuxEnvironment.runInEnvStreaming("apt-get update -y; ${tool.installCmd}") { line ->
                sink?.invoke(line)
                tailBuf.append(line).append('\n')
                if (tailBuf.length > 4000) tailBuf.delete(0, tailBuf.length - 2000)
            }
            val ok = r.exitCode == 0
            Log.i(TAG, "install ${tool.id}: exit=${r.exitCode}")
            ok to tailBuf.toString().trim().takeLast(500)
        } catch (e: Exception) {
            Log.w(TAG, "install ${tool.id} failed: ${e.message}")
            false to (e.message ?: "未知错误")
        }
    }
}
