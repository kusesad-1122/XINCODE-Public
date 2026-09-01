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

@Composable
fun IdeDashboardScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val xc = LocalXinColors.current
    val groups = listOf(
        "代码与语言" to listOf(
            IdeEntry("lsp", "语言服务器", "Java / Kotlin / XML · 诊断补全", "L"),
            IdeEntry("git", "Git 集成", "状态/提交/分支/日志 · 终端 git", "G"),
        ),
        "界面与资源" to listOf(
            IdeEntry("designer", "UI 设计师", "布局充气器·拖放·可视化编辑·小部件", "U"),
            IdeEntry("translator", "字符串翻译器", "strings.xml 多语言 · AI 翻译", "T"),
            IdeEntry("assets", "Asset Studio", "图标/绘图 · Vector/Shape 生成", "A"),
        ),
        "扩展" to listOf(
            IdeEntry("plugin", "插件创建器", "子模块/插件脚手架 · library/feature", "P"),
            IdeEntry("terminal", "终端", "Ubuntu chroot · 包含必需品", "›"),
            IdeEntry("log", "日志读取器", "logcat 实时 · 崩溃/文件日志", "L"),
        )
    )

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "IDE", subtitle = "LSP·UI设计·翻译·Asset·插件·Git · 构建与环境请前往 环境配置", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            groups.forEach { (groupTitle, entries) ->
                item {
                    Text(groupTitle, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                entries.chunked(2).forEach { row ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { e ->
                                Box(
                                    Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(14.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(14.dp))
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
                                        Spacer(Modifier.height(4.dp))
                                        Text(e.desc, fontSize = 9.sp, fontFamily = Mono, color = xc.sub, lineHeight = 11.sp, maxLines = 2)
                                    }
                                }
                            }
                            if (row.size==1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                    Text("IDE 专注代码与设计：三语言服务器/UI设计全套(含充气器/资源解析/拖放/可视化编辑/小部件/翻译/Asset)/插件创建器/Git/日志。构建与环境变量(Gradle/JDK/SDK/环境变量)已统一收归 环境配置 → 构建与环境变量 融合卡，入口唯一，返回路径按来源回退。", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089), lineHeight = 13.sp)
                }
            }
        }
    }
}
