package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared page chrome for every non-immersive XINCODE screen, crafted with Claude aesthetic. */
@Composable
fun XinPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalXinColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.bgElevated)
                .border(0.5.dp, colors.border, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = colors.ink,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.ink,
                fontFamily = XinSerifFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 27.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.sub,
                    fontFamily = XinUiFont,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** Consistent pill text action for page headers, styled in Claude terracotta pill. */
@Composable
fun XinHeaderAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val colors = LocalXinColors.current
    val contentColor = when {
        !enabled -> colors.faint
        destructive -> colors.red
        else -> colors.green
    }
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) colors.activeBg else colors.bg)
            .border(0.5.dp, if (enabled) colors.green.copy(alpha = 0.35f) else colors.border, RoundedCornerShape(18.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = contentColor,
            fontFamily = XinUiFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}

/** Rounded section surface used by settings and management pages. */
@Composable
fun XinSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalXinColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgElevated, RoundedCornerShape(22.dp))
            .border(1.dp, colors.border, RoundedCornerShape(22.dp)),
    ) {
        content()
    }
}

/** 磨砂玻璃质感的开关:半透明渐变轨道 + 高光描边 + 白瓷拇指(选中变主题赤陶色)。 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = LocalXinColors.current
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "glassSwitch"
    )
    Box(
        modifier = modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (checked) 0.34f else 0.55f),
                        Color.White.copy(alpha = 0.14f)
                    )
                )
            )
            .border(
                0.8.dp,
                if (checked) colors.green.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f),
                RoundedCornerShape(15.dp)
            )
            .clickable(
                enabled = onCheckedChange != null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange?.invoke(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(start = 3.dp)
                .offset(x = progress * 22.dp)
                .size(24.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(if (checked) colors.green else Color.White)
        )
    }
}
