package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/** 单项安装状态。 */
private enum class InstallState { IDLE, INSTALLING, OK, FAIL }

/**
 * 「环境配置」页:仿参考设计,分类列出可安装的开发环境/工具,支持全选/单选/已安装检测/
 * 收起展开/开始配置(经 root shell 自适应包管理器并行? 顺序安装)。
 */
@Composable
fun EnvConfigScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onNavigateBuild: (String) -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val categories = remember { EnvCatalog.categories }
    // toolId -> 已安装 / 选中 / 安装状态;category.title -> 展开
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { EnvCatalog.allTools.forEach { put(it.id, true) } } }
    val installState = remember { mutableStateMapOf<String, InstallState>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { categories.forEach { put(it.title, true) } } }
    var running by remember { mutableStateOf(false) }
    // L1 修复:初始 false —— 未部署/未就绪时不显示"检测中…"(仅真正开始检测时才置 true)。
    var detecting by remember { mutableStateOf(false) }

    // 环境就绪后(或进入时已就绪)检测已安装状态。
    LaunchedEffect(LinuxEnvironment.state) {
        if (LinuxEnvironment.isReady()) {
            detecting = true
            withContext(Dispatchers.IO) {
                EnvCatalog.allTools.forEach { t -> installed[t.id] = EnvSetupManager.isInstalled(t) }
            }
            detecting = false
        }
    }

    fun catAllSelected(cat: EnvCategory) = cat.tools.all { selected[it.id] == true }
    fun toggleCat(cat: EnvCategory) {
        val newVal = !catAllSelected(cat)
        cat.tools.forEach { selected[it.id] = newVal }
    }

    fun deployEnv() {
        // M2:部署中或安装中都不再重复部署(bootstrap 内部也有原子互斥兜底)。
        if (running || LinuxEnvironment.state == LinuxEnvironment.State.SETTING_UP) return
        // 已就绪时点的是「重新部署」→ 强制重装;否则首次部署(已存在则自动跳过下载)。
        val force = LinuxEnvironment.state == LinuxEnvironment.State.READY
        // 关键修复:部署跑在【应用级 GlobalScope】,不用 rememberCoroutineScope ——
        // 否则一点「查看终端」/页面重组就把 scope 取消,解包中断 → "The coroutine scope left the composition" 判失败。
        val appCtx = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) { LinuxEnvironment.bootstrap(appCtx, force = force) }
    }

    fun startInstall() {
        if (running || !LinuxEnvironment.isReady()) return
        running = true
        scope.launch {
            val todo = EnvCatalog.allTools.filter { selected[it.id] == true && installed[it.id] != true }
            for (t in todo) {
                installState[t.id] = InstallState.INSTALLING
                val (ok, _) = withContext(Dispatchers.IO) { EnvSetupManager.install(t) }
                // 安装后复检真实状态。
                val nowInstalled = withContext(Dispatchers.IO) { EnvSetupManager.isInstalled(t) }
                installed[t.id] = nowInstalled
                installState[t.id] = if (ok || nowInstalled) InstallState.OK else InstallState.FAIL
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "环境配置",
            subtitle = "Ubuntu、语言运行时与开发工具",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (detecting) XinHeaderAction(label = "检测中", enabled = false, onClick = {})
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "__env_status__") { EnvStatusCard(xc, onDeploy = { deployEnv() }, onOpenTerminal = onOpenTerminal) }
            item(key = "__build_env__") {
                BuildEnvCard(xc, onNavigate = onNavigateBuild)
            }
            items(categories, key = { it.title }) { cat ->
                CategoryCard(
                    cat = cat, xc = xc,
                    isExpanded = expanded[cat.title] == true,
                    allSelected = catAllSelected(cat),
                    installed = installed, selected = selected, installState = installState,
                    onToggleExpand = { expanded[cat.title] = expanded[cat.title] != true },
                    onToggleAll = { toggleCat(cat) },
                    onToggleTool = { id -> selected[id] = selected[id] != true }
                )
            }
        }

        // 底部按钮
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(xc.sub.copy(alpha = 0.25f))
                    .clickable(enabled = !running, indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() },
                contentAlignment = Alignment.Center
            ) { Text("跳过", fontSize = 15.sp, fontFamily = Mono, color = xc.ink) }
            val canStart = !running && LinuxEnvironment.isReady()
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(if (canStart) xc.green else xc.green.copy(alpha = 0.5f))
                    .clickable(enabled = canStart, indication = null, interactionSource = remember { MutableInteractionSource() }) { startInstall() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        running -> "配置中…"
                        !LinuxEnvironment.isReady() -> "先部署环境"
                        else -> "开始配置"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color.White
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    cat: EnvCategory,
    xc: XinColors,
    isExpanded: Boolean,
    allSelected: Boolean,
    installed: Map<String, Boolean>,
    selected: Map<String, Boolean>,
    installState: Map<String, InstallState>,
    onToggleExpand: () -> Unit,
    onToggleAll: () -> Unit,
    onToggleTool: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(xc.bgElevated).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = xc.ink, fontFamily = Mono)
                }
                if (cat.required) Text("(必须)", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E),
                    modifier = Modifier.padding(top = 2.dp))
                Text(cat.subtitle, fontSize = 12.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
            }
            CheckBox(checked = allSelected, color = xc.green, onClick = onToggleAll)
            Spacer(Modifier.width(8.dp))
            Text("全选", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
            Spacer(Modifier.width(8.dp))
            Text(if (isExpanded) "︿" else "﹀", fontSize = 14.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleExpand() })
        }

        if (isExpanded) {
            Spacer(Modifier.height(12.dp))
            cat.tools.forEach { tool ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    CheckBox(checked = selected[tool.id] == true, color = xc.green, onClick = { onToggleTool(tool.id) })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = xc.ink, fontFamily = Mono)
                            Spacer(Modifier.width(8.dp))
                            StatusTag(tool.id, installed, installState, xc)
                        }
                        Text(tool.desc, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(id: String, installed: Map<String, Boolean>, installState: Map<String, InstallState>, xc: XinColors) {
    val st = installState[id] ?: InstallState.IDLE
    when (st) {
        InstallState.INSTALLING -> Text("(安装中…)", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E))
        InstallState.OK -> Text("(已安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
        InstallState.FAIL -> Text("(失败)", fontSize = 11.sp, fontFamily = Mono, color = xc.red)
        InstallState.IDLE -> if (installed[id] == true)
            Text("(已安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
        else
            Text("(未安装)", fontSize = 11.sp, fontFamily = Mono, color = xc.faint)
    }
}

/** 顶部:内置 Linux 环境状态 + 一键部署。 */
@Composable
private fun EnvStatusCard(xc: XinColors, onDeploy: () -> Unit, onOpenTerminal: () -> Unit) {
    val st = LinuxEnvironment.state
    val (label, color) = when (st) {
        LinuxEnvironment.State.READY -> "Linux 环境已就绪" to xc.green
        LinuxEnvironment.State.SETTING_UP -> "正在部署环境…" to Color(0xFFF2C14E)
        LinuxEnvironment.State.ERROR -> "环境部署失败" to xc.red
        LinuxEnvironment.State.NOT_SETUP -> "未部署 Linux 环境" to xc.faint
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(xc.bgElevated).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
            // 查看终端(可视化部署/安装/AI 操作)
            Text("查看终端 ›", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onOpenTerminal() }
                    .padding(end = 12.dp))
            if (st != LinuxEnvironment.State.SETTING_UP) {
                // 入口形状:一个 pill 按钮。
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(xc.green.copy(alpha = 0.15f))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDeploy() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text((if (st == LinuxEnvironment.State.READY) "重新部署" else "部署环境") + " ›",
                        fontSize = 12.sp, fontFamily = Mono, color = xc.green)
                }
            } else {
                Text("下载/部署中…", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFFF2C14E))
            }
        }
        // 部署进度日志(仅部署中/失败时显示)
        if (st == LinuxEnvironment.State.SETTING_UP || (st == LinuxEnvironment.State.ERROR && LinuxEnvironment.setupLog.isNotBlank())) {
            Spacer(Modifier.height(8.dp))
            Text(LinuxEnvironment.setupLog.trim().takeLast(400), fontSize = 10.sp, fontFamily = Mono,
                color = xc.faint, lineHeight = 14.sp)
        }
    }
}

/** 1.22 融合卡：构建与环境变量 — Gradle/JDK/SDK/环境变量 统一入口，按来源回退 */
@Composable
private fun BuildEnvCard(xc: XinColors, onNavigate: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Text("构建与环境变量", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = xc.ink)
        Text("Gradle / JDK / SDK / 环境变量 统一在此跳转，IDE 仅保留代码与设计能力", fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        val items = listOf(
            Triple("gradle", "Gradle", "G"),
            Triple("jdk", "JDK", "J"),
            Triple("sdk", "SDK/NDK", "S"),
            Triple("envvar", "环境变量", "E")
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (id, title, icon) ->
                        Box(
                            Modifier.weight(1f).height(68.dp).clip(RoundedCornerShape(12.dp))
                                .background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(12.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNavigate(id) }
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(xc.green.copy(0.15f)), contentAlignment = Alignment.Center) {
                                        Text(icon, fontSize = 11.sp, fontFamily = Mono, color = xc.green)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(title, fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when (id) {
                                        "gradle" -> "Wrapper/任务/依赖"
                                        "jdk" -> "OpenJDK 11/17"
                                        "sdk" -> "平台/构建工具/NDK"
                                        else -> "构建与终端注入"
                                    }, fontSize = 10.sp, fontFamily = Mono, color = xc.sub, maxLines = 1
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("普通用户无 Root 仍可使用 Git/文件等，仅构建类能力需环境", fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 12.sp)
    }
}

/** 绿底白勾 / 空框 复选框(仿参考设计)。 */
@Composable
private fun CheckBox(checked: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .then(if (checked) Modifier.background(color) else Modifier.border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
