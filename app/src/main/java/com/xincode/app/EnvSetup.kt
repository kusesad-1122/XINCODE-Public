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
                "apt-get install -y python3-pip && (pip3 install -U uv || pip install -U uv) || (apt-get install -y curl && curl -LsSf https://astral.sh/uv/install.sh | sh)")
        )),
        EnvCategory("SSH 工具", "SSH 客户端和密码认证工具", required = false, tools = listOf(
            EnvTool("ssh", "SSH 客户端", "SSH 连接客户端",
                "command -v ssh", "apt-get install -y openssh-client"),
            EnvTool("sshpass", "sshpass", "SSH 密码认证工具",
                "command -v sshpass", "apt-get install -y sshpass"),
            EnvTool("sshd", "OpenSSH 服务器", "用于反向隧道挂载本地文件系统",
                "command -v sshd", "apt-get install -y openssh-server")
        )),
        EnvCategory("Java 环境", "Java 开发环境", required = false, tools = listOf(
            EnvTool("jdk17", "OpenJDK 17", "Java 17 开发环境",
                "command -v java", "apt-get install -y openjdk-17-jdk-headless"),
            EnvTool("gradle", "Gradle", "现代化的构建自动化工具",
                "command -v gradle", "apt-get install -y gradle")
        )),
        EnvCategory("Rust (Cargo) 环境", "Rust 开发环境和包管理器", required = false, tools = listOf(
            EnvTool("rust", "Rust & Cargo", "通过 rustup 安装 Rust 工具链",
                "command -v cargo",
                "(apt-get install -y curl && curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y && ln -sf /root/.cargo/bin/* /usr/local/bin/) || apt-get install -y rustc cargo")
        )),
        EnvCategory("Go 环境", "Go 语言开发环境", required = false, tools = listOf(
            EnvTool("go", "Go", "Go 编程语言",
                "command -v go", "apt-get install -y golang-go")
        )),
        EnvCategory("Android 构建", "编译 APK 用的 SDK / NDK(下载较大,约 2GB)", required = false, tools = listOf(
            EnvTool("android_ndk", "Android SDK/NDK", "编译 APK 的命令行工具、build-tools、平台与 NDK",
                "ls /opt/android-sdk/ndk 2>/dev/null | grep -q .",
                "apt-get install -y wget unzip openjdk-17-jdk-headless && mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp && " +
                    "wget -qO cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && " +
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
