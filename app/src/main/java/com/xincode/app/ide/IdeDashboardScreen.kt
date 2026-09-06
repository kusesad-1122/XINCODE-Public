package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LocalXinColors
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont

private val Mono = XinUiFont

data class IdeEntry(val id: String, val title: String, val desc: String, val icon: String)

/**
 * IDE 面板 —— 立场是「给 Agent 用的开发环境」,不是给人用的编辑器:
 * 这里配置的每一项都是 Agent 替你干活的装备,LSP/Git/资源工具/终端,
 * 配好之后回对话页直接下任务即可。
 */
@Composable
fun IdeDashboardScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val xc = LocalXinColors.current
    val groups = listOf(
        "Agent 的代码能力" to listOf(
            IdeEntry("lsp", "语言服务器", "Agent 读代码时的诊断与定义跳转 · Java/Kotlin/XML", "L"),
            IdeEntry("git", "Git 集成", "Agent 提交/分支/日志走的通道 · 终端 git", "G"),
        ),
        "Agent 的资源工具" to listOf(
            IdeEntry("designer", "UI 设计师", "Agent 解析/修改布局与资源的可视化工具", "U"),
            IdeEntry("translator", "字符串翻译器", "Agent 批量翻译 strings.xml 多语言 · AI 辅助", "T"),
            IdeEntry("assets", "Asset Studio", "Agent 生成图标与 Vector/Shape 资源", "A"),
        ),
        "Agent 的手和眼" to listOf(
            IdeEntry("plugin", "插件创建器", "在工作区项目里搭子模块/插件脚手架", "P"),
            IdeEntry("terminal", "Ubuntu 终端", "Agent env_exec 的执行现场 · apt/构建都在这", "›"),
            IdeEntry("log", "日志读取器", "Agent 与 App 的运行日志 · 排障证据", "L"),
        )
    )

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "Agent IDE",
            subtitle = "给 Agent 用的开发环境与工具箱 · 构建环境在 环境配置",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            item {
                // 定位说明:把「这是谁的 IDE」一次讲清楚
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1117)).padding(14.dp)) {
                    Column {
                        Text("这里是 Agent 的工作台,不是给人用的编辑器。", fontSize = 12.sp, fontFamily = Mono, color = Color(0xFFD8DCE8), lineHeight = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "下面的每一项都是给 Agent 配装备:语言服务器让它读代码带诊断,Git 让它能提交分支,设计器/翻译器/Asset 让它改资源,Ubuntu 终端是它执行命令的手。配好环境后回对话页直接下任务即可;Gradle/JDK/SDK/环境变量在 设置 → 环境配置,环境变量会注入 Agent 的 Ubuntu 执行环境。",
                            fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089), lineHeight = 14.sp
                        )
                    }
                }
            }
            groups.forEach { (groupTitle, entries) ->
                item {
                    Text(groupTitle, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                entries.chunked(2).forEach { row ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { e ->
                                Box(
                                    Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(14.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(14.dp))
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNavigate(e.id) }
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(xc.green.copy(0.15f)), contentAlignment = Alignment.Center) {
                                                Text(e.icon, fontSize = 14.sp, fontFamily = Mono, color = xc.green)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(e.title, fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(e.desc, fontSize = 9.sp, fontFamily = Mono, color = xc.sub, lineHeight = 12.sp, maxLines = 3)
                                    }
                                }
                            }
                            if (row.size==1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
