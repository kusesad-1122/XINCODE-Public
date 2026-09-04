package com.xincode.app

import android.app.Activity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * XINCODE Claude-inspired aesthetic palette — warm parchment, literary serif typography,
 * terracotta amber accents and refined, low-contrast borders.
 *
 * Two variants: [XinLight] (warm parchment cream) and [XinDark] (warm obsidian charcoal).
 * Read via LocalXinColors.current inside a Composable, wrapped by [XinTheme].
 */
@Immutable
data class XinColors(
    val bg: Color,
    val bgElevated: Color,
    val ink: Color,
    val sub: Color,
    val faint: Color,
    val green: Color,       // Brand primary accent: warm terracotta coral (Claude style)
    val red: Color,
    val yellow: Color,      // Secondary accent: golden amber
    val border: Color,
    val activeBg: Color,
    val activeBar: Color,
    val divider: Color,
    val isDark: Boolean
)

val XinLight = XinColors(
    bg = Color(0xFFFAF8F5),          // Claude Warm Parchment / Cream
    bgElevated = Color(0xFFFFFFFF),  // Pure warm elevated surface
    ink = Color(0xFF201E1C),         // Deep warm espresso
    sub = Color(0xFF6B6760),         // Refined charcoal stone
    faint = Color(0xFF9E9A91),       // Gentle mute
    green = Color(0xFFCC6644),       // Claude Iconic Terracotta Coral
    red = Color(0xFFBF4842),         // Brick red
    yellow = Color(0xFFD97706),      // Warm Amber
    border = Color(0xFFEBE6DD),      // Feather-light warm border
    activeBg = Color(0x1ACC6644),    // Tinted terracotta wash
    activeBar = Color(0xFFCC6644),
    divider = Color(0x14201E1C),
    isDark = false
)

val XinDark = XinColors(
    bg = Color(0xFF181715),          // Deep warm obsidian
    bgElevated = Color(0xFF22201D),  // Warm dark stone surface
    ink = Color(0xFFEDE8E1),         // Soft warm ivory
    sub = Color(0xFF9E988E),         // Muted stone gray
    faint = Color(0xFF635F57),       // Subtle mute
    green = Color(0xFFE07A5F),       // Luminous warm terracotta
    red = Color(0xFFD96B6B),
    yellow = Color(0xFFE5A84B),      // Warm Amber Dark
    border = Color(0xFF2E2B27),      // Low-contrast warm border
    activeBg = Color(0x33E07A5F),
    activeBar = Color(0xFFE07A5F),
    divider = Color(0x22EDE8E1),
    isDark = true
)

val LocalXinColors = compositionLocalOf { XinLight }

/**
 * Root theme wrapper — provides LocalXinColors for the entire tree.
 * Uses a 300ms animated color transition so switching light<->dark is smooth.
 */
@Composable
fun XinTheme(dark: Boolean, content: @Composable () -> Unit) {
    val target = if (dark) XinDark else XinLight
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = animated.bg.toArgb()
            window.navigationBarColor = animated.bg.toArgb()
            WindowCompat.getInsetsController(window, view).let {
                it.isAppearanceLightStatusBars = !dark
                it.isAppearanceLightNavigationBars = !dark
            }
        }
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

/** Bundled Chinese serif for stable editorial headings on Android OEM fonts. */
val XinSerifFont: FontFamily = FontFamily(
    Font(R.font.noto_serif_sc, FontWeight.Normal),
    Font(R.font.noto_serif_sc, FontWeight.Medium),
    Font(R.font.noto_serif_sc, FontWeight.SemiBold),
    Font(R.font.noto_serif_sc, FontWeight.Bold)
)

/** Clean humanist system sans for UI buttons, forms, and conversation body. */
val XinUiFont: FontFamily = FontFamily.SansSerif

/** Monospace for code, tokens, commands, and terminals. */
val XinCodeFont: FontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/** Compatibility alias used by existing UI while screens migrate to shared components. */
val XinFont: FontFamily = XinUiFont

val XinTypography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = XinSerifFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = XinSerifFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = XinSerifFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontSize = 16.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = XinUiFont,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

val XinShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp)
)
