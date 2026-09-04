package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.xincode.app.R
import com.xincode.app.privilege.PrivilegedExecutor
import com.xincode.app.privilege.ShizukuShell
import com.xincode.security.PermissionMode
import com.xincode.tools.RootDiagnosticResult
import kotlinx.coroutines.launch

// Palette now sourced from [LocalXinColors].
private val JetBrainsMono = XinUiFont

/**
 * Settings main screen — 6 sections, collapsible with icons (1.22).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSupplierConfig: () -> Unit,
    onNavigateToModelMarket: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToAuditLog: () -> Unit,
    onNavigateToMemoryStorage: () -> Unit = {},
    rootDetector: RootDetector? = null,
    permissionMode: PermissionMode = PermissionMode.ASK,
    onUpdatePermissionMode: (PermissionMode) -> Unit = {},
    onRootDiagnostic: (() -> Unit)? = null,
    rootDiagnosticResult: RootDiagnosticResult? = null,
    searchApiKey: String = "",
    onUpdateSearchApiKey: (String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    darkMode: Boolean = false,
    onUpdateDarkMode: (Boolean) -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToCuratedMemory: () -> Unit = {},   // Hermes-⑤
    onNavigateToCron: () -> Unit = {},            // Hermes-⑦
    onNavigateToContextCompress: () -> Unit = {}, // 上下文压缩(长度/阈值/总结规则)
    workspaceRoot: String = "",                   // 全局工作区根(空=当前安装的应用专属目录)
    onUpdateWorkspaceRoot: (String) -> Unit = {},
    onNavigateToAuxModels: () -> Unit = {},       // 模型委托(视觉/推理/翻译/转写副模型)
    onNavigateToFunctionModels: () -> Unit = {},  // 功能模型配置(每个内部调用点各指一套已存配置)
    onNavigateToLanDevices: () -> Unit = {},      // 局域网设备发现
    onNavigateToLogs: () -> Unit = {},            // 日志查看
    onNavigateToCodeIndex: () -> Unit = {},       // 代码索引
    onNavigateToUsageStats: () -> Unit = {},      // 用量分析
    onNavigateToKanban: () -> Unit = {},          // 看板
    onNavigateToGroupRooms: () -> Unit = {},      // 群聊房间
    onNavigateToProfiles: () -> Unit = {},        // 多配置环境
    onNavigateToSubAgents: () -> Unit = {},       // 子智能体
    onNavigateToEnvConfig: () -> Unit = {},       // 环境配置(内置开发环境/工具安装)
    onNavigateToIdeDashboard: () -> Unit = {},    // IDE 面板(Gradle/SDK/环境变量/LSP/UI设计等)
    onNavigateToAbout: () -> Unit = {}            // 关于页(版本/检查更新/项目地址/许可)
) {
    val xc = LocalXinColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    val context = LocalContext.current
    val expanded = remember { mutableStateMapOf(
        "外观" to true,
        "账户与模型" to true,
        "权限与安全" to true,
        "数据" to true,
        "Agent工具" to true,
        "关于" to true
    ) }
    fun toggle(key: String) { expanded[key] = !(expanded[key] ?: true) }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        XinPageHeader(
            title = "设置",
            subtitle = "外观、模型、权限与 Agent 工具",
            onBack = onBack
        )
        Spacer(Modifier.height(8.dp))

        // ── Section: 外观 ──
        SectionHeader(title = "外观", icon = Icons.Outlined.DarkMode, expanded = expanded["外观"] == true, onToggle = { toggle("外观") })
        AnimatedVisibility(visible = expanded["外观"] == true) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(xc.bgElevated, RoundedCornerShape(18.dp))
                        .border(1.dp, Border, RoundedCornerShape(18.dp))
                        .heightIn(min = 58.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onUpdateDarkMode(!darkMode) }
                        .padding(start = 20.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("暗色模式", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
                        Text(if (darkMode) "近黑终端配色" else "羊皮纸浅色配色", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub)
                    }
                    Switch(
                        checked = darkMode,
                        onCheckedChange = onUpdateDarkMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = xc.bgElevated,
                            checkedTrackColor = Green,
                            uncheckedThumbColor = Faint,
                            uncheckedTrackColor = Border,
                            uncheckedBorderColor = Border
                        )
                    )
                }

                // 回车发送开关(App 层可观察设置,立即生效)
                val app = LocalContext.current.applicationContext as XincodeApplication
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(xc.bgElevated, RoundedCornerShape(18.dp))
                        .border(1.dp, Border, RoundedCornerShape(18.dp))
                        .heightIn(min = 58.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { app.updateEnterToSend(!app.enterToSend) }
                        .padding(start = 20.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("回车发送", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
                        Text(if (app.enterToSend) "回车直接发送(换行用输入法组合键)" else "回车换行(发送靠 [→] 键)", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub)
                    }
                    Switch(
                        checked = app.enterToSend,
                        onCheckedChange = { app.updateEnterToSend(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = xc.bgElevated,
                            checkedTrackColor = Green,
                            uncheckedThumbColor = Faint,
                            uncheckedTrackColor = Border,
                            uncheckedBorderColor = Border
                        )
                    )
                }
            }
        }

        // ── Section: 账户与模型 ──
        SectionDivider()
        SectionHeader(title = "账户与模型", icon = Icons.Outlined.AccountCircle, expanded = expanded["账户与模型"] == true, onToggle = { toggle("账户与模型") })
        AnimatedVisibility(visible = expanded["账户与模型"] == true) {
            Column {
                // 统一收敛为「模型与供应商」单一入口，内部通过双 Tab 切换「我的配置」与「供应商市场」，避免双重入口困惑
                SettingRow("模型与供应商", "管理 API 密钥、切换默认模型与探索供应商市场", icon = Icons.Outlined.Tune) { onNavigateToSupplierConfig() }
            }
        }

        // ── Section: 权限与安全 ──
        SectionDivider()
        SectionHeader(title = "权限与安全", icon = Icons.Outlined.Security, expanded = expanded["权限与安全"] == true, onToggle = { toggle("权限与安全") })
        AnimatedVisibility(visible = expanded["权限与安全"] == true) {
            Column {
                // 1.22: 权限分级卡实时展示并支持一键请求 Shizuku 授权
                PrivilegeTierCard(context)
                Spacer(Modifier.height(8.dp))
                SettingRow("Root 状态", rootDetector?.status?.label ?: "检测中…", icon = Icons.Outlined.Terminal) { rootDetector?.recheck() }
                // Root diagnostic button
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("运行诊断", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRootDiagnostic?.invoke() }
                            .padding(vertical = 4.dp))
                }
                // Diagnostic result card
                val diag = rootDiagnosticResult
                if (diag != null) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(xc.bgElevated, RoundedCornerShape(18.dp))
                            .border(1.dp, Border, RoundedCornerShape(18.dp))
                            .padding(12.dp)
                    ) {
                        Text("── Root 诊断报告 ──", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub)
                        Spacer(Modifier.height(4.dp))
                        diagRow("id", diag.id, Green)
                        diagRow("whoami", diag.whoami, Green)
                        diagRow("/sdcard", "${diag.lsSdcard.lines().size} 项", if (diag.lsSdcard.isNotBlank()) Green else Red)
                        diagRow("/system", if (diag.catSystemBuild.isNotBlank()) "可读" else "不可读", if (diag.catSystemBuild.isNotBlank()) Green else Red)
                        diagRow("/data/data", if (diag.lsDataData.isNotBlank()) "${diag.lsDataData.lines().size} 项" else "不可访问", if (diag.lsDataData.isNotBlank()) Green else Red)
                        if (diag.errors.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            diag.errors.forEach { err ->
                                Text("✗ $err", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Red)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val conclusion = if (diag.errors.isEmpty()) "结论: Root 正常工作" else "结论: Root 异常"
                        Text(conclusion, fontSize = 10.sp, fontFamily = JetBrainsMono, color = if (diag.errors.isEmpty()) Green else Red)
                    }
                }

                // 权限/计划模式已移到输入框内的「○聊天 / ◑计划 / ●全自动」三态开关,设置页不再重复。

                SettingRow("审计日志", "查看所有调用记录", icon = Icons.Outlined.BugReport) { onNavigateToAuditLog() }
            }
        }

        // ── Section: 数据 ──
        SectionDivider()
        SectionHeader(title = "数据", icon = Icons.Outlined.Storage, expanded = expanded["数据"] == true, onToggle = { toggle("数据") })
        AnimatedVisibility(visible = expanded["数据"] == true) {
            Column {
                SettingRow("记忆与存储", "管理本地记忆数据", icon = Icons.Outlined.Storage) { onNavigateToMemoryStorage() }
                SettingRow("上下文压缩", "自定义上下文长度、自动压缩阈值与总结规则", icon = Icons.Outlined.Memory) { onNavigateToContextCompress() }
                SettingRow("精编记忆", "查看/编辑 agent 记住的你与近况", icon = Icons.Outlined.Lightbulb) { onNavigateToCuratedMemory() }
                SettingRow("定时任务", "管理后台自动化 cron 任务", icon = Icons.Outlined.Build) { onNavigateToCron() }
            }
        }

        // ── Section: Agent 工具 ──
        SectionDivider()
        var showSearchKeyDialog by remember { mutableStateOf(false) }
        var showWorkspaceDialog by remember { mutableStateOf(false) }
        SectionHeader(title = "Agent 工具", icon = Icons.Outlined.Build, expanded = expanded["Agent工具"] == true, onToggle = { toggle("Agent工具") })
        AnimatedVisibility(visible = expanded["Agent工具"] == true) {
            Column {
                SettingRow(
                    "全局工作区目录",
                    workspaceRoot.ifBlank { com.xincode.tools.WorkspaceContext.defaultRoot + " (默认·免 Root)" },
                    icon = Icons.Outlined.Storage
                ) { showWorkspaceDialog = true }
                if (showWorkspaceDialog) {
                    DirectoryPickerDialog(
                        initialPath = workspaceRoot,
                        onConfirm = { onUpdateWorkspaceRoot(it); showWorkspaceDialog = false },
                        onDismiss = { showWorkspaceDialog = false }
                    )
                }
                // 1.22: 开发工具 IDE 已移至侧边栏 DEVELOPER，设置页不再重复
                SettingRow("环境配置", "安装 Node/Python/uv/SSH/JDK/Gradle/Rust/Go 等开发环境", icon = Icons.Outlined.Build) { onNavigateToEnvConfig() }
                SettingRow("配置环境", "多套独立配置(工作/私用各一套),可克隆与导出导入", icon = Icons.Outlined.Settings) { onNavigateToProfiles() }
                SettingRow("功能模型配置", "上下文总结/后台复盘/子智能体/Goal 裁判等各自指定模型", icon = Icons.Outlined.Memory) { onNavigateToFunctionModels() }
                SettingRow("模型委托", "视觉/推理/翻译/转写各配一个副模型(另填 URL 与 Key)", icon = Icons.Outlined.Extension) { onNavigateToAuxModels() }
                SettingRow("子智能体", "主脑指挥的专职子智能体(各管各的技能),可自建", icon = Icons.Outlined.Folder) { onNavigateToSubAgents() }
                SettingRow("局域网设备", "发现同一 Wi-Fi 下其它开着 XINCODE 的设备", icon = Icons.Outlined.Extension) { onNavigateToLanDevices() }
                // 群聊房间入口归侧边栏(与 GOAL/IDE 同层);设置页不再重复,避免双入口分流。
                SettingRow("看板", "跨会话的长期待办,可把 AI 的计划一键导入", icon = Icons.Outlined.Build) { onNavigateToKanban() }
                SettingRow("用量分析", "30 天趋势、模型分布、缓存命中率与成本估算", icon = Icons.Outlined.Storage) { onNavigateToUsageStats() }
                SettingRow("日志", "崩溃与运行日志,按级别/关键词过滤,可复制反馈", icon = Icons.Outlined.BugReport) { onNavigateToLogs() }
                SettingRow("代码索引", "把工作区代码结构抽进本地索引,AI 查符号定义与调用关系不再靠读文件", icon = Icons.Outlined.Code) { onNavigateToCodeIndex() }
                SettingRow("Git 接入", "OAuth 登录 GitHub + 远程/本地 MCP(免 root 也能用)", icon = Icons.Outlined.Code) { onNavigateToGit() }
                SettingRow("搜索 API Key", if (searchApiKey.isNotBlank()) "••••••••••" else "未配置", icon = Icons.Outlined.Extension) { showSearchKeyDialog = true }
                if (showSearchKeyDialog) {
                    var tempKey by remember { mutableStateOf(searchApiKey) }
                    AlertDialog(
                        onDismissRequest = { showSearchKeyDialog = false },
                        title = { Text("搜索 API Key", fontFamily = JetBrainsMono, color = Ink) },
                        text = {
                            Column {
                                Text("输入 Tavily Search API Key", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub)
                                Spacer(Modifier.height(8.dp))
                                TextField(
                                    value = tempKey,
                                    onValueChange = { tempKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("tavily-...", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { onUpdateSearchApiKey(tempKey); showSearchKeyDialog = false }) {
                                Text("保存", fontFamily = JetBrainsMono, color = Green)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSearchKeyDialog = false }) {
                                Text("取消", fontFamily = JetBrainsMono, color = Sub)
                            }
                        },
                        containerColor = Bg
                    )
                }
            }
        }

        // ── Section: 关于 ──
        SectionDivider()
        SectionHeader(title = "关于", icon = Icons.Outlined.Settings, expanded = expanded["关于"] == true, onToggle = { toggle("关于") })
        AnimatedVisibility(visible = expanded["关于"] == true) {
            Column {
                SettingRow("关于 XINCODE", "版本信息、检查更新、项目地址与开源许可", icon = Icons.Outlined.Settings) { onNavigateToAbout() }
            }
        }
    }
}

@Composable
private fun PrivilegeTierCard(ctx: android.content.Context) {
    val xc = LocalXinColors.current
    var refresh by remember { mutableStateOf(0) }
    val tier = remember(refresh) { PrivilegedExecutor.currentTier(ctx) }
    val isAvailable = remember(refresh) { ShizukuShell.isAvailable(ctx) }
    val isGranted = remember(refresh) { ShizukuShell.isPermissionGranted(ctx) }
    val label = when {
        tier == com.xincode.app.privilege.PrivilegeTier.ROOT -> "Root · 已授权"
        isAvailable && isGranted -> "Shizuku · 已授权"
        isAvailable && !isGranted -> "Shizuku · 未授权"
        else -> "普通 · 沙盒运行"
    }
    val desc = when {
        tier == com.xincode.app.privilege.PrivilegeTier.ROOT -> "Root 优先，终端与工具已接管"
        isAvailable && isGranted -> "Shizuku 接管终端，Root > Shizuku > 普通 自动降级"
        isAvailable -> "检测到 Shizuku，点击授权后可提升权限"
        else -> "Git/文件等基础能力可用，构建类需 Root/Shizuku"
    }
    val dotColor = when {
        tier == com.xincode.app.privilege.PrivilegeTier.ROOT -> xc.green
        isAvailable && isGranted -> Color(0xFF4FC3F7)
        isAvailable -> Color(0xFFF2C14E)
        else -> xc.faint
    }
    Column(
        Modifier.fillMaxWidth()
            .background(xc.bgElevated, RoundedCornerShape(14.dp))
            .border(1.dp, xc.border, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Text("权限分级", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (isAvailable && !isGranted) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(xc.green.copy(alpha = 0.15f))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            ShizukuShell.requestPermission(1001)
                            refresh++
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("请求授权", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.green) }
            } else {
                Text("查询", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { refresh++ }.padding(4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text(desc, fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
        Text("分级：Root > Shizuku > 普通 · 终端已接管，自动降级执行", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector? = null, expanded: Boolean = true, onToggle: (() -> Unit)? = null) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggle() } else Modifier)
            .padding(start = 4.dp, top = 20.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = xc.sub)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (title.all { it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }) title.uppercase() else title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = JetBrainsMono,
            color = xc.sub,
            letterSpacing = 0.05.em,
            modifier = Modifier.weight(1f)
        )
        if (onToggle != null) {
            Text(if (expanded) "︿" else "﹀", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.faint,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingRow(label: String, value: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(xc.bgElevated, RoundedCornerShape(18.dp))
            .border(1.dp, xc.border, RoundedCornerShape(18.dp))
            .heightIn(min = 52.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = xc.sub)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontFamily = JetBrainsMono, color = xc.ink)
            if (value.isNotBlank()) {
                Text(
                    value, fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("›", fontSize = 20.sp, fontFamily = JetBrainsMono, color = xc.faint)
    }
}

@Composable
private fun diagRow(label: String, value: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", fontSize = 10.sp, fontFamily = JetBrainsMono, color = LocalXinColors.current.sub)
        Text(value.take(60), fontSize = 10.sp, fontFamily = JetBrainsMono, color = color)
    }
}
