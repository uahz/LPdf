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
    /** 玻璃本底（主体半透明填充） */
    val glassBase: Color,
    /**
     * 玻璃「霜化」层：叠在本底之上、内容之下的一层乳白雾。
     *
     * 纯 `glassBase` 只有 6% 白，叠在彩色 PDF 页面上等于没有 —— 底下的文字
     * 会原样透上来，图标和文字完全糊在一起看不清（v1.3.4 真机截图）。
     * 参考图的玻璃是有实体的：能看见底下内容，但亮度被抬高、对比被压低。
     * 这一层就是这个作用，配合 blur 达成果冻质感。
     */
    val glassFrost: Color,
    /** 玻璃边缘描边（上缘高光 + 下缘暗线，立体感来源） */
    val glassStroke: Color,
    /** 下缘暗线，让玻璃看起来有厚度 */
    val glassStrokeBottom: Color,
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
    glassBase = Color(0x2EFFFFFF),      // 18% 白：玻璃实体感
    glassFrost = Color(0x8CFFFFFF),     // 55% 白：霜化层，压住底层对比
    glassStroke = Color(0xB0FFFFFF),    // 69% 白：亮上缘
    glassStrokeBottom = Color(0x1A000000), // 10% 黑：下缘厚度暗线
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
    glassBase = Color(0x28FFFFFF),      // 16% 白
    glassFrost = Color(0x59000000),     // 35% 黑：深色下霜化层是压暗而非提亮
    glassStroke = Color(0x66FFFFFF),    // 40% 白
    glassStrokeBottom = Color(0x33000000),
    separator = Color(0x1FFFFFFF)
)

/**
 * 玻璃光学参数（视觉规范 §2.2 锁定值）。
 */
@Immutable
data class GlassSpec(
    /**
     * 模糊半径。
     *
     * 规范原定 10dp，但真机上玻璃底下的 PDF 文字仍然清晰可辨，
     * 玻璃看起来像「一块透明塑料」而不是「磨砂玻璃」。参考图里底层内容
     * 已经被糊成色块，只剩轮廓 —— 对应 18dp 左右的模糊量。
     */
    val blurDp: Float = 18f,
    val refraction: Float = 0.12f,
    val dispersion: Float = 0.04f,
    val highlightAlpha: Float = 0.15f,
    val maskAlpha: Float = 0.35f,
    val highlightHeightRatio: Float = 0.40f,
    /** 设置页"动效等级"为减弱/关闭时，动态形变被压平 */
    val motionScale: Float = 1f,
    /** 玻璃强度偏好（1.0 = 规范基准） */
    val intensity: Float = 1f,
    /**
     * 是否允许 AGSL 折射（设备能力探测结果，非用户设置）。
     *
     * 这个值走 [GlassSpec] 而不是单独读 CompositionLocal，是为了让玻璃参数
     * 只有一个数据源：一旦设备不支持折射，[intensity] 里就不含折射分量，
     * 所有依赖它的计算（阴影、高光位移、边缘色散）自动保持一致。
     */
    val allowRefraction: Boolean = true
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
