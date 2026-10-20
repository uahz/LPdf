package com.lpdf.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 一套完整的视觉令牌集合。随系统深浅色自动切换，
 * 并支持设置页覆盖（主题 / 玻璃强度 / 动效等级）。
 */
@Immutable
data class LPdfColors(
    val isDark: Boolean,
    val text: Color,
    val secondary: Color,
    val pageBg: Color,
    val paper: Color,
    val scrim: Color,
    val danger: Color,
    val success: Color,
    val accent: Color,
    /** 玻璃本底 */
    val glassBase: Color,
    /** 玻璃边缘描边 */
    val glassStroke: Color,
    val separator: Color
)

val LightColors = LPdfColors(
    isDark = false,
    text = LColors.LightText,
    secondary = LColors.LightSecondary,
    pageBg = LColors.PageBgLight,
    paper = LColors.PaperWhite,
    scrim = LColors.ScrimLight,
    danger = LColors.Danger,
    success = LColors.Success,
    accent = LColors.AccentBlue,
    glassBase = Color(0x0FFFFFFF),      // 6% 白
    glassStroke = Color(0x38FFFFFF),    // 22% 白
    separator = Color(0x14000000)
)

val DarkColors = LPdfColors(
    isDark = true,
    text = LColors.DarkText,
    secondary = LColors.DarkSecondary,
    pageBg = LColors.PageBgDark,
    paper = LColors.PaperWhite,
    scrim = LColors.ScrimDark,
    danger = LColors.DangerDark,
    success = LColors.Success,
    accent = LColors.AccentBlue,
    glassBase = Color(0x0AFFFFFF),      // 4% 白
    glassStroke = Color(0x24FFFFFF),    // 14% 白
    separator = Color(0x1FFFFFFF)
)

/**
 * 玻璃光学参数（视觉规范 §2.2 锁定值）。
 */
@Immutable
data class GlassSpec(
    val blurDp: Float = 10f,
    val refraction: Float = 0.12f,
    val dispersion: Float = 0.04f,
    val highlightAlpha: Float = 0.15f,
    val maskAlpha: Float = 0.35f,
    val highlightHeightRatio: Float = 0.40f,
    /** 设置页"动效等级"为减弱/关闭时，动态形变被压平 */
    val motionScale: Float = 1f,
    /** 玻璃强度偏好（1.0 = 规范基准） */
    val intensity: Float = 1f
)

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalGlass = staticCompositionLocalOf { GlassSpec() }
val LocalType = staticCompositionLocalOf { LType }

@Composable
fun LPdfTheme(
    dark: Boolean,
    glass: GlassSpec = GlassSpec(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalColors provides if (dark) DarkColors else LightColors,
        LocalGlass provides glass,
        LocalType provides LType,
        content = content
    )
}

/** 语义化文本样式快捷访问 */
val TextStyle.body: TextStyle get() = LType.Body
