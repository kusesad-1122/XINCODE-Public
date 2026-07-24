package com.xincode.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.TrajectoryEntity
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * Replay view: loads a trajectory from Room and animates the timeline step by step.
 */
@Composable
fun ReplayScreen(
    trajectory: TrajectoryEntity,
    onBack: () -> Unit
) {
    val events = remember(trajectory) {
        val arr = JSONArray(trajectory.eventsJson)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ReplayEvent(
                type = obj.getString("type"),
                label = obj.getString("label"),
                detail = obj.optString("detail", ""),
                iteration = obj.optInt("iteration", 0)
            )
        }
    }

    var currentStep by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) } // steps per tick

    // Auto-play
    LaunchedEffect(playing, currentStep) {
        if (playing && currentStep < events.size - 1) {
            delay((300 / speed).toLong())
            currentStep++
        } else if (currentStep >= events.size - 1) {
            playing = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(WfPalette.bg)) {

        // Top bar
        Row(
            Modifier.fillMaxWidth().background(WfPalette.black).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "back", tint = WfPalette.bg) }
            Text(
                "Replay · ${events.size} steps",
                color = WfPalette.bg, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            // Play/Pause
            IconButton(onClick = { playing = !playing }) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "play", tint = WfPalette.bg
                )
            }
            // Skip to next
            IconButton(onClick = {
                if (currentStep < events.size - 1) currentStep++
            }) {
                Icon(Icons.Outlined.SkipNext, "skip", tint = WfPalette.bg)
            }
        }

        // Speed control
        Row(
            Modifier.fillMaxWidth().background(WfPalette.black.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Speed:", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = WfPalette.gray)
            listOf(0.5f, 1f, 2f, 4f).forEach { s ->
                val active = s == speed
                Text(
                    "${s}x",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (active) WfPalette.black else WfPalette.gray,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) WfPalette.black.copy(alpha = 0.1f) else WfPalette.bg)
                        .clickable { speed = s }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${currentStep + 1}/${events.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = WfPalette.gray)
        }

        // Timeline
        LazyColumn(
            Modifier.fillMaxSize().animateContentSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(events) { idx, event ->
                val revealed = idx <= currentStep
                TimelineStep(event, idx, revealed)
            }
        }
    }
}

data class ReplayEvent(
    val type: String,
    val label: String,
    val detail: String,
    val iteration: Int
)

@Composable
private fun TimelineStep(event: ReplayEvent, step: Int, revealed: Boolean) {
    val colors = when (event.type) {
        "THINKING" -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.06f)
        "CALLING_TOOL" -> WfPalette.black to WfPalette.black.copy(alpha = 0.04f)
        "EXECUTING" -> WfPalette.black to WfPalette.black.copy(alpha = 0.06f)
        "RESPONDING" -> WfPalette.green to WfPalette.green.copy(alpha = 0.04f)
        "ERROR" -> WfPalette.red to WfPalette.red.copy(alpha = 0.06f)
        "INTERRUPTED" -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.04f)
        else -> WfPalette.gray to WfPalette.gray.copy(alpha = 0.04f)
    }

    var expanded by remember { mutableStateOf(false) }
    val hasDetail = event.detail.isNotBlank()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (revealed) colors.second else WfPalette.bg)
            .animateContentSize()
            .clickable(enabled = revealed && hasDetail) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(8.dp)
                .offset(y = 4.dp)
                .clip(CircleShape)
                .background(if (revealed) colors.first else WfPalette.gray.copy(alpha = 0.2f))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (revealed) {
                Text(
                    event.label,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    color = WfPalette.black, lineHeight = 18.sp
                )
                if (expanded && hasDetail) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        event.detail,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = WfPalette.gray, lineHeight = 16.sp
                    )
                }
            } else {
                Text(
                    "Step ${step + 1}",
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    color = WfPalette.gray.copy(alpha = 0.3f)
                )
            }
        }
    }
}