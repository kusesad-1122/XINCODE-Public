package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.launch

// --- palette ---
private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * 插件商店:安装/卸载连接器(OAuth)、远程 MCP 服务与内置技能包。
 * 安装需授权的插件时先弹「跳转授权」弹窗(GitHub 设备流),浏览器完成登录后自动回传。
 */
@Composable
fun PluginStoreScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    mcpManager: McpManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val manager = remember { PluginStoreManager(appContext, database, keystore, mcpManager) }

    var installed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var authPlugin by remember { mutableStateOf<PluginDescriptor?>(null) }
    var pendingAfterAuth by remember { mutableStateOf<PluginDescriptor?>(null) }
    var urlPromptPlugin by remember { mutableStateOf<PluginDescriptor?>(null) }
    var confirmUninstall by remember { mutableStateOf<PluginDescriptor?>(null) }

    fun refresh() {
        scope.launch {
            installed = manager.installedStates()
            loaded = true
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // 协程里拼的状态文案:格式串先按当前语言取好,协程内只做 format(McpServerScreen 同款)。
    val fmtConnecting = t("连接中...")
    val fmtInstallOk = t("安装成功: %s(发现 %s 个工具)")
    val fmtInstallFail = t("安装失败: %s")
    val fmtSkillOk = t("已安装: %s")
    val fmtUninstalled = t("已卸载: %s")
    val fmtAuthSaved = t("GitHub 授权已保存")
    val fmtBadUrl = t("地址需以 http(s):// 开头")

    fun setStatus(msg: String, err: Boolean) {
        statusMessage = msg
        isError = err
    }

    fun doInstallMcp(p: PluginDescriptor, url: String, authHeader: String) {
        if (busyId != null) return
        busyId = p.id
        setStatus(fmtConnecting, false)
        scope.launch {
            when (val r = manager.installMcp(p, url, authHeader)) {
                is McpConnectResult.Success -> setStatus(fmtInstallOk.format(p.name, r.toolCount.toString()), false)
                is McpConnectResult.Error -> setStatus(fmtInstallFail.format(r.message), true)
            }
            busyId = null
            refresh()
        }
    }

    fun onInstallClicked(p: PluginDescriptor) {
        if (busyId != null) return
        when (p.kind) {
            // 连接器:授权即安装(存 token)
            PluginKind.CONNECTOR -> { pendingAfterAuth = null; authPlugin = p }
            PluginKind.MCP -> when {
                // 需要鉴权但未登录:先走设备流授权,成功后接着装
                p.requiresAuth -> scope.launch {
                    val tok = manager.gitToken()
                    if (tok.isBlank()) { pendingAfterAuth = p; authPlugin = p }
                    else doInstallMcp(p, p.defaultUrl, "Bearer ${tok.trim()}")
                }
                // 地址由用户提供(如 Composio):弹输入弹窗
                p.defaultUrl.isBlank() -> urlPromptPlugin = p
                else -> doInstallMcp(p, p.defaultUrl, "")
            }
            PluginKind.SKILL -> {
                busyId = p.id
                scope.launch {
                    val ok = manager.installSkill(p)
                    setStatus(if (ok) fmtSkillOk.format(p.name) else fmtInstallFail.format(p.name), !ok)
                    busyId = null
                    refresh()
                }
            }
        }
    }

    fun doUninstall(p: PluginDescriptor) {
        if (busyId != null) return
        busyId = p.id
        scope.launch {
            when (p.kind) {
                PluginKind.CONNECTOR -> manager.clearGitToken()
                PluginKind.MCP -> manager.uninstallMcp(p)
                PluginKind.SKILL -> manager.uninstallSkill(p)
            }
            setStatus(fmtUninstalled.format(p.name), false)
            busyId = null
            refresh()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        XinPageHeader(title = t("插件"), subtitle = t("安装与卸载 XINCODE 插件"), onBack = onBack)
        Spacer(Modifier.height(12.dp))

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, fontSize = 11.sp, fontFamily = JetBrainsMono,
                color = if (isError) Red else Green,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        // 市场总览:全部插件可见、装了多少一目了然(加载完成才显示,避免闪「已安装 0 个」)
        if (loaded) {
            val totalCount = PluginCatalog.all.size
            val installedCount = installed.count { it.value }
            Text(
                tx("共 %s 个插件 · 已安装 %s 个", totalCount.toString(), installedCount.toString()),
                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        listOf(PluginKind.CONNECTOR, PluginKind.MCP, PluginKind.SKILL).forEach { kind ->
            val items = PluginCatalog.all.filter { it.kind == kind }
            Text(
                t(kind.label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp)
            )
            items.forEach { p ->
                PluginCard(
                    p = p,
                    installedState = installed[p.id] == true,
                    disabled = busyId != null || !loaded,
                    onInstall = { onInstallClicked(p) },
                    onUninstall = { confirmUninstall = p }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // —— 授权跳转弹窗(GitHub 设备流):申请设备码 → 自动跳浏览器 → 轮询回传 ——
    authPlugin?.let { ap ->
        PluginAuthDialog(
            plugin = ap,
            database = database,
            onDismiss = { authPlugin = null; pendingAfterAuth = null },
            onAuthorized = { tok ->
                authPlugin = null
                scope.launch {
                    manager.saveGitToken(tok)
                    setStatus(fmtAuthSaved, false)
                    val cont = pendingAfterAuth
                    pendingAfterAuth = null
                    if (cont != null && cont.kind == PluginKind.MCP) {
                        doInstallMcp(cont, cont.defaultUrl, "Bearer ${tok.trim()}")
                    }
                    refresh()
                }
            }
        )
    }

    // —— 需要用户自填 MCP 地址(Composio 等) ——
    urlPromptPlugin?.let { p ->
        PluginMcpUrlDialog(
            plugin = p,
            onDismiss = { urlPromptPlugin = null },
            onConfirm = { url, auth ->
                urlPromptPlugin = null
                doInstallMcp(p, url, auth)
            }
        )
    }

    // —— 卸载确认 ——
    confirmUninstall?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(t("卸载插件"), fontFamily = JetBrainsMono, color = Ink) },
            text = {
                Text(
                    tx("「%s」将从 XINCODE 移除,相关工具/授权不再对 AI 可用。", p.name),
                    fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUninstall = null; doUninstall(p) }) {
                    Text(t("卸载"), fontFamily = JetBrainsMono, color = Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) {
                    Text(t("取消"), fontFamily = JetBrainsMono, color = Sub)
                }
            },
            containerColor = Bg
        )
    }
}

@Composable
private fun PluginCard(
    p: PluginDescriptor,
    installedState: Boolean,
    disabled: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(xc.bgElevated, RoundedCornerShape(18.dp))
            .border(1.dp, xc.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(xc.activeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (p.brandRes != null) {
                // 官方品牌图标(GitHub octocat 等),比通用图标更有辨识度
                Icon(painterResource(p.brandRes), null, Modifier.size(20.dp), tint = xc.ink)
            } else {
                Icon(p.icon, null, Modifier.size(18.dp), tint = xc.sub)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontSize = 14.sp, fontFamily = JetBrainsMono, color = xc.ink)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (installedState) t("● 已安装") else t("○ 未安装"),
                    fontSize = 10.sp, fontFamily = JetBrainsMono,
                    color = if (installedState) xc.green else xc.faint
                )
            }
            Text(
                p.summary, fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub,
                lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        XinHeaderAction(
            label = if (installedState) t("卸载") else t("安装"),
            onClick = { if (installedState) onUninstall() else onInstall() },
            enabled = !disabled,
            destructive = installedState
        )
    }
}

/**
 * 授权跳转弹窗:GitHub OAuth 设备流。展示用户码 + 自动跳浏览器,轮询授权结果自动回传。
 */
@Composable
private fun PluginAuthDialog(
    plugin: PluginDescriptor,
    database: AppDatabase,
    onDismiss: () -> Unit,
    onAuthorized: (String) -> Unit
) {
    val ctx = LocalContext.current
    var userCode by remember { mutableStateOf("") }
    var verifyUri by remember { mutableStateOf("") }
    // 协程内不能调 t(),所有文案先在组合作用域取好
    val fmtRequesting = t("正在申请设备码…")
    val fmtSteps = t("① 浏览器打开授权页 → ② 输入用户码 → ③ 点授权(自动检测)")
    val fmtOk = t("授权成功 ✓")
    val fmtAuthFail = t("授权失败")
    val fmtReqFail = t("申请失败")
    var status by remember { mutableStateOf(fmtRequesting) }
    var done by remember { mutableStateOf(false) }

    fun openVerify() {
        if (verifyUri.isBlank()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verifyUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        val clientId = database.settingDao().get("github_oauth_client_id")
            ?.takeIf { it.isNotBlank() } ?: GithubAuth.DEFAULT_CLIENT_ID
        val dc = GithubAuth.requestDeviceCode(clientId).getOrElse {
            status = "$fmtReqFail: ${(it.message ?: "").take(120)}"
            return@LaunchedEffect
        }
        userCode = dc.userCode
        verifyUri = dc.verificationUri
        status = fmtSteps
        openVerify()
        GithubAuth.pollForToken(clientId, dc.deviceCode, dc.interval, dc.expiresIn) { tick -> status = tick }
            .onSuccess { tok -> done = true; status = fmtOk; onAuthorized(tok) }
            .onFailure { status = "$fmtAuthFail: ${(it.message ?: "").take(120)}" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Bg).padding(16.dp)) {
            Text(tx("授权 %s", plugin.name), fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                t("该插件需要授权你的账号。点「跳转授权」打开浏览器完成登录,授权结果自动回传,无需手动填 Token。"),
                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub
            )
            Spacer(Modifier.height(12.dp))
            if (userCode.isNotBlank()) {
                Text(t("在网页里输入用户码(点码可复制):"), fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
                Text(
                    userCode, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMono, color = Ink,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("code", userCode))
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 11.sp, fontFamily = JetBrainsMono, color = if (done) Green else Sub)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(t("取消"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onDismiss() })
                if (userCode.isNotBlank()) {
                    Spacer(Modifier.width(16.dp))
                    Text(t("跳转授权"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Green,
                        modifier = Modifier.clickable(indication = null,
                            interactionSource = remember { MutableInteractionSource() }) { openVerify() })
                }
            }
        }
    }
}

/** 自填 MCP 地址弹窗(Composio 等按用户账号生成的服务地址)。 */
@Composable
private fun PluginMcpUrlDialog(
    plugin: PluginDescriptor,
    onDismiss: () -> Unit,
    onConfirm: (url: String, authHeader: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val fmtHint = t("三步接入:① 打开控制台生成你的 MCP 地址 → ② 复制粘贴到下面 → ③ 点安装。如需鉴权可再填 Authorization 头(可选)。")
    val valid = url.trim().startsWith("http://") || url.trim().startsWith("https://")

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Bg).padding(16.dp)) {
            Text(tx("安装 %s", plugin.name), fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(fmtHint, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            if (plugin.consoleUrl.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(t("打开控制台获取 MCP 地址 ›"), fontSize = 11.sp, fontFamily = JetBrainsMono, color = Green,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(plugin.consoleUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                    })
            }
            Spacer(Modifier.height(12.dp))
            Text("MCP URL", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            TextField(
                value = url, onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(plugin.urlPlaceholder.ifBlank { "https://…" },
                        fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint)
                },
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(t("Auth Header (可选)"), fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            TextField(
                value = auth, onValueChange = { auth = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Bearer xxx", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
                )
            )
            if (url.isNotBlank() && !valid) {
                Spacer(Modifier.height(6.dp))
                Text(t("地址需以 http(s):// 开头"), fontSize = 10.sp, fontFamily = JetBrainsMono, color = Red)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(t("取消"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onDismiss() })
                Spacer(Modifier.width(16.dp))
                Text(t("安装"), fontSize = 12.sp, fontFamily = JetBrainsMono,
                    color = if (valid) Ink else Faint,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        if (valid) onConfirm(url.trim(), auth.trim())
                    })
            }
        }
    }
}
