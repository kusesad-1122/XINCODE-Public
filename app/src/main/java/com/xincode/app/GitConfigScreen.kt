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

private val Mono = XinUiFont

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
    // OAuth 设备流:client_id(用户注册的 OAuth App)+ 登录中显示的用户码/授权网址。
    var clientId by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var deviceUserCode by remember { mutableStateOf("") }
    var deviceVerifyUri by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            user = database.settingDao().get("git_user_name") ?: ""
            email = database.settingDao().get("git_user_email") ?: ""
            // 优先用用户自填(高级用法:自建 OAuth App);否则用 XINCODE 内置的公共 Client ID。
            clientId = database.settingDao().get("github_oauth_client_id")
                ?.takeIf { it.isNotBlank() } ?: GithubAuth.DEFAULT_CLIENT_ID
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
            database.settingDao().put("github_oauth_client_id", clientId.trim())
            if (token.isNotBlank()) {
                val enc = android.util.Base64.encodeToString(keystore.encrypt(token.trim()), android.util.Base64.NO_WRAP)
                database.settingDao().put("git_token_enc", enc)
            }
        }
    }

    /** OAuth 设备流登录:拿设备码 → 打开授权网址 → 轮询拿 token → 回填用户名。免手动建 PAT。 */
    fun loginOAuth() {
        if (loggingIn) return
        val cid = clientId.trim().ifBlank { GithubAuth.DEFAULT_CLIENT_ID }
        if (cid.isBlank()) { status = "本次构建未内置 OAuth Client ID,请在上方填入自建 OAuth App 的 Client ID(需勾选 Device Flow)"; return }
        loggingIn = true; deviceUserCode = ""; deviceVerifyUri = ""; status = "正在申请设备码…"
        scope.launch {
            val dc = GithubAuth.requestDeviceCode(cid).getOrElse {
                status = "申请失败:${it.message?.take(120)}"; loggingIn = false; return@launch
            }
            deviceUserCode = dc.userCode; deviceVerifyUri = dc.verificationUri
            status = "① 打开授权网页 → ② 输入下面的用户码 → ③ 点授权(自动检测)"
            // 顺手把授权网页打开,省得用户手输网址。
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            val tok = GithubAuth.pollForToken(cid, dc.deviceCode, dc.interval, dc.expiresIn) { tick -> status = "$tick(用户码 ${dc.userCode})" }
                .getOrElse { status = "登录失败:${it.message?.take(120)}"; loggingIn = false; deviceUserCode = ""; return@launch }
            token = tok
            val login = GithubAuth.fetchLogin(tok)
            if (!login.isNullOrBlank() && user.isBlank()) user = login
            saveSettings()
            status = "已登录 GitHub ✓${login?.let { "(@$it)" } ?: ""} — 现在可配置环境或添加远程 MCP"
            deviceUserCode = ""; loggingIn = false
        }
    }

    /** 添加 GitHub 官方【远程】MCP(HTTP,免 root/免 node):AI 直接用 GitHub API 管仓库/PR/Issue/文件。 */
    fun addRemoteGithubMcp() {
        if (token.isBlank()) { status = "请先登录 GitHub(或填 Token)再添加远程 MCP"; return }
        saveSettings()
        scope.launch(Dispatchers.IO) {
            database.mcpServerDao().upsert(
                McpServerEntity(
                    name = "github-remote",
                    url = "https://api.githubcopilot.com/mcp/",
                    authHeader = "Bearer ${token.trim()}",
                    transport = "http"
                )
            )
            status = "已添加 GitHub 远程 MCP(免 root),去「设置→MCP 服务器」连接即可"
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
        Text("点「登录 GitHub」用你的账户授权即可(无需手动建 Token)。登录后可:添加官方远程 MCP(免 root/免 node)让 AI 直接用 GitHub API 管仓库/PR/Issue/文件,或配置到 Linux 环境走终端 git。",
            fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(horizontal = 16.dp))

        // —— OAuth 登录 ——
        // 已内置公共 Client ID 时,用户无需填写任何东西,直接点登录(与 gh CLI / Claude Code 同做法)。
        // 仅当未内置(空)时,才要求自填——留给自建 OAuth App 的高级用户。
        if (GithubAuth.DEFAULT_CLIENT_ID.isBlank()) {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("OAuth App Client ID", clientId, { clientId = it }, xc)
            }
            Text("没有 Client ID?点这里注册一个 OAuth App(务必勾选 Enable Device Flow) ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.padding(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/developers")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                })
        }

        if (deviceUserCode.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                Text("在打开的网页里输入用户码(点码可复制):", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                Text(deviceUserCode, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("code", deviceUserCode))
                        status = "用户码已复制"
                    })
                if (deviceVerifyUri.isNotBlank())
                    Text("授权网址:$deviceVerifyUri(点击重开) ›", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deviceVerifyUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        })
            }
        }

        if (status.isNotBlank()) Text(status, fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.padding(16.dp))

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (loggingIn) "登录中…(等待网页授权)" else "登录 GitHub(OAuth,免建 Token)", xc.green, !loggingIn) { loginOAuth() }
            Btn("② 添加官方远程 MCP(免 root/免 node,推荐)", xc.green.copy(alpha = 0.85f), true) { addRemoteGithubMcp() }
            Btn(if (busy) "配置中…" else "① 配置到 Linux 环境(终端 git,需 root)", xc.green.copy(alpha = 0.75f), !busy) { configureEnv() }
            Btn("③ 添加本地 GitHub MCP(npx,需环境装 node)", xc.green.copy(alpha = 0.6f), true) { addGithubMcp() }
        }

        // —— 备用:手动 PAT ——
        Text("或手动填资料(用 Personal Access Token 代替 OAuth):", fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("GitHub 用户名", user, { user = it }, xc)
            Field("邮箱(git commit 用)", email, { email = it }, xc)
            Field("Personal Access Token", token, { token = it }, xc)
        }
        Text("手动建 Token(勾选 repo 权限) ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
            modifier = Modifier.padding(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            })
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
