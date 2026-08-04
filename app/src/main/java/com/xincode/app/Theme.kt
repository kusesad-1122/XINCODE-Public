package com.xincode.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = animated.green,
            onPrimary = animated.bg,
            primaryContainer = animated.activeBg,
            onPrimaryContainer = animated.ink,
            secondary = animated.yellow,
            onSecondary = animated.bg,
            error = animated.red,
            background = animated.bg,
            onBackground = animated.ink,
            surface = animated.bgElevated,
            onSurface = animated.ink,
            surfaceVariant = animated.activeBg,
            onSurfaceVariant = animated.sub,
            outline = animated.border,
            outlineVariant = animated.divider
        )
    } else {
        lightColorScheme(
            primary = animated.green,
            onPrimary = animated.bgElevated,
            primaryContainer = animated.activeBg,
            onPrimaryContainer = animated.ink,
            secondary = animated.yellow,
            onSecondary = animated.bgElevated,
            error = animated.red,
            background = animated.bg,
            onBackground = animated.ink,
            surface = animated.bgElevated,
            onSurface = animated.ink,
            surfaceVariant = animated.activeBg,
            onSurfaceVariant = animated.sub,
            outline = animated.border,
            outlineVariant = animated.divider
        )
    }

    CompositionLocalProvider(LocalXinColors provides animated) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = XinTypography,
            shapes = XinShapes,
            content = content
        )
    }
}

/** Humanist system sans for navigation, settings, forms and conversation copy. */
val XinUiFont: FontFamily = FontFamily.SansSerif

/** Monospace is intentionally reserved for commands, code and terminal output. */
val XinCodeFont: FontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/** Compatibility alias used by existing UI while screens migrate to shared components. */
val XinFont: FontFamily = XinUiFont

val XinTypography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontSize = 17.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

val XinShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)
