package com.xincode.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UserAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val colors = LocalXinColors.current
    Image(
        painter = painterResource(R.drawable.avatar_user_re),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.bgElevated)
            .border(1.dp, colors.border, CircleShape)
    )
}

@DrawableRes
fun providerIconRes(supplierId: String): Int = when (supplierId.trim().lowercase(Locale.ROOT)) {
    "deepseek" -> R.drawable.provider_deepseek
    "openai" -> R.drawable.provider_openai
    "anthropic" -> R.drawable.provider_anthropic
    "groq" -> R.drawable.provider_groq
    "zhipu" -> R.drawable.provider_zhipu
    "dashscope" -> R.drawable.provider_qwen
    "moonshot" -> R.drawable.provider_moonshot
    "baidu" -> R.drawable.provider_baidu
    "ollama" -> R.drawable.provider_ollama
    "nous" -> R.drawable.provider_nous
    "openrouter" -> R.drawable.provider_openrouter
    "xai" -> R.drawable.provider_xai
    "modelscope" -> R.drawable.provider_modelscope
    "siliconflow" -> R.drawable.provider_siliconflow
    "opencode-zen" -> R.drawable.provider_opencode
    else -> R.mipmap.ic_launcher_round
}

@Composable
fun ProviderAvatar(
    supplierId: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val colors = LocalXinColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (colors.isDark) Color(0xFFF8F8F4) else colors.bgElevated)
            .border(1.dp, colors.border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(providerIconRes(supplierId)),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .matchParentSize()
                .padding(size * 0.17f)
        )
    }
}

@Composable
fun ChatActionIcon(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = LocalXinColors.current.ink,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

fun formatChatTime(timestamp: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("HH:mm", locale).format(Date(timestamp))

fun assistantSubtitle(assistantName: String, model: String, providerName: String): String {
    val assistant = assistantName.ifBlank { "默认助手" }
    val modelLabel = model.ifBlank { "未选择模型" }
    val provider = providerName.trim()
    return buildString {
        append(assistant)
        append(" / ")
        append(modelLabel)
        if (provider.isNotEmpty()) append(" ($provider)")
    }
}

fun derivedThinkingDurationMs(group: TurnGroup): Long? {
    val start = group.assistantMessage?.timestamp ?: return null
    val end = group.toolMessages.minOfOrNull { it.timestamp } ?: return null
    val elapsed = end - start
    return elapsed.takeIf { it in 1..(60L * 60L * 1000L) }
}

fun formatThinkingLabel(durationMs: Long?): String {
    if (durationMs == null) return "思考过程"
    val seconds = (durationMs.coerceAtLeast(100L) / 1000.0)
    return "思考了 ${String.format(Locale.US, "%.1f", seconds)} 秒"
}
