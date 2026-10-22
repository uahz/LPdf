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
    glassBase = Color(0x1FFFFFFF),      // 12% 白：极薄的实体感
    // v1.5.0：55% → 20%。55% 的白雾会把折射糊成乳白，
    // 「调整亮度」应交给 colorControls，而不是靠盖白。
    glassFrost = Color(0x33FFFFFF),     // 20% 白：仅做轻微提亮
    glassStroke = Color(0x8CFFFFFF),    // 55% 白：亮上缘
    glassStrokeBottom = Color(0x14000000), // 8% 黑：下缘厚度暗线
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
    glassBase = Color(0x1AFFFFFF),      // 10% 白
    // 深色下霜化层是压暗而非提亮；同样从 35% 降到 14%
    glassFrost = Color(0x24000000),     // 14% 黑
    glassStroke = Color(0x59FFFFFF),    // 35% 白
    glassStrokeBottom = Color(0x33000000),
    separator = Color(0x1FFFFFFF)
)

/**
 * 玻璃光学参数。
 *
 * ## v1.5.0 重做：对齐 Apple Liquid Glass
 *
 * 旧版的参数是「看起来差不多」的猜测值，实测效果用户评价是「太烂了」。
 * 对照 Apple HIG 与 Metal / AGSL 的实现方式（`liquid-glass` 的采样管线是
 * 折射 → 色散 → 模糊 → 亮度整理 → 边缘高光），旧版有三处方向性错误：
 *
 * 1. **色散关着**。`lens(..., depthEffect, chromaticAberration)` 的第 4 个参数
 *    原来传的是 `false`，玻璃最标志性的彩虹边缘从未出现过。而它正是
 *    「玻璃」与「半透明塑料」在视觉上最直接的区别。
 * 2. **霜化层 55% 白**把折射糊成乳白。Apple 的 `regular` 变体只是
 *    「blur + 调整亮度」，不会用大面积白雾去盖。现在降到 20%，
 *    亮度交给 [colorControls] 的 `brightness/contrast/saturation` 做，
 *    这才是玻璃「提亮但不发白」的正确做法。
 * 3. **折射量级耦合错**。原来 `refraction × 100` 得到的是像素位移，
 *    24px 的位移会把采样点推到控件外，边缘直接采到背景色。
 *    现在拆成 [refractionHeightPx]（折射带高度）与 [refractionAmountPx]
 *    （最大位移），量级分别是 14px / 12px —— 与 Apple 在 iOS 26 上的
 *    观感一致：边缘有明显「凸透镜」弯曲，但中心保持清晰。
 */
@Immutable
data class GlassSpec(
    /**
     * 模糊半径（dp）。
     *
     * 22dp 是 Apple `regular` 变体的量级：底层内容被糊成色块、只剩轮廓，
     * 但又不是完全看不出结构。低于 12dp 时 PDF 文字仍可辨认，
     * 玻璃就会退化成「透明塑料」。
     */
    val blurDp: Float = 22f,

    /** 折射带高度（px）：从边缘向内多少距离内发生弯曲 */
    val refractionHeightPx: Float = 14f,

    /** 最大折射位移（px）：越大越像厚透镜，过大则边缘失真 */
    val refractionAmountPx: Float = 12f,

    /** 是否开启色散（彩虹边缘）。关掉能省 6 次采样，但玻璃感会明显减弱 */
    val chromaticAberration: Boolean = true,

    /**
     * 底层亮度整理。Apple 的 `regular` 变体描述是
     * "blurs and adjusts the luminosity"，这三项就是那个 luminosity 调整：
     * - 浅色下 brightness 略抬 + contrast 略压，让玻璃发「润」
     * - 深色下 brightness 略压，避免灰成一片
     */
    val brightness: Float = 1.06f,
    val contrast: Float = 0.94f,
    val saturation: Float = 1.35f,

    /** 边缘高光强度（0~1） */
    val highlightIntensity: Float = 0.55f,

    /** 边缘高光角度（度），模拟左上光源 */
    val highlightAngle: Float = 135f,

    /** 高光衰减：越大越集中在边缘，越小越铺开 */
    val highlightFalloff: Float = 0.6f,

    /** 设置页"动效等级"为减弱/关闭时，动态形变被压平 */
    val motionScale: Float = 1f,

    /** 玻璃强度整体倍率（1.0 = 规范基准） */
    val intensity: Float = 1f
) {
    /** 实际折射带高度（含倍率） */
    val refractionHeight: Float get() = refractionHeightPx * intensity

    /** 实际折射位移（含倍率） */
    val refractionAmount: Float get() = refractionAmountPx * intensity
}

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalGlass = staticCompositionLocalOf { GlassSpec() }
val LocalType = staticCompositionLocalOf { LType }

@Composable
fun LPdfTheme(
    dark: Boolean,
    glass: GlassSpec = GlassSpec(),
    /**
     * 强调色覆盖（v1.6.1）。
     *
     * 设置里的「高亮色取自文档」打开时，调用方把当前文档封面的主色传进来，
     * 玻璃和强调元素就跟着封面走。传 null 表示用主题默认色。
     *
     * 放在主题层而不是每个组件各自判断：否则「取色」这件事要在
     * 十几个地方各写一遍，还容易一半取色一半不取，看着很割裂。
     */
    accentOverride: Color? = null,
    content: @Composable () -> Unit
) {
    val base = if (dark) DarkColors else LightColors
    val colors = if (accentOverride == null) base else base.copy(accent = accentOverride)
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalGlass provides glass,
        LocalType provides LType,
        content = content
    )
}

/** 语义化文本样式快捷访问 */
val TextStyle.body: TextStyle get() = LType.Body
