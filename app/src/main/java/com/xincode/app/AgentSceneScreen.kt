package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xincode.app.R
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SceneBg = Color(0xFF141621)
private val Cream = Color(0xFFF3EFE0)
private val Grey = Color(0xFF6B7089)
private val GreenC = Color(0xFF7BE0A4)
private val Amber = Color(0xFFF2C14E)
private val BlueC = Color(0xFF6FB3E0)
private val Panel = Color(0xFF232739)
private val SceneMono = XinUiFont

/**
 * 「智能体指挥室」独立页(全屏,竖屏像素办公室)。
 * 每个【已配置的子智能体】都常驻一个工位 + 像素小人:没派活时在休息室打盹,
 * 主脑派活后走到工位工作。dispatch_agents 一跑就实时联动。
 */
@Composable
fun AgentSceneScreen(scene: SubAgentSceneState, database: AppDatabase, onBack: () -> Unit) {
    var agents by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        agents = withContext(Dispatchers.IO) {
            try { database.subAgentDao().getAll().map { it.name } } catch (_: Exception) { emptyList() }
        }
    }
    val liveByName = scene.workers.associateBy { it.agent }
    var selected by remember { mutableStateOf<SubAgentSceneState.Worker?>(null) }

    // 仅在【指挥室】这一页把系统状态栏/导航栏彻底透明化,并让场景延伸到它们后面(edge-to-edge)。
    // 离开本页时还原成应用默认的浅色状态栏,不影响其它页面。
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        }
        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.statusBarColor = 0xFFF9F9F6.toInt()
                window.navigationBarColor = 0xFFF9F9F6.toInt()
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
            }
        }
    }

    Box(Modifier.fillMaxSize().background(SceneBg)) {
        if (agents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("╭───╮", fontSize = 20.sp, fontFamily = SceneMono, color = Grey)
                    Text("│ 主脑 │", fontSize = 16.sp, fontFamily = SceneMono, color = Cream)
                    Text("╰───╯", fontSize = 20.sp, fontFamily = SceneMono, color = Grey)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有子智能体", fontSize = 12.sp, fontFamily = SceneMono, color = Grey)
                    Text("去 设置 → 子智能体 添加,每加一个就会生成一个像素小人和工位", fontSize = 10.sp, fontFamily = SceneMono, color = Grey)
                }
            }
        } else {
            // 全屏像素办公室(WebView/Canvas 引擎),铺满整个屏幕作背景层。
            OfficeWebView(
                agents = agents,
                liveByName = liveByName,
                brainBusy = scene.brainBusy,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 顶栏:透明背景悬浮在场景之上,让像素办公室铺满整屏;仅【返回】与【标题】
        // 各自套一颗半透明小药丸保证可读,其余全透明(用户要求:标题和出口UI不透明,其他透明化)。
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("← 返回", fontSize = 12.sp, fontFamily = SceneMono, color = Cream,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC141621))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            Text(if (scene.brainBusy) "智能体指挥室 · 指挥中" else "智能体指挥室",
                fontSize = 13.sp, fontFamily = SceneMono, color = Cream,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC141621))
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }

    val sel = selected
    if (sel != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭", fontFamily = SceneMono, color = GreenC) } },
            title = { Text("${sel.agent} · ${statusText(sel.status)}", fontSize = 13.sp, fontFamily = SceneMono, color = Cream) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("领的活", fontSize = 10.sp, fontFamily = SceneMono, color = Amber)
                    Text(sel.task, fontSize = 11.sp, fontFamily = SceneMono, color = Cream)
                    Spacer(Modifier.height(10.dp))
                    if (sel.status == SubAgentSceneState.Status.RUNNING) {
                        Text("正在做(实时)", fontSize = 10.sp, fontFamily = SceneMono, color = GreenC)
                        Text(sel.activity.ifBlank { "(启动中…)" }, fontSize = 11.sp, fontFamily = SceneMono, color = Grey, lineHeight = 15.sp)
                    } else {
                        Text("结论 / 产出", fontSize = 10.sp, fontFamily = SceneMono, color = BlueC)
                        Text(sel.result.ifBlank { sel.activity.ifBlank { "(无输出)" } }, fontSize = 11.sp, fontFamily = SceneMono, color = Cream, lineHeight = 15.sp)
                    }
                }
            },
            containerColor = Panel
        )
    }
}

/** 从 Compose 的 Context 里往上找到承载的 Activity(拿 window 改状态栏用)。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun statusDot(s: SubAgentSceneState.Status) = when (s) {
    SubAgentSceneState.Status.PREPARING -> Grey
    SubAgentSceneState.Status.RUNNING -> GreenC
    SubAgentSceneState.Status.DONE -> BlueC
    SubAgentSceneState.Status.FAILED -> Color(0xFFE0685C)
}

private fun statusText(s: SubAgentSceneState.Status) = when (s) {
    SubAgentSceneState.Status.PREPARING -> "○ 准备"
    SubAgentSceneState.Status.RUNNING -> "◐ 执行中"
    SubAgentSceneState.Status.DONE -> "● 完成"
    SubAgentSceneState.Status.FAILED -> "✗ 失败"
}
