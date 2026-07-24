package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
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

private val GBg = Color(0xFFF9F9F6)
private val GInk = Color(0xFF1A1A17)
private val GSub = Color(0xFF86857B)
private val GGreen = Color(0xFF6E8050)
private val GRed = Color(0xFFA8514A)
private val GBorder = Color(0xFFE6E4DC)
private val GMono = FontFamily(Font(com.xincode.app.R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Goal-driven execution screen.
 * Shows: goal input, goal text, current round status, judge evaluations timeline, result.
 */
@Composable
fun GoalScreen(
    goalRunner: GoalRunner,
    onBack: () -> Unit
) {
    val state by goalRunner.state.collectAsState()
    val judgeLog by goalRunner.judgeLog.collectAsState()
    var goalInput by remember { mutableStateOf("") }
    val isRunning = state is GoalRunner.GoalState.Running

    Column(Modifier.fillMaxSize().background(GBg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(GInk).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, "back", tint = GBg)
            }
            Text(
                "Goal",
                color = GBg, fontSize = 14.sp, fontFamily = GMono,
                modifier = Modifier.weight(1f)
            )
            if (isRunning) {
                IconButton(onClick = { goalRunner.stop() }) {
                    Icon(Icons.Outlined.Stop, "stop", tint = GBg)
                }
            }
        }

        // Input row — only visible when idle
        if (state is GoalRunner.GoalState.Idle || state is GoalRunner.GoalState.Error) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("❯", fontSize = 14.sp, fontFamily = GMono, color = GGreen)
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = GMono),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GBg,
                        unfocusedContainerColor = GBg,
                        focusedIndicatorColor = GInk,
                        unfocusedIndicatorColor = GBorder,
                        cursorColor = GInk,
                        focusedTextColor = GInk,
                        unfocusedTextColor = GInk
                    ),
                    placeholder = {
                        Text("输入目标，如: 列出 /sdcard 下所有文件夹",
                            fontFamily = GMono, fontSize = 12.sp, color = GSub)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "[run]",
                    fontSize = 13.sp, fontFamily = GMono,
                    color = if (goalInput.isBlank()) GSub else GInk,
                    modifier = Modifier.clickable(enabled = goalInput.isNotBlank()) {
                        goalRunner.run(goal = goalInput.trim(), scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
                        // Don't clear input — keep it visible as the goal description
                    }
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(GBorder))
        }

        // Active goal description
        val currentGoal = when (val s = state) {
            is GoalRunner.GoalState.Running -> s.goal
            is GoalRunner.GoalState.Achieved -> s.goal
            is GoalRunner.GoalState.Failed -> s.goal
            is GoalRunner.GoalState.Error -> s.goal
            else -> null
        }
        if (currentGoal != null) {
            Text(
                "目标: $currentGoal",
                fontSize = 12.sp, fontFamily = GMono, color = GInk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 3
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(GBorder))
        }

        // Status bar
        val statusText = when (val s = state) {
            is GoalRunner.GoalState.Idle -> "等待目标输入"
            is GoalRunner.GoalState.Running -> "${s.status} (${s.round}/${s.maxRounds})"
            is GoalRunner.GoalState.Achieved -> "✓ 达成 (第${s.round}轮): ${s.verdict.take(80)}"
            is GoalRunner.GoalState.Failed -> "✗ 未达成 (第${s.round}轮): ${s.reason.take(80)}"
            is GoalRunner.GoalState.Error -> "✗ 错误: ${s.message.take(80)}"
        }
        val statusColor = when (state) {
            is GoalRunner.GoalState.Achieved -> GGreen
            is GoalRunner.GoalState.Failed, is GoalRunner.GoalState.Error -> GRed
            else -> GSub
        }
        Text(
            statusText,
            fontSize = 12.sp, fontFamily = GMono, color = statusColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(GBorder))

        // Judge evaluations timeline
        if (judgeLog.isNotEmpty()) {
            Text(
                "裁判评估",
                fontSize = 11.sp, fontFamily = GMono, color = GSub,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(judgeLog) { _, event ->
                    JudgeCard(event)
                }
            }
        }
    }
}

@Composable
private fun JudgeCard(event: GoalRunner.JudgeEvent) {
    val bgColor = if (event.achieved) GGreen.copy(alpha = 0.06f) else GRed.copy(alpha = 0.06f)
    val dotColor = if (event.achieved) GGreen else GRed
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(8.dp).offset(y = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "第${event.round}轮: ${event.verdict} (${(event.confidence * 100).toInt()}%)",
                fontSize = 13.sp, fontFamily = GMono,
                color = GInk, lineHeight = 18.sp
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    event.explanation,
                    fontSize = 12.sp, fontFamily = GMono,
                    color = GSub, lineHeight = 16.sp
                )
            }
        }
    }
}