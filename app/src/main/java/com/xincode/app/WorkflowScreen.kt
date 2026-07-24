package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.core.AgentCore
import com.xincode.core.AgentState
import kotlinx.coroutines.flow.collectLatest

/**
 * Workflow real-time view: status bar + timeline + interrupt button.
 */
@Composable
fun WorkflowScreen(
    agentCore: AgentCore,
    workflowState: WorkflowState,
    onBack: () -> Unit,
    onNavigateToReplay: () -> Unit = {}
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    val currentState by workflowState.currentState.collectAsState()
    val events by workflowState.events.collectAsState()
    val isRunning = currentState.isBusy

    // Elapsed timer
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (true) {
                elapsed = (System.currentTimeMillis() - (events.firstOrNull()?.timestamp ?: System.currentTimeMillis()))
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(WfPalette.bg)) {

        // --- Top status bar ---
        StatusBar(
            state = currentState,
            elapsed = elapsed,
            isRunning = isRunning,
            onBack = onBack,
            onStop = { agentCore.stop() },
            onReplay = onNavigateToReplay
        )

        // --- Timeline ---
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(events) { event ->
                TimelineCard(event)
            }
        }
    }
}

@Composable
private fun StatusBar(
    state: AgentState,
    elapsed: Long,
    isRunning: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onReplay: (() -> Unit)? = null
) {
    val statusColor = when (state) {
        is AgentState.Idle -> WfPalette.green
        is AgentState.Thinking -> WfPalette.black
        is AgentState.CallingTool, is AgentState.Executing -> WfPalette.black
        is AgentState.WaitingConfirm -> WfPalette.black
        is AgentState.Responding -> WfPalette.green
        is AgentState.Error -> WfPalette.red
        is AgentState.Interrupted -> WfPalette.gray
    }

    val statusText = when (state) {
        is AgentState.Idle -> "空闲"
        is AgentState.Thinking -> "思考中 第${state.iteration}轮"
        is AgentState.CallingTool -> "调用工具: ${state.toolName}"
        is AgentState.WaitingConfirm -> "等待确认"
        is AgentState.Executing -> "执行: ${state.toolName}"
        is AgentState.Responding -> "回复完成"
        is AgentState.Error -> "✗ ${state.message.take(30)}"
        is AgentState.Interrupted -> "已中断"
    }

    val elapsedSec = elapsed / 1000

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(statusColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, "back", tint = WfPalette.bg)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(statusText, color = WfPalette.bg, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Text(
                "${elapsedSec}s",
                color = WfPalette.bg.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (isRunning) {
            IconButton(onClick = onStop) {
                Icon(Icons.Outlined.Stop, "stop", tint = WfPalette.bg)
            }
        }
        if (onReplay != null && !isRunning) {
            Text(
                "replay",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = WfPalette.bg,
                modifier = Modifier.clickable { onReplay() }.padding(4.dp)
            )
        }
    }
}

@Composable
private fun TimelineCard(event: WorkflowState.TimelineEvent) {
    val (iconColor, bgColor) = when (event.type) {
        WorkflowState.EventType.THINKING -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.08f)
        WorkflowState.EventType.CALLING_TOOL -> WfPalette.black to WfPalette.black.copy(alpha = 0.06f)
        WorkflowState.EventType.WAITING_CONFIRM -> WfPalette.black to WfPalette.black.copy(alpha = 0.06f)
        WorkflowState.EventType.EXECUTING -> WfPalette.black to WfPalette.black.copy(alpha = 0.08f)
        WorkflowState.EventType.RESPONDING -> WfPalette.green to WfPalette.green.copy(alpha = 0.06f)
        WorkflowState.EventType.ERROR -> WfPalette.red to WfPalette.red.copy(alpha = 0.08f)
        WorkflowState.EventType.INTERRUPTED -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.06f)
        WorkflowState.EventType.IDLE -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.04f)
    }

    var expanded by remember { mutableStateOf(false) }
    val hasDetail = event.detail.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(enabled = hasDetail) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .offset(y = 4.dp)
                .clip(CircleShape)
                .background(iconColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.label,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = WfPalette.black,
                lineHeight = 18.sp
            )
            if (expanded && hasDetail) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    event.detail,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WfPalette.gray,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // Timestamp
        Text(
            formatTime(event.timestamp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = WfPalette.gray.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
        )
    }
}

private fun formatTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return sdf.format(java.util.Date(ts))
}

/** Legacy shim — kept as a Composable-scoped accessor so existing `.bg/.black/.gray/.green/.red`
 *  references keep compiling while sourcing from the live theme. Prefer [LocalXinColors]
 *  directly in new code. */
internal object WfPalette {
    val bg: androidx.compose.ui.graphics.Color @Composable get() = LocalXinColors.current.bg
    val black: androidx.compose.ui.graphics.Color @Composable get() = LocalXinColors.current.ink
    val gray: androidx.compose.ui.graphics.Color @Composable get() = LocalXinColors.current.sub
    val green: androidx.compose.ui.graphics.Color @Composable get() = LocalXinColors.current.green
    val red: androidx.compose.ui.graphics.Color @Composable get() = LocalXinColors.current.red
}