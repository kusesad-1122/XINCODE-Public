package com.xincode.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import kotlin.math.roundToInt
import kotlin.math.sin

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

// —— 像素场景固定配色(不随主题;专属像素观感)——
private val Bg = Color(0xFF1B1E2B)
private val Panel = Color(0xFF232739)
private val Cream = Color(0xFFF3EFE0)
private val Grey = Color(0xFF6B7089)
private val GreenC = Color(0xFF7BE0A4)
private val Amber = Color(0xFFF2C14E)
private val RedC = Color(0xFFE0685C)
private val BlueC = Color(0xFF6FB3E0)
private val Ink = Color(0xFF11131C)

/**
 * 「智能体指挥室」像素动画:中间主脑,下方每个子智能体一个像素小机器人,
 * 头上/脚下能看到它领的活与状态(准备/执行/完成/失败)。dispatch_agents 跑时实时更新。
 */
@Composable
fun SubAgentScene(state: SubAgentSceneState, onClose: () -> Unit) {
    if (!state.visible || state.workers.isEmpty()) return
    val workers = state.workers.toList()
    val ctx = LocalContext.current
    val assets = remember { PixelOfficeAssets.load(ctx) }
    // 点进某个子智能体看它「正在做什么」的详情。
    var selected by remember { mutableStateOf<Int?>(null) }

    val t = rememberInfiniteTransition(label = "scene")
    val phase by t.animateFloat(0f, (2f * Math.PI).toFloat(),
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase")
    val blink by t.animateFloat(1f, 0.15f,
        infiniteRepeatable(tween(540, easing = LinearEasing), RepeatMode.Reverse), label = "blink")
    val dash by t.animateFloat(0f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing)), label = "dash")

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.brainBusy) "▸ 智能体指挥室 · 指挥中" else "▸ 智能体指挥室 · 已汇总",
                fontSize = 11.sp, fontFamily = Mono, color = Cream)
            Text("×", fontSize = 14.sp, fontFamily = Mono, color = Grey,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() })
        }
        Spacer(Modifier.height(6.dp))

        // —— 画布:竖屏像素办公室(主脑在上,子智能体每行 2 个往下铺)——
        val n = workers.size
        val cols = if (n <= 1) 1 else 2
        val rows = (n + cols - 1) / cols
        val brainAreaDp = 82.dp
        val rowDp = 108.dp
        val canvasHeight = (brainAreaDp + rowDp * rows).coerceIn(180.dp, 360.dp)
        Canvas(Modifier.fillMaxWidth().height(canvasHeight)) {
            val w = size.width
            val h = size.height
            val brainX = w / 2f
            val brainAreaPx = brainAreaDp.toPx()
            val cellW = w / cols
            val gridTop = brainAreaPx
            val rowH = ((h - gridTop) / rows).coerceAtLeast(1f)
            fun cellCx(i: Int) = cellW * (i % cols) + cellW / 2f
            fun cellBaseY(i: Int) = gridTop + rowH * ((i / cols) + 1) - 10f

            if (assets.ok) {
                drawFloorTiles(assets.floor!!, w, h)
                val brainBaseY = brainAreaPx - 8f
                workers.forEachIndexed { i, wkr ->
                    drawFlowLine(Offset(brainX, brainAreaPx - 40f), Offset(cellCx(i), cellBaseY(i) - 52f),
                        statusColor(wkr.status).copy(alpha = 0.35f),
                        if (wkr.status == SubAgentSceneState.Status.RUNNING) dash else -1f)
                }
                drawSpriteWorkstation(assets, 0, brainX, brainBaseY, 2.0f,
                    if (state.brainBusy) SubAgentSceneState.Status.RUNNING else SubAgentSceneState.Status.DONE,
                    phase, blink, isBrain = true, busy = state.brainBusy)
                val s = (cellW / 40f).coerceIn(1.3f, 2.1f)
                workers.forEachIndexed { i, wkr ->
                    drawSpriteWorkstation(assets, (i + 1), cellCx(i), cellBaseY(i), s, wkr.status, phase + i, blink, isBrain = false, busy = false)
                }
            } else {
                // 回退:代码手绘(同样竖屏网格)
                val px = (cellW / 40f).coerceIn(2.2f, 3.6f)
                drawFloor(w, h)
                drawManagerDesk(brainX, 30f, 3.2f, phase, blink, state.brainBusy)
                workers.forEachIndexed { i, wkr ->
                    drawWorkstation(cellCx(i), cellBaseY(i), px, wkr.status, blink, phase, dash, i)
                }
            }
        }

        // —— 子智能体列表(竖排,可滚动):名字 · 状态 · 领的活,点击看实时详情 ——
        Column(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)
        ) {
            workers.forEachIndexed { i, wkr ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { selected = i }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor(wkr.status)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(wkr.agent, fontSize = 11.sp, fontFamily = Mono, color = Cream, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            Text(statusLabel(wkr.status), fontSize = 9.sp, fontFamily = Mono, color = statusColor(wkr.status))
                        }
                        Text(wkr.task, fontSize = 9.sp, fontFamily = Mono, color = Grey, maxLines = 2)
                    }
                    Text("›", fontSize = 14.sp, fontFamily = Mono, color = BlueC.copy(alpha = 0.8f))
                }
            }
        }
    }

    // —— 子智能体详情:实时读 state.workers,能看到它当前动作 / 最终结论 ——
    val sel = selected
    if (sel != null) {
        val wkr = state.workers.getOrNull(sel)
        if (wkr == null) { selected = null } else {
            AlertDialog(
                onDismissRequest = { selected = null },
                confirmButton = {
                    TextButton(onClick = { selected = null }) { Text("关闭", fontFamily = Mono, color = GreenC) }
                },
                title = {
                    Text("${wkr.agent} · ${statusLabel(wkr.status)}", fontSize = 13.sp, fontFamily = Mono, color = Cream)
                },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("领的活", fontSize = 10.sp, fontFamily = Mono, color = Amber)
                        Text(wkr.task, fontSize = 11.sp, fontFamily = Mono, color = Cream)
                        Spacer(Modifier.height(10.dp))
                        if (wkr.status == SubAgentSceneState.Status.RUNNING) {
                            Text("正在做(实时)", fontSize = 10.sp, fontFamily = Mono, color = GreenC)
                            Text(wkr.activity.ifBlank { "(启动中…)" }, fontSize = 11.sp, fontFamily = Mono, color = Grey, lineHeight = 15.sp)
                        } else {
                            Text("结论 / 产出", fontSize = 10.sp, fontFamily = Mono, color = BlueC)
                            Text(wkr.result.ifBlank { wkr.activity.ifBlank { "(无输出)" } },
                                fontSize = 11.sp, fontFamily = Mono, color = Cream, lineHeight = 15.sp)
                        }
                    }
                },
                containerColor = Panel
            )
        }
    }
}

/**
 * 「像素办公室」全屏场景:每个【已配置的子智能体】都有一个固定工位 + 像素小人。
 * 没派活时小人在下方休息室待着(打盹 Zzz);主脑派活后走到办公室自己的工位开始工作(打字)。
 * 铺满竖屏:上=办公室(主脑在顶 + 工位网格),下=休息室。
 */
@Composable
fun PixelOffice(
    agents: List<String>,
    liveByName: Map<String, SubAgentSceneState.Worker>,
    brainBusy: Boolean,
    modifier: Modifier
) {
    val ctx = LocalContext.current
    val assets = remember { PixelOfficeAssets.load(ctx) }
    val tr = rememberInfiniteTransition(label = "office")
    val phase by tr.animateFloat(0f, (2.0 * Math.PI).toFloat(),
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ph")
    val blink by tr.animateFloat(1f, 0.15f,
        infiniteRepeatable(tween(540, easing = LinearEasing), RepeatMode.Reverse), label = "bl")
    val dash by tr.animateFloat(0f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing)), label = "da")
    // 每个子智能体:被派活→走向工位(1),否则→休息室(0)。平滑过渡=走路动画。
    val progress = agents.map { name ->
        animateFloatAsState(
            if (liveByName[name] != null) 1f else 0f,
            tween(1600, easing = FastOutSlowInEasing), label = "pg"
        ).value
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val officeBottom = h * 0.60f
        if (assets.ok) {
            // 地砖上色:办公室暖木、休息室冷蓝,两个房间色调分明(仿图四)
            drawFloorRegion(assets.floor!!, w, 0f, officeBottom, Color(0xFFCBA678))
            drawFloorRegion(assets.floor!!, w, officeBottom, h, Color(0xFF6E7CA6))
        } else drawFloor(w, h)
        // 墙体:把办公室(上)和休息室(下)框成两个房间,中间留门洞
        val doorW = w * 0.26f
        drawWalls(w, h, officeBottom, doorW)
        // 家具装饰(墙上书架/时钟/白板/画 + 办公室绿植 + 休息室沙发/茶几/地毯)
        if (assets.ok) drawDecor(assets, w, h, officeBottom, (w / 170f).coerceIn(1.3f, 2.3f))

        // 主脑:顶部居中
        val brainCx = w / 2f; val brainBaseY = 74f
        if (assets.ok) drawSpriteWorkstation(assets, 0, brainCx, brainBaseY, 1.9f,
            if (brainBusy) SubAgentSceneState.Status.RUNNING else SubAgentSceneState.Status.DONE,
            phase, blink, isBrain = true, busy = brainBusy)
        else drawManagerDesk(brainCx, 34f, 3.0f, phase, blink, brainBusy)

        val n = agents.size.coerceAtLeast(1)
        val cols = 2
        val rows = (n + cols - 1) / cols
        // 工位从顶部往下【紧凑排布】(每行固定 ~112px,不再把两个桌子拉到整屏),更像真办公室
        val zoneTop = 118f
        val rowH = 112f
        fun deskCx(i: Int) = (w / cols) * (i % cols) + (w / cols) / 2f
        fun deskBaseY(i: Int) = (zoneTop + rowH * ((i / cols) + 1) - 8f).coerceAtMost(officeBottom - 14f)
        // 小人放大一点:scale 提高
        val s = ((w / cols) / 34f).coerceIn(1.7f, 2.7f)
        val bcols = 3
        fun breakCx(i: Int) = (w / bcols) * (i % bcols) + (w / bcols) / 2f
        fun breakBaseY(i: Int) = officeBottom + 46f + (i / bcols) * 54f
        val bs = s * 0.92f

        // 办公室工位(家具常驻:桌 + PC;在岗且运行中则 PC 开机)
        if (assets.ok) agents.indices.forEach { i ->
            val atDesk = progress[i] >= 0.9f
            val on = atDesk && liveByName[agents[i]]?.status == SubAgentSceneState.Status.RUNNING
            drawDeskOnly(assets, deskCx(i), deskBaseY(i), s, on, phase)
        }
        // 连线:主脑 → 在岗工位
        agents.indices.forEach { i ->
            val wkr = liveByName[agents[i]] ?: return@forEach
            drawFlowLine(Offset(brainCx, brainBaseY - 44f), Offset(deskCx(i), deskBaseY(i) - 42f),
                statusColor(wkr.status).copy(alpha = 0.3f),
                if (wkr.status == SubAgentSceneState.Status.RUNNING) dash else -1f)
        }
        // 角色:在岗(坐)/ 休息(打盹)/ 途中(走)
        if (assets.ok) agents.indices.forEach { i ->
            val sheet = assets.chars[(i + 1) % assets.chars.size]
            val p = progress[i]
            val wkr = liveByName[agents[i]]
            when {
                p >= 0.9f -> {
                    val frame = if (wkr?.status == SubAgentSceneState.Status.RUNNING) (if (blink < 0.5f) 0 else 1) else 0
                    drawCharAt(sheet, PixelOfficeAssets.DIR_UP, frame, deskCx(i), deskBaseY(i) - 15f * s, s)
                }
                p <= 0.1f -> {
                    drawCharAt(sheet, PixelOfficeAssets.DIR_DOWN, 0, breakCx(i), breakBaseY(i), bs)
                    drawZzz(breakCx(i) + 7f * bs, breakBaseY(i) - 28f * bs, bs, phase + i)
                }
                else -> {
                    // 途中:从休息室先走到门洞(w/2, officeBottom),再从门洞走到工位 —— 穿过隔断门。
                    val doorX = w / 2f; val doorY = officeBottom
                    val cx: Float; val fy: Float
                    if (p < 0.5f) {
                        val u = p * 2f
                        cx = breakCx(i) + (doorX - breakCx(i)) * u
                        fy = breakBaseY(i) + (doorY - breakBaseY(i)) * u
                    } else {
                        val u = (p - 0.5f) * 2f
                        cx = doorX + (deskCx(i) - doorX) * u
                        fy = doorY + ((deskBaseY(i) - 15f * s) - doorY) * u
                    }
                    drawCharAt(sheet, PixelOfficeAssets.DIR_UP, if (blink < 0.5f) 0 else 1, cx, fy, s)
                }
            }
        }
    }
}

/** 像素墙:外框 + 办公室/休息室之间的隔断墙(中间留门洞),把场景框成两个房间。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWalls(w: Float, h: Float, officeBottom: Float, doorW: Float) {
    val face = Color(0xFF2E2A44)
    val cap = Color(0xFF45406A)
    val edge = Color(0xFF1B1830)
    val t = 10f
    // 顶墙
    drawRect(face, Offset(0f, 0f), Size(w, 18f))
    drawRect(cap, Offset(0f, 0f), Size(w, 5f))
    drawRect(edge, Offset(0f, 18f), Size(w, 2f))
    // 左右墙
    drawRect(face, Offset(0f, 0f), Size(t, h))
    drawRect(face, Offset(w - t, 0f), Size(t, h))
    drawRect(cap, Offset(0f, 0f), Size(4f, h))
    drawRect(cap, Offset(w - 4f, 0f), Size(4f, h))
    // 底墙
    drawRect(face, Offset(0f, h - 10f), Size(w, 10f))
    // 隔断墙(带门洞)
    val dl = w / 2f - doorW / 2f
    val dr = w / 2f + doorW / 2f
    val dy = officeBottom - 7f
    drawRect(face, Offset(0f, dy), Size(dl, 14f))
    drawRect(face, Offset(dr, dy), Size(w - dr, 14f))
    drawRect(cap, Offset(0f, dy), Size(dl, 4f))
    drawRect(cap, Offset(dr, dy), Size(w - dr, 4f))
    // 门洞两侧门柱
    drawRect(edge, Offset(dl - 2f, dy - 2f), Size(3f, 16f))
    drawRect(edge, Offset(dr, dy - 2f), Size(3f, 16f))
}

/** 铺家具装饰:墙上(书架/时钟/白板/画)、办公室绿植、休息室沙发/茶几/地毯 —— 让场景不空。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDecor(
    a: PixelOfficeAssets.Loaded, w: Float, h: Float, officeBottom: Float, ds: Float
) {
    // 脚底对齐放置(站地面的物件)
    fun place(name: String, cx: Float, feetY: Float, k: Float = 1f) {
        val img = a.d(name) ?: return
        val dw = img.width * ds * k; val dh = img.height * ds * k
        blit(img, 0, 0, img.width, img.height, cx - dw / 2f, feetY - dh, dw, dh)
    }
    // 顶边对齐放置(挂墙物件:时钟/画/白板/书架)
    fun wall(name: String, cx: Float, topY: Float, k: Float = 1f) {
        val img = a.d(name) ?: return
        val dw = img.width * ds * k; val dh = img.height * ds * k
        blit(img, 0, 0, img.width, img.height, cx - dw / 2f, topY, dw, dh)
    }

    // —— 办公室顶墙一排(靠墙):书架 · 白板 · 时钟 · 书架 ——
    val topY = 20f
    wall("DOUBLE_BOOKSHELF", w * 0.16f, topY)
    wall("WHITEBOARD", w * 0.38f, topY)
    wall("CLOCK", w * 0.62f, topY - 4f)
    wall("BOOKSHELF", w * 0.84f, topY + 2f)
    // —— 办公室角落绿植 ——
    place("LARGE_PLANT", w * 0.10f, officeBottom - 12f)
    place("PLANT", w * 0.90f, officeBottom - 12f)
    place("CACTUS", w * 0.90f, officeBottom - 48f, 0.9f)

    // —— 休息室:沙发 + 茶几 + 挂画 + 绿植 + 仙人掌 + 垃圾桶(不用拉伸地毯,避免糊成一片)——
    place("SMALL_PAINTING_2", w * 0.28f, officeBottom + 30f)
    place("SMALL_PAINTING_2", w * 0.72f, officeBottom + 30f)
    val restFeet = h - 14f
    place("SOFA_FRONT", w * 0.20f, restFeet - 6f, 1.2f)
    place("COFFEE_TABLE", w * 0.44f, restFeet, 0.95f)
    place("PLANT", w * 0.66f, restFeet, 0.9f)
    place("CACTUS", w * 0.82f, restFeet, 0.9f)
    place("BIN", w * 0.93f, restFeet, 0.85f)
}

/** 只画一张工位(桌 + PC),不含角色。on=PC 开机(执行中循环开机帧)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDeskOnly(
    a: PixelOfficeAssets.Loaded, cx: Float, baseY: Float, s: Float, on: Boolean, phase: Float
) {
    val deskSide = 22f * s
    val pcW = 11f * s; val pcH = 22f * s
    val deskTop = baseY - deskSide
    val pcTop = deskTop + 7f * s - pcH
    a.desk?.let { blit(it, 0, 0, 32, 32, cx - deskSide / 2f, deskTop, deskSide, deskSide) }
    val twoPi = (2.0 * Math.PI).toFloat()
    val pc = if (on && a.pcOn.isNotEmpty())
        a.pcOn[((phase / twoPi) * a.pcOn.size).toInt().coerceIn(0, a.pcOn.size - 1)]
    else (a.pcOff ?: a.pcOn.firstOrNull())
    pc?.let { blit(it, 0, 0, 16, 32, cx - pcW / 2f, pcTop, pcW, pcH) }
}

/** 独立画一个角色(按脚底 [feetY] 对齐)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCharAt(
    sheet: ImageBitmap, dirRow: Int, frameCol: Int, cx: Float, feetY: Float, s: Float, alpha: Float = 1f
) {
    val cw = 13f * s; val ch = 26f * s
    blit(sheet, frameCol * 16, dirRow * 32, 16, 32, cx - cw / 2f, feetY - ch, cw, ch, alpha)
}

/** 打盹 Zzz。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawZzz(cx: Float, topY: Float, s: Float, phase: Float) {
    val a = ((sin(phase) + 1f) / 2f) * 0.6f + 0.3f
    for (k in 0..2) {
        val sz = (3f - k * 0.7f).coerceAtLeast(1f) * s * 0.6f
        drawRect(Cream.copy(alpha = a), Offset(cx + k * 4f * s, topY - k * 5f * s), Size(sz, sz))
    }
}

private fun statusColor(s: SubAgentSceneState.Status) = when (s) {
    SubAgentSceneState.Status.PREPARING -> Grey
    SubAgentSceneState.Status.RUNNING -> GreenC
    SubAgentSceneState.Status.DONE -> BlueC
    SubAgentSceneState.Status.FAILED -> RedC
}

private fun statusLabel(s: SubAgentSceneState.Status) = when (s) {
    SubAgentSceneState.Status.PREPARING -> "○ 准备"
    SubAgentSceneState.Status.RUNNING -> "◐ 执行中"
    SubAgentSceneState.Status.DONE -> "● 完成"
    SubAgentSceneState.Status.FAILED -> "✗ 失败"
}

// —— 像素绘制助手 ——

/** 在 (cx,cy) 居中画一个 [gw]x[gh] 网格的像素块;p=像素边长。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.px(cx: Float, cy: Float, gx: Int, gy: Int, p: Float, color: Color, gw: Int, gh: Int) {
    val ox = cx - gw * p / 2f
    val oy = cy - gh * p / 2f
    drawRect(color, Offset(ox + gx * p, oy + gy * p), Size(p, p))
}

// ═══════════ 真 sprite 渲染(pixel-agents 素材)═══════════

/** 低层:把 sprite 的一块区域按最近邻(不糊)画到目标矩形。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.blit(
    img: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float,
    alpha: Float = 1f, tint: ColorFilter? = null
) {
    drawImage(
        image = img,
        srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
        dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        alpha = alpha, filterQuality = FilterQuality.None, colorFilter = tint
    )
}

/**
 * 在 Y 区间 [y0,y1) 平铺地砖并【上色】(pixel-agents 地砖是灰度模板,用 Modulate 相乘上色):
 * 办公室给暖木色、休息室给冷蓝色,两个房间色调分明(仿图四)。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloorRegion(
    floor: ImageBitmap, w: Float, y0: Float, y1: Float, tintColor: Color
) {
    val ts = 24f
    val tint = ColorFilter.tint(tintColor, BlendMode.Modulate)
    var y = y0
    while (y < y1) {
        var x = 0f
        while (x < w) { blit(floor, 0, 0, 16, 16, x, y, ts, ts, tint = tint); x += ts }
        y += ts
    }
}

/** 兼容旧调用:整块平铺(不上色)。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloorTiles(floor: ImageBitmap, w: Float, h: Float) {
    drawFloorRegion(floor, w, 0f, h, Color.White)
}

/** 一个工位:角色(坐姿)+ 桌 + PC(执行中循环开机帧/闪屏),底部对齐 [baseY]。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpriteWorkstation(
    a: PixelOfficeAssets.Loaded, charIdx: Int, cx: Float, baseY: Float, s: Float,
    status: SubAgentSceneState.Status, phase: Float, blink: Float, isBrain: Boolean, busy: Boolean
) {
    val running = status == SubAgentSceneState.Status.RUNNING
    val char = a.chars[charIdx % a.chars.size]
    val deskSide = 22f * s
    val pcW = 11f * s; val pcH = 22f * s
    val charW = 13f * s; val charH = 26f * s
    val deskTop = baseY - deskSide
    val pcBottom = deskTop + 7f * s
    val pcTop = pcBottom - pcH
    val charBottom = pcTop + 13f * s
    val charTop = charBottom - charH
    val twoPi = (2.0 * Math.PI).toFloat()

    if (busy) {
        val r = deskSide * 0.9f + (sin(phase) + 1f) * 4f
        drawCircle(Amber.copy(alpha = 0.08f + 0.08f * ((sin(phase) + 1f) / 2f)), r, Offset(cx, charTop + charH * 0.4f))
    }
    // 角色:主脑面向房间(down),子智能体坐着面向桌子(up)
    val dirRow = if (isBrain) PixelOfficeAssets.DIR_DOWN else PixelOfficeAssets.DIR_UP
    val col = if (running) (if (blink < 0.5f) 0 else 1) else 0
    blit(char, col * 16, dirRow * 32, 16, 32, cx - charW / 2f, charTop, charW, charH)
    // 主脑皇冠
    if (isBrain) {
        val cwv = 2.4f * s
        listOf(-1f, 0f, 1f).forEach { drawRect(Amber, Offset(cx + it * cwv - cwv / 2f, charTop - cwv * 1.2f), Size(cwv, cwv)) }
    }
    // 桌子
    a.desk?.let { blit(it, 0, 0, 32, 32, cx - deskSide / 2f, deskTop, deskSide, deskSide) }
    // PC:执行中循环开机帧,否则关机帧
    val pc = if (running && a.pcOn.isNotEmpty())
        a.pcOn[((phase / twoPi) * a.pcOn.size).toInt().coerceIn(0, a.pcOn.size - 1)]
    else (a.pcOff ?: a.pcOn.firstOrNull())
    pc?.let { blit(it, 0, 0, 16, 32, cx - pcW / 2f, pcTop, pcW, pcH) }
    // 状态标记
    when (status) {
        SubAgentSceneState.Status.PREPARING -> {
            val bx = cx + charW * 0.4f; val by = charTop - 10f * s
            drawRoundRect(Cream, Offset(bx, by), Size(15f * s, 9f * s),
                androidx.compose.ui.geometry.CornerRadius(3f * s, 3f * s))
            for (d in 0..2) {
                val al = ((sin(phase * 2f + d) + 1f) / 2f) * 0.8f + 0.2f
                drawCircle(Grey.copy(alpha = al), 1.1f * s, Offset(bx + (3f + d * 4f) * s, by + 4.5f * s))
            }
        }
        SubAgentSceneState.Status.DONE -> drawCircle(BlueC, 2.2f * s, Offset(cx + charW * 0.5f, charTop + 2f))
        SubAgentSceneState.Status.FAILED -> drawCircle(RedC, 2.2f * s, Offset(cx + charW * 0.5f, charTop + 2f))
        else -> {}
    }
}

/** 像素办公室地板:棋盘格 + 顶部一条墙裙,营造"房间"感。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloor(w: Float, h: Float) {
    val tile = 20f
    val floorA = Color(0xFF20233A)
    val floorB = Color(0xFF262A44)
    val wall = Color(0xFF2E3350)
    var y = 0f
    var row = 0
    while (y < h) {
        var x = 0f; var col = 0
        while (x < w) {
            drawRect(if ((row + col) % 2 == 0) floorA else floorB, Offset(x, y), Size(tile, tile))
            x += tile; col++
        }
        y += tile; row++
    }
    // 顶部墙裙(深一点)+ 踢脚线
    drawRect(wall, Offset(0f, 0f), Size(w, 16f))
    drawRect(GreenC.copy(alpha = 0.25f), Offset(0f, 16f), Size(w, 2f))
}

/** 子智能体工位:桌子 + 显示器(屏色随状态,执行中闪光标/扫描线)+ 小人 + 准备时等待气泡。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWorkstation(
    cx: Float, deskY: Float, p: Float, status: SubAgentSceneState.Status, blink: Float, phase: Float, dash: Float, idx: Int
) {
    val col = statusColor(status)
    // 小人(坐在桌后,轻微上下浮动)
    val bob = (sin(phase + idx.toFloat()) * 1.6f)
    drawBot(cx, deskY - 30f + bob, p * 0.9f, status, blink)
    // 桌面(木色梯形近似:一块深板 + 高光边)
    val deskW = 46f; val deskH = 12f
    drawRect(Color(0xFF4A3B2E), Offset(cx - deskW / 2f, deskY), Size(deskW, deskH))
    drawRect(Color(0xFF5E4B39), Offset(cx - deskW / 2f, deskY), Size(deskW, 3f))
    // 桌腿
    drawRect(Color(0xFF3A2F25), Offset(cx - deskW / 2f + 2f, deskY + deskH), Size(3f, 8f))
    drawRect(Color(0xFF3A2F25), Offset(cx + deskW / 2f - 5f, deskY + deskH), Size(3f, 8f))
    // 显示器(屏幕颜色随状态)
    val mw = 22f; val mh = 15f
    val mx = cx - mw / 2f; val my = deskY - mh - 1f
    drawRect(Color(0xFF15171F), Offset(mx - 2f, my - 2f), Size(mw + 4f, mh + 4f)) // 外壳
    val screen = if (status == SubAgentSceneState.Status.RUNNING) col.copy(alpha = 0.85f) else col.copy(alpha = 0.55f)
    drawRect(screen, Offset(mx, my), Size(mw, mh))
    // 屏幕内容:执行中=闪动的代码行 + 光标;完成=对勾;失败=叉
    when (status) {
        SubAgentSceneState.Status.RUNNING -> {
            val lines = 3
            for (l in 0 until lines) {
                val ly = my + 3f + l * 4f
                val lw = mw * (0.4f + 0.5f * ((sin(phase * 1.7f + l) + 1f) / 2f))
                drawRect(Ink.copy(alpha = 0.7f), Offset(mx + 2f, ly), Size(lw.coerceAtMost(mw - 4f), 1.6f))
            }
            // 光标闪
            drawRect(Ink.copy(alpha = blink), Offset(mx + 3f, my + mh - 4f), Size(2f, 2.4f))
        }
        SubAgentSceneState.Status.DONE -> {
            drawLine(Ink, Offset(mx + 6f, my + 8f), Offset(mx + 9f, my + 11f), strokeWidth = 2f)
            drawLine(Ink, Offset(mx + 9f, my + 11f), Offset(mx + 15f, my + 4f), strokeWidth = 2f)
        }
        SubAgentSceneState.Status.FAILED -> {
            drawLine(Ink, Offset(mx + 6f, my + 4f), Offset(mx + 15f, my + 11f), strokeWidth = 2f)
            drawLine(Ink, Offset(mx + 15f, my + 4f), Offset(mx + 6f, my + 11f), strokeWidth = 2f)
        }
        SubAgentSceneState.Status.PREPARING -> {
            // 等待气泡「…」浮在头顶
            val bx = cx + 12f; val by = deskY - 46f
            drawRoundRect(Cream, Offset(bx, by), Size(20f, 12f),
                androidx.compose.ui.geometry.CornerRadius(4f, 4f))
            drawRect(Cream, Offset(bx + 3f, by + 11f), Size(4f, 4f)) // 小尾巴
            for (d in 0..2) {
                val a = ((sin(phase * 2f + d) + 1f) / 2f) * 0.8f + 0.2f
                drawCircle(Grey.copy(alpha = a), 1.4f, Offset(bx + 5f + d * 5f, by + 6f))
            }
        }
    }
}

/** 主脑:经理桌 + "指挥官"小人(皇冠),忙碌脉冲。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawManagerDesk(cx: Float, cy: Float, p: Float, phase: Float, blink: Float, busy: Boolean) {
    drawCommander(cx, cy, p, phase, blink, busy)
    // 经理办公桌(比工位更宽,深色)
    val deskW = 64f; val deskH = 12f; val dy = cy + 22f
    drawRect(Color(0xFF3E3350), Offset(cx - deskW / 2f, dy), Size(deskW, deskH))
    drawRect(Amber.copy(alpha = 0.6f), Offset(cx - deskW / 2f, dy), Size(deskW, 2.4f))
    drawRect(Color(0xFF2C2440), Offset(cx - deskW / 2f + 3f, dy + deskH), Size(3f, 8f))
    drawRect(Color(0xFF2C2440), Offset(cx + deskW / 2f - 6f, dy + deskH), Size(3f, 8f))
    // 桌上铭牌
    drawRect(Ink, Offset(cx - 12f, dy + 3f), Size(24f, 5f))
    drawRect(Amber, Offset(cx - 11f, dy + 4f), Size(22f, 1.5f))
}

/** 主脑:像素"指挥官"——皇冠 + 方脸 + 眼睛,忙碌时脉冲光环。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCommander(cx: Float, cy: Float, p: Float, phase: Float, blink: Float, busy: Boolean) {
    val gw = 9; val gh = 8
    // 光环(忙碌脉冲)
    if (busy) {
        val r = 22f + (sin(phase) + 1f) * 6f
        drawCircle(Amber.copy(alpha = 0.10f + 0.10f * ((sin(phase) + 1f) / 2f)), r, Offset(cx, cy + p))
    }
    // 皇冠(amber)
    intArrayOf(2, 4, 6).forEach { px(cx, cy, it, 0, p, Amber, gw, gh) }
    px(cx, cy, 3, 1, p, Amber, gw, gh); px(cx, cy, 5, 1, p, Amber, gw, gh)
    // 脸(cream 2..6 x 2..6)
    for (x in 2..6) for (y in 2..6) px(cx, cy, x, y, p, Cream, gw, gh)
    // 眼睛(眨)
    val eye = Ink.copy(alpha = blink)
    px(cx, cy, 3, 3, p, eye, gw, gh); px(cx, cy, 5, 3, p, eye, gw, gh)
    // 嘴
    px(cx, cy, 4, 5, p, Ink, gw, gh)
    // 底座
    for (x in 3..5) px(cx, cy, x, 7, p, Grey, gw, gh)
}

/** 子智能体像素小机器人;头的颜色随状态。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBot(cx: Float, cy: Float, p: Float, status: SubAgentSceneState.Status, blink: Float) {
    val gw = 7; val gh = 8
    val body = statusColor(status)
    // 天线
    px(cx, cy, 3, 0, p, body, gw, gh)
    // 头(1..5 x 1..3)
    for (x in 1..5) for (y in 1..3) px(cx, cy, x, y, p, body, gw, gh)
    // 眼睛(RUNNING 时眨,其它常亮)
    val eyeAlpha = if (status == SubAgentSceneState.Status.RUNNING) blink else 1f
    px(cx, cy, 2, 2, p, Ink.copy(alpha = eyeAlpha), gw, gh)
    px(cx, cy, 4, 2, p, Ink.copy(alpha = eyeAlpha), gw, gh)
    // 身子(1..5 x 4..5,浅一点)
    val torso = body.copy(alpha = 0.55f)
    for (x in 1..5) for (y in 4..5) px(cx, cy, x, y, p, torso, gw, gh)
    // 状态徽记(胸口)
    val badge = when (status) {
        SubAgentSceneState.Status.DONE -> Cream
        SubAgentSceneState.Status.FAILED -> RedC
        else -> Ink
    }
    px(cx, cy, 3, 4, p, badge, gw, gh)
    // 腿
    px(cx, cy, 2, 6, p, Grey, gw, gh); px(cx, cy, 4, 6, p, Grey, gw, gh)
}

/** 主脑到 worker 的连线;dash>=0 时画流动虚线(执行中),否则静态细线。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlowLine(a: Offset, b: Offset, color: Color, dash: Float) {
    if (dash < 0f) {
        drawLine(color.copy(alpha = 0.35f), a, b, strokeWidth = 2f)
        return
    }
    val segs = 12
    for (i in 0 until segs) {
        val f0 = ((i + dash) % segs) / segs
        if (i % 2 == 0) continue
        val p0 = Offset(a.x + (b.x - a.x) * f0, a.y + (b.y - a.y) * f0)
        val f1 = (f0 + 1f / segs).coerceAtMost(1f)
        val p1 = Offset(a.x + (b.x - a.x) * f1, a.y + (b.y - a.y) * f1)
        drawLine(color, p0, p1, strokeWidth = 3f)
    }
}
