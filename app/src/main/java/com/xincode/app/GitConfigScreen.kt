package com.xincode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.McpServerEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Git 接入:两条路都备上——
 *  1) Git 授权(CLI):存 GitHub 用户名/邮箱/PAT,一键写进内置 Ubuntu 环境的 git 配置 + 凭证,
 *     之后 AI 用终端/env_exec 就能 clone/push 私有仓库。
 *  2) Git MCP:用同一个 token 一键添加 GitHub 官方 MCP 服务器(stdio:npx server-github),
 *     去 MCP 页连接即可用 GitHub API(仓库/PR/Issue)。
 */
@Composable
fun GitConfigScreen(database: AppDatabase, keystore: KeystoreProvider, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var user by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            user = database.settingDao().get("git_user_name") ?: ""
            email = database.settingDao().get("git_user_email") ?: ""
            val enc = database.settingDao().get("git_token_enc")
            if (!enc.isNullOrBlank()) token = try {
                keystore.decrypt(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP))
            } catch (_: Exception) { "" }
        }
    }

    fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    fun saveSettings() {
        scope.launch(Dispatchers.IO) {
            database.settingDao().put("git_user_name", user.trim())
            database.settingDao().put("git_user_email", email.trim())
            if (token.isNotBlank()) {
                val enc = android.util.Base64.encodeToString(keystore.encrypt(token.trim()), android.util.Base64.NO_WRAP)
                database.settingDao().put("git_token_enc", enc)
            }
        }
    }

    fun configureEnv() {
        if (busy) return
        if (!LinuxEnvironment.isReady()) { status = "请先到 设置→环境配置 部署 Linux 环境"; return }
        busy = true; status = "配置中…(可在终端页查看)"
        saveSettings()
        scope.launch {
            // 用 base64 传值,避开 shell 引号问题;在 Ubuntu 内 base64 -d 还原后用双引号展开。
            val cmd = "N=\$(echo ${b64(user.trim())}|base64 -d); E=\$(echo ${b64(email.trim())}|base64 -d); " +
                "U=\$(echo ${b64(user.trim())}|base64 -d); T=\$(echo ${b64(token.trim())}|base64 -d); " +
                "apt-get install -y git >/dev/null 2>&1; " +
                "git config --global user.name \"\$N\"; git config --global user.email \"\$E\"; " +
                "git config --global credential.helper store; " +
                "printf 'https://%s:%s@github.com\\n' \"\$U\" \"\$T\" > ~/.git-credentials && chmod 600 ~/.git-credentials && echo GIT_CONFIGURED"
            val r = withContext(Dispatchers.IO) {
                LinuxEnvironment.runInEnvStreaming(cmd) { line -> LinuxEnvironment.outputSink?.invoke(line) }
            }
            status = if (r.exitCode == 0) "已配置到环境 ✓ 现在 AI 可在终端 git clone/push 了" else "配置失败,退出码 ${r.exitCode}(看终端)"
            busy = false
        }
    }

    fun addGithubMcp() {
        if (token.isBlank()) { status = "请先填 Token 再添加 GitHub MCP"; return }
        saveSettings()
        scope.launch(Dispatchers.IO) {
            val env = org.json.JSONObject().put("GITHUB_PERSONAL_ACCESS_TOKEN", token.trim())
            database.mcpServerDao().upsert(
                McpServerEntity(
                    name = "github", url = "", transport = "stdio",
                    command = "npx",
                    argsJson = org.json.JSONArray(listOf("-y", "@modelcontextprotocol/server-github")).toString(),
                    envJson = env.toString()
                )
            )
            status = "已添加 GitHub MCP,去「设置→MCP 服务器」连接即可"
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("Git 接入", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
        }
        Text("填入 GitHub 用户名/邮箱/Personal Access Token,即可让 XINCODE 连接 Git(CLI 授权 + Git MCP 两条路都备)。",
            fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(horizontal = 16.dp))
        Text("点这里去 GitHub 创建 Token(勾选 repo 权限) ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
            modifier = Modifier.padding(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            })

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("GitHub 用户名", user, { user = it }, xc)
            Field("邮箱(git commit 用)", email, { email = it }, xc)
            Field("Personal Access Token", token, { token = it }, xc)
        }

        if (status.isNotBlank()) Text(status, fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.padding(16.dp))

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (busy) "配置中…" else "① 配置到 Linux 环境(git 授权)", xc.green, !busy) { configureEnv() }
            Btn("② 添加 GitHub MCP(用同一 Token)", xc.green.copy(alpha = 0.85f), true) { addGithubMcp() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, xc: XinColors) {
    Column {
        Text(label, fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
        TextField(
            value = value, onValueChange = onValue, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = Mono),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink
            )
        )
    }
}

@Composable
private fun Btn(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp)
            .background(if (enabled) color else color.copy(alpha = 0.4f), RoundedCornerShape(25.dp))
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color.White) }
}
