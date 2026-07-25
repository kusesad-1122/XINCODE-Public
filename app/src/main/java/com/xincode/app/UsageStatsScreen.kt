package com.xincode.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.DailyUsage
import com.xincode.data.ModelUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/**
 * 用量分析:30 天趋势 + 模型分布 + 成本估算 + 缓存命中率。
 *
 * 数据来自 usage_records —— 按【每次模型调用】记的,不是按回合,所以工具回环的开销
 * 也算得进去。老版本没有这张表,升级后前几天是空的,属正常。
 */
@Composable
fun UsageStatsScreen(database: AppDatabase, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var days by remember { mutableStateOf(30) }
    var daily by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }
    var byModel by remember { mutableStateOf<List<ModelUsage>>(emptyList()) }
    var calls by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }

    fun load() {
        loading = true
        scope.launch {
            val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            withContext(Dispatchers.IO) {
                val dao = database.usageRecordDao()
                daily = runCatching { dao.dailySince(since) }.getOrDefault(emptyList())
                byModel = runCatching { dao.byModelSince(since) }.getOrDefault(emptyList())
                calls = runCatching { dao.callCountSince(since) }.getOrDefault(0L)
            }
            loading = false
        }
    }
    LaunchedEffect(days) { load() }

    val totalInput = daily.sumOf { it.input }
    val totalOutput = daily.sumOf { it.output }
    val totalCacheRead = daily.sumOf { it.cacheRead }
    // 命中率 = 缓存命中 / 本可计费的总输入。
    // 入库时已经把「含缓存」的供应商扣成净输入了(见 UsageRecorder),
    // 所以这里的分母 input + cacheRead 才是真正的总输入,不会重复计算。
    val hitRate = if (totalInput + totalCacheRead > 0)
        totalCacheRead.toDouble() / (totalInput + totalCacheRead) * 100 else 0.0

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("用量分析", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                listOf(7 to "7 天", 30 to "30 天", 60 to "60 天").forEach { (d, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (d == days) xc.green.copy(alpha = 0.15f) else xc.bgElevated)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { days = d }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = Mono,
                            color = if (d == days) xc.green else xc.sub)
                    }
                }
            }

            if (loading) {
                Text("统计中…", fontSize = 12.sp, fontFamily = Mono, color = xc.faint,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else if (daily.isEmpty()) {
                Text(
                    "这段时间还没有用量记录。\n用量统计从本次更新后才开始记录,之前的对话没有数据。",
                    fontSize = 12.sp, fontFamily = Mono, color = xc.sub, lineHeight = 18.sp,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                // 总览
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("输入", fmtTokens(totalInput), xc, Modifier.weight(1f))
                    StatBox("输出", fmtTokens(totalOutput), xc, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("调用次数", calls.toString(), xc, Modifier.weight(1f))
                    StatBox("缓存命中", String.format("%.1f%%", hitRate), xc, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                val avgPerDay = if (daily.isNotEmpty()) (totalInput + totalOutput) / daily.size else 0L
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("日均 token", fmtTokens(avgPerDay), xc, Modifier.weight(1f))
                    StatBox("缓存读", fmtTokens(totalCacheRead), xc, Modifier.weight(1f))
                }

                // 趋势
                Spacer(Modifier.height(20.dp))
                Text("每日 token(输入 + 输出)", fontSize = 12.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, color = xc.ink)
                Spacer(Modifier.height(8.dp))
                DailyBars(daily, xc)

                // 模型分布
                Spacer(Modifier.height(20.dp))
                Text("模型分布", fontSize = 12.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, color = xc.ink)
                Spacer(Modifier.height(8.dp))
                val grand = byModel.sumOf { it.input + it.output }.coerceAtLeast(1)
                byModel.forEach { m ->
                    val total = m.input + m.output
                    val pct = total.toDouble() / grand * 100
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.model, fontSize = 11.sp, fontFamily = Mono, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(String.format("%.1f%%", pct), fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                        }
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.bgElevated)) {
                            Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f))
                                .height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.green))
                        }
                        Text("${fmtTokens(total)}  ·  ${m.calls} 次调用",
                            fontSize = 9.sp, fontFamily = Mono, color = xc.faint,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }

                // 成本
                Spacer(Modifier.height(20.dp))
                Text("成本估算", fontSize = 12.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, color = xc.ink)
                Text(
                    "按各模型公开价目粗算,仅供参考。未收录的模型不计入,实际以供应商账单为准。",
                    fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
                val cost = byModel.sumOf { estimateCostCny(it) }
                val known = byModel.count { PRICES.keys.any { k -> it.model.contains(k, true) } }
                StatBox("估算(人民币)", String.format("¥ %.2f", cost), xc, Modifier.fillMaxWidth())
                Text(
                    "$known / ${byModel.size} 个模型有价目数据",
                    fontSize = 9.sp, fontFamily = Mono, color = xc.faint,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, xc: XinColors, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(xc.bgElevated).padding(12.dp)) {
        Text(label, fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
        Text(value, fontSize = 16.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
            color = xc.ink, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 每日柱状图。自己画,不引图表库——就一个柱状图,没必要多一个依赖。 */
@Composable
private fun DailyBars(daily: List<DailyUsage>, xc: XinColors) {
    val maxV = daily.maxOfOrNull { it.input + it.output }?.coerceAtLeast(1) ?: 1
    val green = xc.green
    val faint = xc.faint
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (daily.isEmpty()) return@Canvas
        val gap = 2f
        val barW = ((size.width - gap * (daily.size - 1)) / daily.size).coerceAtLeast(1f)
        daily.forEachIndexed { i, d ->
            val v = (d.input + d.output).toFloat() / maxV
            val h = (size.height * v).coerceAtLeast(1f)
            drawRect(
                color = green,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h)
            )
        }
        // 底线
        drawRect(color = faint, topLeft = Offset(0f, size.height - 0.5f), size = Size(size.width, 0.5f))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(daily.firstOrNull()?.day?.removePrefix("20").orEmpty(),
            fontSize = 9.sp, fontFamily = Mono, color = xc.faint)
        Spacer(Modifier.weight(1f))
        Text(daily.lastOrNull()?.day?.removePrefix("20").orEmpty(),
            fontSize = 9.sp, fontFamily = Mono, color = xc.faint)
    }
}

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.2fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

/**
 * 粗略价目表(人民币 / 每百万 token,输入 to 输出)。
 *
 * 只收录常见的几家,按模型名【包含】匹配。价格会变,这里只求量级正确——
 * 页面上也明说了「仅供参考,以账单为准」,不做精确账。
 */
private val PRICES: Map<String, Pair<Double, Double>> = mapOf(
    "deepseek-chat" to (2.0 to 8.0),
    "deepseek-reasoner" to (4.0 to 16.0),
    "gpt-4o-mini" to (1.1 to 4.3),
    "gpt-4o" to (18.0 to 72.0),
    "claude-sonnet" to (21.0 to 108.0),
    "claude-opus" to (108.0 to 540.0),
    "claude-haiku" to (1.8 to 9.0),
    "glm-4-flash" to (0.0 to 0.0),
    "glm-4" to (3.6 to 3.6),
    "qwen-turbo" to (0.3 to 0.6),
    "qwen-plus" to (0.8 to 2.0),
    "qwen-max" to (2.4 to 9.6),
    "moonshot-v1-8k" to (12.0 to 12.0),
    "moonshot-v1-32k" to (24.0 to 24.0),
    "mimo" to (0.0 to 0.0)
)

private fun estimateCostCny(m: ModelUsage): Double {
    val price = PRICES.entries.firstOrNull { m.model.contains(it.key, ignoreCase = true) }?.value
        ?: return 0.0
    // 缓存命中按输入价的十分之一算 —— 各家折扣不同,这是个中位数量级
    val inCost = m.input / 1_000_000.0 * price.first
    val cacheCost = m.cacheRead / 1_000_000.0 * price.first * 0.1
    val outCost = m.output / 1_000_000.0 * price.second
    return inCost + cacheCost + outCost
}
