package com.xincode.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val JetBrainsMono = XinUiFont

/**
 * Floating live-plan card. Rendered above the message list in ChatScreen.
 *
 * Design goals:
 *  - Immediately show the model's commitment: "I plan to do these N steps"
 *  - Visibly tick when each step completes — this is the "productive-work" signal
 *  - Collapsible so once the plan is >5 steps deep the user can fold it down
 *  - Animates step transitions with spring, not tween, so it feels alive
 */
@Composable
fun PlanCard(planState: PlanState, modifier: Modifier = Modifier) {
    val xc = LocalXinColors.current
    val visible = planState.visible && planState.steps.isNotEmpty()
    var expanded by remember(planState.title) { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        modifier = modifier
    ) {
        val done = planState.doneCount()
        val total = planState.totalCount()
        val ratio = if (total == 0) 0f else done.toFloat() / total.toFloat()
        val progress by animateFloatAsState(
            targetValue = ratio,
            animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.75f),
            label = "planProgress"
        )
        val arrowRot by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "planArrow"
        )
        // Subtle pulse when the plan just updated
        val pulse = rememberInfiniteTransition(label = "planPulse")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "planPulseAlpha"
        )
        val hasInProgress = planState.steps.any { it.status == PlanStepStatus.IN_PROGRESS }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(xc.bgElevated)
                .border(1.dp, xc.border, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            // Header row: title, N/M, collapse arrow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { expanded = !expanded }
            ) {
                Text(
                    "▾",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.sub,
                    modifier = Modifier.rotate(arrowRot)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    planState.title,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Medium,
                    color = xc.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$done/$total",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    color = if (done == total) xc.green else xc.sub,
                    modifier = Modifier.alpha(if (hasInProgress) pulseAlpha else 1f)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Animated progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(xc.border)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(if (done == total) xc.green else xc.activeBar)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
            ) {
                Column(Modifier.padding(top = 8.dp)) {
                    planState.steps.forEach { step ->
                        PlanStepRow(step, xc = xc)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(step: PlanStep, xc: XinColors) {
    // Marker + text color driven by status; both animate smoothly on change.
    val markerColor = when (step.status) {
        PlanStepStatus.DONE -> xc.green
        PlanStepStatus.IN_PROGRESS -> xc.activeBar
        PlanStepStatus.FAILED -> xc.red
        PlanStepStatus.PENDING -> xc.faint
    }
    val marker = when (step.status) {
        PlanStepStatus.DONE -> "✓"
        PlanStepStatus.IN_PROGRESS -> "●"
        PlanStepStatus.FAILED -> "✗"
        PlanStepStatus.PENDING -> "○"
    }
    val running = step.status == PlanStepStatus.IN_PROGRESS
    val infinite = rememberInfiniteTransition(label = "stepPulse${step.id}")
    val alpha by infinite.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "stepPulseAlpha${step.id}"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            marker,
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
            color = markerColor,
            modifier = Modifier
                .padding(top = 1.dp, end = 8.dp)
                .alpha(if (running) alpha else 1f)
        )
        Text(
            "${step.id}. ${step.text}",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = JetBrainsMono,
            color = when (step.status) {
                PlanStepStatus.DONE -> xc.sub
                PlanStepStatus.FAILED -> xc.red
                PlanStepStatus.PENDING -> xc.sub
                PlanStepStatus.IN_PROGRESS -> xc.ink
            },
            textDecoration = if (step.status == PlanStepStatus.DONE) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f)
        )
    }
}
