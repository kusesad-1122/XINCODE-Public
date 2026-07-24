package com.xincode.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * XINCODE palette — terminal aesthetic, brand-neutral tokens.
 *
 * Two variants: [XinLight] (default parchment) and [XinDark] (near-black terminal).
 * Read via `LocalXinColors.current` inside a Composable, wrapped by [XinTheme].
 */
@Immutable
data class XinColors(
    val bg: Color,
    val bgElevated: Color,
    val ink: Color,
    val sub: Color,
    val faint: Color,
    val green: Color,
    val red: Color,
    val yellow: Color,
    val border: Color,
    val activeBg: Color,
    val activeBar: Color,
    val divider: Color,
    val isDark: Boolean
)

val XinLight = XinColors(
    bg = Color(0xFFF9F9F6),
    bgElevated = Color(0xFFFDFDFB),
    ink = Color(0xFF1A1A17),
    sub = Color(0xFF86857B),
    faint = Color(0xFFB7B6AB),
    green = Color(0xFF6E8050),
    red = Color(0xFFA8514A),
    yellow = Color(0xFFB88B3A),
    border = Color(0xFFE6E4DC),
    activeBg = Color(0x1A6E8050),
    activeBar = Color(0xFF6E8050),
    divider = Color(0x141A1A17),
    isDark = false
)

val XinDark = XinColors(
    bg = Color(0xFF141412),
    bgElevated = Color(0xFF1C1C1A),
    ink = Color(0xFFE8E6DC),
    sub = Color(0xFF8F8E85),
    faint = Color(0xFF585754),
    green = Color(0xFF8FA36A),
    red = Color(0xFFC77469),
    yellow = Color(0xFFD9A85B),
    border = Color(0xFF2A2A26),
    activeBg = Color(0x338FA36A),
    activeBar = Color(0xFF8FA36A),
    divider = Color(0x22E8E6DC),
    isDark = true
)

val LocalXinColors = compositionLocalOf { XinLight }

/**
 * Root theme wrapper — provides `LocalXinColors` for the entire tree.
 * Uses a 300ms animated color transition so switching light↔dark is smooth.
 */
@Composable
fun XinTheme(dark: Boolean, content: @Composable () -> Unit) {
    val target = if (dark) XinDark else XinLight
    // Animate the 4 most-visible tokens so the switch is buttery instead of a hard cut.
    val bg by animateColorAsState(target.bg, tween(300), label = "themeBg")
    val ink by animateColorAsState(target.ink, tween(300), label = "themeInk")
    val bgElevated by animateColorAsState(target.bgElevated, tween(300), label = "themeBgE")
    val border by animateColorAsState(target.border, tween(300), label = "themeBorder")
    val animated = target.copy(bg = bg, ink = ink, bgElevated = bgElevated, border = border)
    CompositionLocalProvider(LocalXinColors provides animated, content = content)
}

/** JetBrains Mono, shared across all UI. */
val XinFont: FontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
