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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.security.PermissionMode
import com.xincode.tools.RootDiagnosticResult
import kotlinx.coroutines.launch

// Palette now sourced from [LocalXinColors].
private val JetBrainsMono = XinUiFont

/**
 * Settings main screen — 5 sections.
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
    val ThinLine = xc.divider
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
        SectionHeader("外观")
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

        // ── Section: 账户与模型 ──
        SectionDivider()
        SectionHeader("账户与模型")
        SettingRow("免费模型 / 供应商", "预置免费与付费供应商,带官网入口,一键添加") { onNavigateToModelMarket() }
        SettingRow("供应商配置", "管理 API 供应商、密钥和模型") { onNavigateToSupplierConfig() }

        // ── Section: 权限与安全 ──
        SectionDivider()
        SectionHeader("权限与安全")
        SettingRow("Root 状态", rootDetector?.status?.label ?: "检测中…") { rootDetector?.recheck() }
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

        SettingRow("审计日志", "查看所有调用记录") { onNavigateToAuditLog() }

        // ── Section: 数据 ──
        SectionDivider()
        SectionHeader("数据")
        SettingRow("记忆与存储", "管理本地记忆数据") { onNavigateToMemoryStorage() }
        SettingRow("上下文压缩", "自定义上下文长度、自动压缩阈值与总结规则") { onNavigateToContextCompress() }
        SettingRow("精编记忆", "查看/编辑 agent 记住的你与近况") { onNavigateToCuratedMemory() }
        SettingRow("定时任务", "管理后台自动化 cron 任务") { onNavigateToCron() }

        // ── Section: Agent 工具 ──
        SectionDivider()
        var showSearchKeyDialog by remember { mutableStateOf(false) }
        var showWorkspaceDialog by remember { mutableStateOf(false) }
        SectionHeader("Agent 工具")
        SettingRow(
            "全局工作区目录",
            workspaceRoot.ifBlank { com.xincode.tools.WorkspaceContext.defaultRoot + " (默认·免 Root)" }
        ) { showWorkspaceDialog = true }
        if (showWorkspaceDialog) {
            // 文件夹选择器:AI 产出/写入的默认目录(仍可读目录之外)。每个项目还可单独覆盖(见侧栏项目菜单)。
            DirectoryPickerDialog(
                initialPath = workspaceRoot,
                onConfirm = { onUpdateWorkspaceRoot(it); showWorkspaceDialog = false },
                onDismiss = { showWorkspaceDialog = false }
            )
        }
        SettingRow("开发工具 IDE", "Gradle·JDK 11/17·SDK/NDK·环境变量·语言服务器·UI设计师·翻译·Asset·插件·Git") { onNavigateToIdeDashboard() }
        SettingRow("环境配置", "安装 Node/Python/uv/SSH/JDK/Gradle/Rust/Go 等开发环境") { onNavigateToEnvConfig() }
        SettingRow("配置环境", "多套独立配置(工作/私用各一套),可克隆与导出导入") { onNavigateToProfiles() }
        SettingRow("功能模型配置", "上下文总结/后台复盘/子智能体/Goal 裁判等各自指定模型") { onNavigateToFunctionModels() }
        SettingRow("模型委托", "视觉/推理/翻译/转写各配一个副模型(另填 URL 与 Key)") { onNavigateToAuxModels() }
        SettingRow("子智能体", "主脑指挥的专职子智能体(各管各的技能),可自建") { onNavigateToSubAgents() }
        SettingRow("局域网设备", "发现同一 Wi-Fi 下其它开着 XINCODE 的设备") { onNavigateToLanDevices() }
        SettingRow("群聊房间", "多个智能体同处一室,@名字 点谁谁答") { onNavigateToGroupRooms() }
        SettingRow("看板", "跨会话的长期待办,可把 AI 的计划一键导入") { onNavigateToKanban() }
        SettingRow("用量分析", "30 天趋势、模型分布、缓存命中率与成本估算") { onNavigateToUsageStats() }
        SettingRow("日志", "崩溃与运行日志,按级别/关键词过滤,可复制反馈") { onNavigateToLogs() }
        SettingRow("代码索引", "把工作区代码结构抽进本地索引,AI 查符号定义与调用关系不再靠读文件") { onNavigateToCodeIndex() }
        SettingRow("Skills 技能", "管理可复用提示词模板") { onNavigateToSkills() }
        SettingRow("MCP 服务器", "外部工具协议服务器管理") { onNavigateToMcp() }
        SettingRow("Git 接入", "OAuth 登录 GitHub + 远程/本地 MCP(免 root 也能用)") { onNavigateToGit() }
        SettingRow("搜索 API Key", if (searchApiKey.isNotBlank()) "••••••••••" else "未配置") { showSearchKeyDialog = true }
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

        // ── Section: 关于 ──
        SectionDivider()
        SectionHeader("关于")
        SettingRow("关于 XINCODE", "版本信息、检查更新、项目地址与开源许可") { onNavigateToAbout() }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        if (title.all { it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }) title.uppercase() else title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = JetBrainsMono,
        color = LocalXinColors.current.sub,
        letterSpacing = 0.05.em,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(xc.bgElevated, RoundedCornerShape(18.dp))
            .border(1.dp, xc.border, RoundedCornerShape(18.dp))
            .heightIn(min = 52.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(start = 20.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标题在上,值/说明作为小字在标题【下方】(不再挤在右边、也不再太长占位)。
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
        // 右侧仅一个箭头入口。
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
