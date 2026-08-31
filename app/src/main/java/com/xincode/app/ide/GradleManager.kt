package com.xincode.app.ide

import com.xincode.app.LinuxEnvironment
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class GradleTask(val name: String, val description: String, val group: String = "")
data class GradleProjectInfo(
    val rootPath: String,
    val hasWrapper: Boolean,
    val wrapperVersion: String = "",
    val gradleVersion: String = "",
    val hasKotlinDsl: Boolean = false,
    val tasks: List<GradleTask> = emptyList()
)

object GradleManager {
    const val KEY_LAST_PROJECT = "ide_gradle_last_project"
    const val KEY_CUSTOM_TASKS = "ide_gradle_custom_tasks"

    val commonTasks = listOf(
        GradleTask("assembleDebug", "构建 Debug APK", "build"),
        GradleTask("assembleRelease", "构建 Release APK", "build"),
        GradleTask("clean", "清理构建", "build"),
        GradleTask("build", "完整构建", "build"),
        GradleTask("test", "运行单元测试", "verification"),
        GradleTask("connectedAndroidTest", "连接设备测试", "verification"),
        GradleTask("dependencies", "查看依赖树", "help"),
        GradleTask("projects", "列出子项目", "help"),
        GradleTask("tasks", "列出所有任务", "help"),
        GradleTask("wrapper", "更新 Wrapper", "help")
    )

    suspend fun detectProject(projectPath: String): GradleProjectInfo = withContext(Dispatchers.IO) {
        if (projectPath.isBlank()) return@withContext GradleProjectInfo(projectPath, false)
        val wrapperFile = File(projectPath, "gradlew")
        val hasWrapper = wrapperFile.exists()
        var wrapperVersion = ""
        try {
            val props = File(projectPath, "gradle/wrapper/gradle-wrapper.properties")
            if (props.exists()) {
                val txt = props.readText()
                Regex("distributionUrl.*gradle-(.*)-bin").find(txt)?.let { wrapperVersion = it.groupValues[1] }
            }
        } catch (_: Exception) {}
        val hasKotlinDsl = File(projectPath, "build.gradle.kts").exists() || File(projectPath, "settings.gradle.kts").exists()
        var gradleVersion = ""
        if (LinuxEnvironment.isReady()) {
            try {
                val r = LinuxEnvironment.runInEnv("gradle --version 2>&1 | grep Gradle | awk '{print \$2}'")
                gradleVersion = r.stdout.trim().take(20)
            } catch (_: Exception) {}
        }
        GradleProjectInfo(projectPath, hasWrapper, wrapperVersion, gradleVersion, hasKotlinDsl, commonTasks)
    }

    suspend fun runTask(projectPath: String, task: String, extraArgs: String = "", jdkVersion: String? = null, onLog: (String)->Unit = {}): Int = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) {
            onLog("[错误] Linux 环境未就绪")
            return@withContext 1
        }
        if (projectPath.isBlank()) {
            onLog("[错误] 未选择项目目录")
            return@withContext 1
        }
        // 自定义任务做基础合法性校验（允许 "clean assembleDebug --info" 多任务空格分隔，但拒绝 ; | && ` $ 等注入元字符）
        val combined = "$task $extraArgs"
        if (combined.contains(Regex("[;|&`$\\n\\r]"))) {
            onLog("[错误] 任务名包含非法字符 ; | & ` \$")
            return@withContext 1
        }
        val useWrapper = File(projectPath, "gradlew").exists()
        val baseCmd = if (useWrapper) "./gradlew" else "gradle"
        val envPrefix = if (jdkVersion != null && jdkVersion in listOf("11","17")) {
            val home = if (jdkVersion=="11") com.xincode.app.JdkManager.JDK11_HOME else com.xincode.app.JdkManager.JDK17_HOME
            "export JAVA_HOME=$home; export PATH=\$JAVA_HOME/bin:\$PATH; "
        } else ""
        val fullCmd = "cd ${shellQuote(projectPath)} && ${envPrefix}$baseCmd $task $extraArgs 2>&1"
        val r = LinuxEnvironment.runInEnvStreaming(fullCmd, scope = "build", onLine = onLog)
        r.exitCode
    }

    suspend fun getLastProject(db: AppDatabase): String = withContext(Dispatchers.IO) { db.settingDao().get(KEY_LAST_PROJECT) ?: "" }
    suspend fun setLastProject(db: AppDatabase, path: String) { withContext(Dispatchers.IO) { db.settingDao().put(KEY_LAST_PROJECT, path) } }

    suspend fun getCustomTasks(db: AppDatabase): List<String> = withContext(Dispatchers.IO) {
        val raw = db.settingDao().get(KEY_CUSTOM_TASKS) ?: return@withContext emptyList()
        try { JSONObject("{\"a\":$raw}").getJSONArray("a").let { arr -> (0 until arr.length()).map { arr.getString(it) } } } catch (_: Exception) { emptyList() }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
