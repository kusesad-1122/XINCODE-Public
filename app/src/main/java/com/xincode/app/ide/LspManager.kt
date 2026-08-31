package com.xincode.app.ide

import com.xincode.app.LinuxEnvironment
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LspServer(
    val id: String,
    val language: String,
    val name: String,
    val description: String,
    val installCmd: String,
    val detectCmd: String,
    val runCmd: String,
    val port: Int = 0
)

object LspManager {
    const val KEY_ENABLED = "ide_lsp_enabled_json"

    val servers = listOf(
        LspServer(
            id = "java",
            language = "Java",
            name = "Eclipse JDT LS",
            description = "Java 语言服务器 · 补全/诊断/跳转",
            // 仅当真实产物存在才写 .installed，避免下载失败仍显示已安装
            installCmd = "apt-get install -y openjdk-17-jdk-headless wget && mkdir -p /opt/lsp/jdtls && cd /tmp && wget -qO jdtls.tar.gz https://download.eclipse.org/jdtls/milestones/1.32.0/jdt-language-server-1.32.0-20240307-1429.tar.gz && tar -xzf jdtls.tar.gz -C /opt/lsp/jdtls && test -f /opt/lsp/jdtls/plugins/org.eclipse.jdt.ls.core_*.jar && echo 'installed' > /opt/lsp/jdtls/.installed",
            detectCmd = "test -f /opt/lsp/jdtls/plugins/org.eclipse.jdt.ls.core_*.jar",
            runCmd = "/opt/lsp/jdtls/bin/jdtls"
        ),
        LspServer(
            id = "kotlin",
            language = "Kotlin",
            name = "Kotlin LSP",
            description = "Kotlin 语言服务器 · 官方 Kotlin Language Server",
            installCmd = "apt-get install -y kotlin 2>/dev/null; npm install -g kotlin-language-server && command -v kotlin-language-server >/dev/null 2>&1 && mkdir -p /opt/lsp/kotlin && echo 'kotlin-lsp' > /opt/lsp/kotlin/.installed || (echo 'kotlin lsp install failed: need node/npm'; exit 1)",
            detectCmd = "command -v kotlin-language-server >/dev/null 2>&1",
            runCmd = "kotlin-language-server"
        ),
        LspServer(
            id = "xml",
            language = "XML",
            name = "LemMinx",
            description = "XML 语言服务器 · Android 布局/资源校验",
            installCmd = "apt-get install -y wget openjdk-17-jdk-headless && mkdir -p /opt/lsp/lemminx && cd /tmp && wget -qO lemminx.jar https://repo.maven.apache.org/maven2/org/eclipse/lemminx/org.eclipse.lemminx/0.26.0/org.eclipse.lemminx-0.26.0-uber.jar && test -s lemminx.jar && cp lemminx.jar /opt/lsp/lemminx/ && echo 'installed' > /opt/lsp/lemminx/.installed",
            detectCmd = "test -f /opt/lsp/lemminx/lemminx.jar",
            runCmd = "java -jar /opt/lsp/lemminx/lemminx.jar"
        )
    )

    suspend fun isInstalled(server: LspServer): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext false
        try { LinuxEnvironment.runInEnv(server.detectCmd).exitCode == 0 } catch (_: Exception) { false }
    }

    suspend fun install(server: LspServer, onLog: (String)->Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady()) return@withContext false
        val r = LinuxEnvironment.runInEnvStreaming(server.installCmd, onLine = onLog)
        r.exitCode == 0
    }

    suspend fun getEnabled(db: AppDatabase): Set<String> = withContext(Dispatchers.IO) {
        val raw = db.settingDao().get(KEY_ENABLED) ?: return@withContext emptySet()
        try {
            val jo = JSONObject(raw)
            jo.keys().asSequence().filter { jo.optBoolean(it, false) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    suspend fun setEnabled(db: AppDatabase, id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cur = getEnabled(db).toMutableSet()
        if (enabled) cur.add(id) else cur.remove(id)
        val jo = JSONObject()
        cur.forEach { jo.put(it, true) }
        db.settingDao().put(KEY_ENABLED, jo.toString())
    }

    // 简单诊断：调用 javac / kotlinc / xmllint 做语法检查，返回错误行
    suspend fun diagnose(filePath: String, language: String): List<String> = withContext(Dispatchers.IO) {
        if (!LinuxEnvironment.isReady() || filePath.isBlank()) return@withContext emptyList()
        val cmd = when (language.lowercase()) {
            "java" -> "javac -Xlint -cp . -d /tmp ${shellQuote(filePath)} 2>&1 | head -n 40"
            "kotlin" -> "kotlinc ${shellQuote(filePath)} -d /tmp 2>&1 | head -n 40"
            "xml" -> "xmllint --noout ${shellQuote(filePath)} 2>&1 | head -n 40"
            else -> return@withContext emptyList()
        }
        try {
            val r = LinuxEnvironment.runInEnv(cmd)
            r.stdout.lines().filter { it.isNotBlank() }.take(30)
        } catch (_: Exception) { emptyList() }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
