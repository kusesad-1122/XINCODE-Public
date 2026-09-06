package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// --- palette ---
private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val BgElevated: Color @Composable get() = LocalXinColors.current.bgElevated
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/** 插件官方在线图标加载器:内存 → 磁盘 → 网络,三级缓存;失败回退本地图标。 */
object PluginIconLoader {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun load(context: Context, url: String): Bitmap? {
        mem[url]?.let { return it }
        val file = java.io.File(
            java.io.File(context.filesDir, "plugin_icons").apply { mkdirs() },
            Integer.toHexString(url.hashCode()) + ".png"
        )
        if (file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                mem[url] = it
                return it
            }
        }
        return try {
            NetGuard.validate(url) // 图标同为出站请求:仅 http(s) 公网
            val req = okhttp3.Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size > 3 * 1024 * 1024) return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                file.writeBytes(bytes)
                mem[url] = bmp
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** 官方在线图标(带本地回退):有 URL 优先网络加载官方图标,失败再落回内置图形。 */
@Composable
private fun PluginIcon(
    url: String?,
    brandRes: Int?,
    icon: ImageVector,
    size: Dp
) {
    val context = LocalContext.current
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (!url.isNullOrBlank()) {
            bmp = withContext(Dispatchers.IO) { PluginIconLoader.load(context, url) }
        }
    }
    when {
        bmp != null -> Image(
            bitmap = bmp!!.asImageBitmap(), null,
            modifier = Modifier.size(size)
        )
        brandRes != null -> Icon(painterResource(brandRes), null, Modifier.size(size), tint = Ink)
        else -> Icon(icon, null, Modifier.size(size), tint = Sub)
    }
}

/**
 * 插件市场:连接器(设备流授权)/ 远程 MCP / 技能包 / 在线 OpenAPI 插件。
 * 在线部分的市场目录从远程 registry 实时拉取(新增插件无需发版),
 * 官方图标在线加载,点卡片可查看该插件带给 Agent 的全部功能。
 */
@Composable
fun PluginStoreScreen(
    store: PluginStoreManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var installed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var onlinePlugins by remember { mutableStateOf<List<PluginDescriptor>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var authPlugin by remember { mutableStateOf<PluginDescriptor?>(null) }
    var pendingAfterAuth by remember { mutableStateOf<PluginDescriptor?>(null) }
    var keyPromptPlugin by remember { mutableStateOf<PluginDescriptor?>(null) }
    var confirmUninstall by remember { mutableStateOf<PluginDescriptor?>(null) }
    var detailPlugin by remember { mutableStateOf<PluginDescriptor?>(null) }
    var detailCaps by remember { mutableStateOf<List<PluginCapability>?>(null) }

    fun refresh() {
        scope.launch {
            installed = store.installedStates()
            loaded = true
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        onlinePlugins = store.refreshRemoteCatalog()
        refresh() // 在线插件安装状态依赖远程目录,拉到后再刷一次
    }

    // 协程里拼的状态文案:格式串先按当前语言取好,协程内只做 format
    val fmtConnecting = t("连接中...")
    val fmtInstallOk = t("安装成功: %s(发现 %s 个工具)")
    val fmtInstallFail = t("安装失败: %s")
    val fmtSkillOk = t("已安装: %s")
    val fmtOnlineOk = t("已安装: %s(%s 个工具已注入 Agent)")
    val fmtUninstalled = t("已卸载: %s")
    val fmtAuthSaved = t("GitHub 授权已保存")

    fun setStatus(msg: String, err: Boolean) {
        statusMessage = msg
        isError = err
    }

    fun doInstallMcp(p: PluginDescriptor, url: String, authHeader: String) {
        if (busyId != null) return
        busyId = p.id
        setStatus(fmtConnecting, false)
        scope.launch {
            when (val r = store.installMcp(p, url, authHeader)) {
                is McpConnectResult.Success -> setStatus(fmtInstallOk.format(p.name, r.toolCount.toString()), false)
                is McpConnectResult.Error -> setStatus(fmtInstallFail.format(r.message), true)
            }
            busyId = null
            refresh()
        }
    }

    fun doInstallOnline(p: PluginDescriptor, key: String) {
        if (busyId != null) return
        busyId = p.id
        setStatus(fmtConnecting, false)
        scope.launch {
            when (val r = store.installOnline(p, key)) {
                is McpConnectResult.Success -> setStatus(fmtOnlineOk.format(p.name, r.toolCount.toString()), false)
                is McpConnectResult.Error -> setStatus(fmtInstallFail.format(r.message), true)
            }
            busyId = null
            refresh()
        }
    }

    fun onInstallClicked(p: PluginDescriptor) {
        if (busyId != null) return
        when (p.kind) {
            PluginKind.CONNECTOR -> { pendingAfterAuth = null; authPlugin = p }
            PluginKind.MCP -> when {
                p.requiresAuth -> scope.launch {
                    val tok = store.gitToken()
                    if (tok.isBlank()) { pendingAfterAuth = p; authPlugin = p }
                    else doInstallMcp(p, p.defaultUrl, "Bearer ${tok.trim()}")
                }
                else -> doInstallMcp(p, p.defaultUrl, "")
            }
            PluginKind.ONLINE -> when {
                p.requiresAuth -> keyPromptPlugin = p
                else -> doInstallOnline(p, "")
            }
            PluginKind.SKILL -> {
                busyId = p.id
                scope.launch {
                    val ok = store.installSkill(p)
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
                PluginKind.CONNECTOR -> store.clearGitToken()
                PluginKind.MCP -> store.uninstallMcp(p)
                PluginKind.SKILL -> store.uninstallSkill(p)
                PluginKind.ONLINE -> store.uninstallOnline(p)
            }
            setStatus(fmtUninstalled.format(p.name), false)
            busyId = null
            refresh()
        }
    }

    // —— 四页签:连接器 / MCP / 在线插件 / 技能包，各占一页 ——
    var tab by remember { mutableStateOf(0) }
    // —— 在线插件页的筛选：搜索 + 分类 + 授权 + 排序 ——
    var query by remember { mutableStateOf("") }
    var catFilter by remember { mutableStateOf("") } // 空 = 全部
    var authFilter by remember { mutableStateOf(0) } // 0全部 1免登 2需Key
    var sortMode by remember { mutableStateOf(0) } // 0名称 1分类 2星数 3下载(后两者有数才出现)

    val tabs = listOf(PluginKind.CONNECTOR, PluginKind.MCP, PluginKind.ONLINE, PluginKind.SKILL)
    val tabItems: List<PluginDescriptor> = when (tabs[tab]) {
        PluginKind.ONLINE -> onlinePlugins
        else -> PluginCatalog.all.filter { it.kind == tabs[tab] }
    }

    // 在线页过滤管线：搜索(名/简介/分类) → 分类 → 授权 → 排序。
    // 星数/下载/收藏/发布时间注册表给了才参与（缺省 0 = 未知，不展示不排序）。
    val categories = remember(onlinePlugins) {
        onlinePlugins.map { it.category.ifBlank { "未分类" } }.distinct().sorted()
    }
    val hasStars = onlinePlugins.any { it.stars > 0 }
    val hasDownloads = onlinePlugins.any { it.downloads > 0 }
    val visibleItems = if (tabs[tab] != PluginKind.ONLINE) {
        tabItems
    } else {
        var list = tabItems
        val q = query.trim()
        if (q.isNotEmpty()) {
            list = list.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.summary.contains(q, ignoreCase = true) ||
                    it.category.contains(q, ignoreCase = true)
            }
        }
        if (catFilter.isNotEmpty()) list = list.filter { it.category == catFilter }
        if (authFilter == 1) list = list.filter { !it.requiresAuth }
        if (authFilter == 2) list = list.filter { it.requiresAuth }
        list = when {
            sortMode == 2 && hasStars -> list.sortedByDescending { it.stars }
            sortMode == 3 && hasDownloads -> list.sortedByDescending { it.downloads }
            sortMode == 1 -> list.sortedWith(compareBy({ it.category }, { it.name }))
            else -> list.sortedBy { it.name }
        }
        list
    }

    @Composable
    fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
        val xc = LocalXinColors.current
        Text(
            label, fontSize = 11.sp, fontFamily = JetBrainsMono,
            color = if (selected) xc.ink else xc.sub,
            modifier = Modifier
                .background(
                    if (selected) xc.activeBg else xc.bgElevated,
                    RoundedCornerShape(12.dp)
                )
                .border(1.dp, if (selected) xc.ink else xc.border, RoundedCornerShape(12.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
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

        // 页签：一项一页。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, kind ->
                val count = when (kind) {
                    PluginKind.ONLINE -> onlinePlugins.size
                    else -> PluginCatalog.all.count { it.kind == kind }
                }
                Box(Modifier.weight(1f)) {
                    FilterPill(
                        label = "${t(kind.label)} $count",
                        selected = tab == i,
                        onClick = { tab = i }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (loaded) {
            val installedCount = installed.count { it.value }
            Text(
                tx("本页 %s 个 · 共已安装 %s 个", visibleItems.size.toString(), installedCount.toString()),
                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
            if (tabs[tab] == PluginKind.ONLINE) {
                store.remoteError?.let {
                    Text(it, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }
            }
        }

        // 在线插件页：搜索框 + 分类 + 授权 + 排序。
        if (tabs[tab] == PluginKind.ONLINE) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(t("搜索名称 / 功能 / 分类…"), fontSize = 11.sp, fontFamily = JetBrainsMono) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterPill(t("全部"), catFilter.isEmpty()) { catFilter = "" }
                categories.forEach { c ->
                    FilterPill(c, catFilter == c) {
                        catFilter = if (catFilter == c) "" else c
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(t("全部授权"), authFilter == 0) { authFilter = 0 }
                FilterPill(t("免登"), authFilter == 1) { authFilter = 1 }
                FilterPill(t("需 Key"), authFilter == 2) { authFilter = 2 }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(t("按名称"), sortMode == 0) { sortMode = 0 }
                FilterPill(t("按分类"), sortMode == 1) { sortMode = 1 }
                if (hasStars) FilterPill(t("按星数"), sortMode == 2) { sortMode = 2 }
                if (hasDownloads) FilterPill(t("按下载"), sortMode == 3) { sortMode = 3 }
            }
            Spacer(Modifier.height(4.dp))
        }

        visibleItems.forEach { p ->
            PluginCard(
                p = p,
                // 在线页徽标：分类 · 授权 · 有数才显示星/下载。
                badge = if (tabs[tab] == PluginKind.ONLINE) onlineBadge(p) else null,
                installedState = installed[p.id] == true,
                disabled = !loaded || busyId != null,
                onOpenDetail = { detailPlugin = p },
                onInstall = { onInstallClicked(p) },
                onUninstall = { confirmUninstall = p }
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    // —— 授权跳转弹窗(GitHub 设备流):申请设备码 → 自动跳浏览器 → 轮询回传 ——
    authPlugin?.let { ap ->
        PluginAuthDialog(
            plugin = ap,
            database = store.database,
            onDismiss = { authPlugin = null; pendingAfterAuth = null },
            onAuthorized = { tok ->
                authPlugin = null
                scope.launch {
                    store.saveGitToken(tok)
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

    // —— 插件详情:这个插件装好后,Agent 能用它做什么 ——
    detailPlugin?.let { p ->
        LaunchedEffect(p.id) {
            detailCaps = null
            detailCaps = store.capabilitiesFor(p)
        }
        ModalBottomSheetGlass(
            onDismiss = { detailPlugin = null },
            title = p.name,
            subtitle = p.summary,
            statusText = if (installed[p.id] == true) t("● 已安装") else t("○ 未安装"),
            statusOk = installed[p.id] == true,
            capabilities = detailCaps,
            actionLabel = if (installed[p.id] == true) t("卸载") else t("安装"),
            actionDestructive = installed[p.id] == true,
            actionEnabled = busyId == null,
            onAction = {
                detailPlugin = null
                if (installed[p.id] == true) confirmUninstall = p else onInstallClicked(p)
            }
        )
    }

    // —— 在线插件 API Key 输入(Keystore 加密落库) ——
    keyPromptPlugin?.let { p ->
        OnlineKeyDialog(
            pluginName = p.name,
            onDismiss = { keyPromptPlugin = null },
            onConfirm = { key ->
                keyPromptPlugin = null
                doInstallOnline(p, key)
            }
        )
    }
}

/** 在线徽标：分类 · 授权 · 有数才显示星/下载/收藏（没数的注册表不编造）。 */
private fun onlineBadge(p: PluginDescriptor): String {
    val parts = mutableListOf(p.category.ifBlank { "未分类" })
    parts.add(if (p.requiresAuth) "需 Key" else "免登")
    if (p.stars > 0) parts.add("★${formatCount(p.stars)}")
    if (p.downloads > 0) parts.add("⬇${formatCount(p.downloads)}")
    if (p.favorites > 0) parts.add("♥${formatCount(p.favorites)}")
    return parts.joinToString(" · ")
}

private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> "${n / 100_000_000}亿+"
    n >= 10_000 -> "${n / 10_000}万+"
    n >= 1_000 -> "${n / 1_000}k+"
    else -> n.toString()
}

@Composable
private fun PluginCard(
    p: PluginDescriptor,
    installedState: Boolean,
    disabled: Boolean,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    /** 在线页徽标行（分类 · 授权 · 星/下载），其他页传 null 不占位。 */
    badge: String? = null
) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(xc.bgElevated, RoundedCornerShape(18.dp))
            .border(1.dp, xc.border, RoundedCornerShape(18.dp))
            .clickable(indication = null,
                interactionSource = remember { MutableInteractionSource() }) { onOpenDetail() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(xc.activeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PluginIcon(url = p.iconUrl, brandRes = p.brandRes, icon = p.icon, size = 20.dp)
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
                lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (badge != null) {
                Text(
                    badge, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                t("查看功能 ›"),
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.green,
                modifier = Modifier.padding(top = 3.dp)
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

/** 复用玻璃拟态底部抽屉样式的插件详情:功能清单 + 安装/卸载操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalBottomSheetGlass(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String,
    statusText: String,
    statusOk: Boolean,
    capabilities: List<PluginCapability>?,
    actionLabel: String,
    actionDestructive: Boolean,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    val xc = LocalXinColors.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = xc.bgElevated,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    color = xc.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText, fontSize = 11.sp, fontFamily = JetBrainsMono,
                    color = if (statusOk) xc.green else xc.faint
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 12.sp, fontFamily = XinUiFont, color = xc.sub, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp))
            Text(t("安装后,以下能力会注入 Agent:"), fontSize = 11.sp, fontFamily = XinUiFont, color = xc.faint)
            Spacer(Modifier.height(8.dp))

            when {
                capabilities == null -> Text(
                    t("加载中…"), fontSize = 12.sp, fontFamily = XinUiFont, color = xc.faint,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
                capabilities.isEmpty() -> Text(
                    t("该插件未声明具体工具。"), fontSize = 12.sp, fontFamily = XinUiFont,
                    color = xc.faint, modifier = Modifier.padding(vertical = 12.dp)
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    capabilities.forEach { cap ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(xc.bg)
                                .border(0.8.dp, xc.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(t(cap.name), fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cap.summary.isNotBlank()) {
                                Text(t(cap.summary), fontSize = 11.sp, fontFamily = XinUiFont, color = xc.sub,
                                    lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(
                        if (actionEnabled) (if (actionDestructive) xc.ink else xc.green) else xc.border
                    )
                    .clickable(enabled = actionEnabled, indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onAction() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    fontFamily = XinUiFont, color = Color.White
                )
            }
        }
    }
}

/** 在线插件 API Key 输入弹窗:Key 经 Keystore 加密存储,只在请求头里使用。 */
@Composable
private fun OnlineKeyDialog(
    pluginName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Bg).padding(16.dp)) {
            Text(tx("安装 %s", pluginName), fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                t("该插件需要 API Key。Key 经 Keystore 加密存储在本机,只在请求头中使用,不会进入对话内容。"),
                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub
            )
            Spacer(Modifier.height(12.dp))
            Text("API Key", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            TextField(
                value = key, onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("粘贴 Key", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
                )
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(t("取消"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onDismiss() })
                Spacer(Modifier.width(16.dp))
                Text(t("保存并安装"), fontSize = 12.sp, fontFamily = JetBrainsMono,
                    color = if (key.isNotBlank()) Green else Faint,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        if (key.isNotBlank()) onConfirm(key.trim())
                    })
            }
        }
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
