package com.xincode.app.ide

import com.xincode.app.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SdkPackage(
    val path: String,
    val version: String = "",
    val description: String = "",
    val installed: Boolean = false
)

object SdkManager {
    const val SDK_ROOT = "/opt/android-sdk"
    val defaultPackages = listOf(
        "platform-tools" to "Platform Tools",
        "build-tools;34.0.0" to "Build Tools 34.0.0",
        "build-tools;33.0.2" to "Build Tools 33.0.2",
        "platforms;android-34" to "Platform android-34",
        "platforms;android-33" to "Platform android-33",
        "ndk;26.1.10909125" to "NDK 26.1.10909125",
        "ndk;25.1.8937393" to "NDK 25.1.8937393",
        "cmdline-tools;latest" to "Cmdline-tools",
        "emulator" to "Emulator",
        "cmake;3.22.1" to "CMake"
    )

    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext false
        LinuxEnvironment.runInEnv("test -x $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager && echo ok").stdout.contains("ok")
    }

    suspend fun listInstalled(): List<String> = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext emptyList()
        val r = LinuxEnvironment.runInEnv("$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_ROOT --list_installed 2>&1 | grep -E '^[[:space:]]*(build-tools|platforms|ndk|platform-tools|emulator|cmake|cmdline-tools)' | awk '{print \$1}'")
        r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    suspend fun listAvailable(): List<String> = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext emptyList()
        val r = LinuxEnvironment.runInEnv("$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_ROOT --list 2>&1 | grep -E '^[[:space:]]*(build-tools|platforms|ndk|platform-tools|emulator|cmake|cmdline-tools)' | head -n 60")
        r.stdout.lines().map { it.trim().substringBefore(" ").trim() }.filter { it.isNotBlank() }
    }

    suspend fun install(pkg: String, onLog: (String)->Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext false
        val cmd = "yes | $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_ROOT '$pkg' 2>&1"
        val r = LinuxEnvironment.runInEnvStreaming(cmd, scope = "build", onLine = onLog)
        r.exitCode == 0
    }

    suspend fun uninstall(pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext false
        LinuxEnvironment.runInEnv("$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_ROOT --uninstall '$pkg' 2>&1", scope = "build").exitCode == 0
    }

    suspend fun sdkRootInfo(): String = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext "环境未就绪"
        val r = LinuxEnvironment.runInEnv("du -sh $SDK_ROOT 2>&1 | head -n1; echo '---'; ls -1 $SDK_ROOT 2>&1 | head -n30")
        if (r.stdout.isBlank()) r.stderr else r.stdout
    }

    suspend fun ensureBase(onLog: (String)->Unit = {}): Boolean {
        if (isInstalled()) return true
        val tool = com.xincode.app.EnvCatalog.categories.flatMap { it.tools }.firstOrNull { it.id == "android_ndk" } ?: return false
        val (ok, _) = com.xincode.app.EnvSetupManager.install(tool)
        return ok
    }
}
