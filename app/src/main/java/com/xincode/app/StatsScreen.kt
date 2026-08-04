package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

private data class CallSlice(val label: String, val count: Int, val color: Color)
private data class StatsData(
    val contextTokens: Long,
    val contextWindow: Int,
    val prompt: Long,
    val completion: Long,
    val cacheHit: Long,
    val slices: List<CallSlice>,
    val totalCalls: Int
)

private val CAT_COLORS = mapOf(
    "网络" to Color(0xFF6FB3E0),
    "文件" to Color(0xFF7BE0A4),
    "终端" to Color(0xFFF2C14E),
    "技能" to Color(0xFFB39DDB),
    "子智能体" to Color(0xFFE0685C),
    "计划" to Color(0xFF4DB6AC),
    "记忆" to Color(0xFFF06292),
    "MCP" to Color(0xFF90A4AE),
    "其他" to Color(0xFFBDBDBD)
)

private fun categoryOf(tool: String): String = when {
    tool in listOf("web_search", "web_fetch", "web_search_batch") -> "网络"
    tool in listOf("file_read", "file_write", "file_edit", "edit", "multi_edit", "list_dir", "grep", "glob") -> "文件"
    tool in listOf("shell_exec", "su_exec", "code_exec") -> "终端"
    tool in listOf("invoke_skill", "skill_manage") -> "技能"
    tool in listOf("dispatch_agents", "wolfpack_run") -> "子智能体"
    tool == "agent_plan" -> "计划"
    tool in listOf("recall_memory", "save_memory") -> "记忆"
    tool.contains("__") || tool.startsWith("mcp") -> "MCP"
    else -> "其他"
}

/** 使用统计:当前上下文 token、累计 token、子智能体 token,以及各类工具调用占比图。 */
@Composable
fun StatsScreen(database: AppDatabase, chatState: AgentChatState, sessionId: Long, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    var data by remember { mutableStateOf<StatsData?>(null) }

    LaunchedEffect(sessionId) {
        data = withContext(Dispatchers.IO) {
            val ts = chatState.getSessionTokenStats()
            val cfg = database.providerConfigDao().getActive()
            val msgs = database.messageDao().getBySessionId(sessionId)
            // 当前上下文占用 ≈ 最近一次请求的 prompt_tokens。
            val ctx = msgs.mapNotNull { it.promptTokens }.lastOrNull() ?: 0L
            // 工具调用分类计数。
            val counts = HashMap<String, Int>()
            var total = 0
            for (m in msgs) {
                if (m.role != "tool") continue
                val name = try {
                    val j = org.json.JSONObject(m.content)
                    if (j.optBoolean("__tool_call__", false)) j.optString("tool_name", "") else ""
                } catch (_: Exception) { "" }
                if (name.isBlank()) continue
                val cat = categoryOf(name)
                counts[cat] = (counts[cat] ?: 0) + 1
                total++
            }
            val slices = counts.entries.sortedByDescending { it.value }
                .map { CallSlice(it.key, it.value, CAT_COLORS[it.key] ?: Color(0xFFBDBDBD)) }
            StatsData(ctx, cfg?.contextWindow ?: 0, ts.prompt, ts.completion, ts.cacheHit, slices, total)
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("使用统计", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
        }

        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("统计中…", fontSize = 12.sp, fontFamily = Mono, color = xc.faint)
            }
            return
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // 上下文占用卡
            Card(xc) {
                Text("当前上下文占用", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                val ratio = if (d.contextWindow > 0) (d.contextTokens.toFloat() / d.contextWindow).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(d.contextTokens), fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
                    Spacer(Modifier.width(6.dp))
                    Text(if (d.contextWindow > 0) "/ ${fmt(d.contextWindow.toLong())} tokens" else "tokens",
                        fontSize = 12.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (d.contextWindow > 0) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(xc.faint.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                            .background(if (ratio > 0.85f) xc.red else xc.green))
                    }
                    Text("${(ratio * 100).toInt()}% 已用", fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }

            // 累计 token 卡
            Card(xc) {
                Text("本会话累计 token", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(8.dp))
                StatRow("输入(prompt)", fmt(d.prompt), xc)
                StatRow("输出(completion)", fmt(d.completion), xc)
                StatRow("缓存命中", fmt(d.cacheHit), xc)
                Spacer(Modifier.height(8.dp))
                Text("子智能体", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                StatRow("子智能体累计 token", fmt(AgentStats.subAgentTokens), xc)
                StatRow("子智能体运行次数", AgentStats.subAgentRuns.toString(), xc)
            }

            // 调用占比卡
            Card(xc) {
                Text("工具/调用占比 (共 ${d.totalCalls} 次)", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(10.dp))
                if (d.slices.isEmpty()) {
                    Text("本会话还没有工具调用。", fontSize = 11.sp, fontFamily = Mono, color = xc.faint)
                } else {
                    // 顶部一条堆叠占比条
                    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                        d.slices.forEach { s ->
                            Box(Modifier.weight(s.count.toFloat()).fillMaxHeight().background(s.color))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    d.slices.forEach { s ->
                        val pct = if (d.totalCalls > 0) s.count * 100 / d.totalCalls else 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(s.color))
                            Spacer(Modifier.width(8.dp))
                            Text(s.label, fontSize = 12.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f))
                            Text("${s.count} 次 · $pct%", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(xc: XinColors, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(xc.bgElevated).padding(16.dp), content = content)
}

@Composable
private fun StatRow(label: String, value: String, xc: XinColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = Mono, color = xc.ink)
    }
}

private fun fmt(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
