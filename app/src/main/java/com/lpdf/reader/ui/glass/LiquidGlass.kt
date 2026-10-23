package com.lpdf.reader.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.LocalGlass

/**
 * 液态玻璃容器 —— 项目内所有**功能层**玻璃的唯一入口。
 *
 * ## v1.5.0 重做：对齐 Apple Liquid Glass
 *
 * 采样管线与 Apple 的实现顺序一致：
 *
 * ```
 * ① lens()          边缘折射 + 色散（彩虹边）← 玻璃的核心特征
 * ② blur()          高斯模糊，把底层糊成色块
 * ③ colorControls() 亮度 / 对比 / 饱和整理 ← Apple 说的 "adjusts the luminosity"
 * ```
 *
 * 三处关键修正（旧版为什么"太烂"）：
 *
 * 1. **色散原来传的是 `false`**。`lens(scope, height, amount, depth, chroma)`
 *    第 4 个参数就是色散开关，关着的时候玻璃边缘是纯灰边，
 *    少了最标志性的彩虹折射 —— 这是"玻璃"与"半透明塑料"最直接的区别。
 * 2. **折射量级错了**。旧代码 `lens(refr * 100f, 24f, ...)` 把 0.12 放大成
 *    12 的位移量、24 的折射带高度，采样点直接被推到控件外，边缘采到背景色。
 *    现在是 (14px 折射带, 12px 位移) —— 边缘明显弯曲、中心保持清晰。
 * 3. **霜化层 55% 白把折射糊没了**。现在降到 20%，亮度交给
 *    `colorControls` 做，玻璃"提亮但不发白"。
 *
 * ## 严禁
 *
 * - 用「半透明色块 + 静态高斯模糊贴图」冒充玻璃
 * - 在内容层（列表行、设置分组、状态卡）使用本组件 ——
 *   Apple HIG 明确要求 "Don't use Liquid Glass in the content layer"，
 *   内容层请用 [GlassContentCard]
 */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes2.XL,
    /** 采样源；为 null 时自动使用最近的 [LiquidGlassScope] */
    backdrop: LayerBackdrop? = null,
    /** 折射强度倍率，null 取全局规范值。0 = 关掉折射只留模糊 */
    refraction: Float? = null,
    /** 模糊半径，null 取规范值 */
    blur: Dp? = null,
    /** 高亮染色（实时采样底层内容得到），null 关闭染色 */
    highlightColor: Color? = null,
    /** 高亮染色透明度 */
    highlightTint: Float = 0.12f,
    /** 是否绘制边缘高光 */
    showHighlight: Boolean = true,
    /**
     * 覆盖霜化层透明度（null = 用全局 glassFrost）。
     * v1.8.6：阅读页的顶栏/Dock 叠在正文上，默认霜化太薄 —— 书页文字
     * 原样透上来和按钮文字混在一起（用户截图）。这类「悬在内容上的玻璃」
     * 需要更实的底，由调用方按场景提高。
     */
    frostAlpha: Float? = null,
    /** 拖拽时的高光偏移（px），让光"跟着手走" */
    highlightOffset: Offset = Offset.Zero,
    /** 按压态：折射增强、高光更亮 */
    pressed: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = LocalLiquidGlassScope.current
    val spec = LocalGlass.current
    val colors = LocalColors.current
    val source = backdrop ?: scope?.backdrop
    val capability = LocalGlassCapability.current
    val level = capability.level

    val intensity = if (pressed) 1.25f else 1f
    val amountScale = refraction ?: 1f
    val refrHeight = spec.refractionHeight * intensity
    val refrAmount = spec.refractionAmount * intensity * amountScale
    val blurDp = blur ?: spec.blurDp.dp

    // 浅色 / 深色下亮度整理方向相反：浅色提亮、深色压暗（见 GlassSpec 注释）
    val bright = if (colors.isDark) spec.brightness - 0.12f else spec.brightness
    val contrast = if (colors.isDark) 1.06f else spec.contrast

    val base = Modifier
        .clip(shape)
        .then(
            if (source != null && level != GlassLevel.PLAIN) {
                Modifier.drawBackdrop(
                    backdrop = source,
                    shape = { shape },
                    effects = {
                        // ① 折射 + 色散。
                        //    refractive 开关以 amount > 0 为准：设置里把折射调到 0
                        //    就真的只留模糊，不会残留一点弯曲。
                        if (level == GlassLevel.FULL && refrAmount > 0.5f) {
                            runCatching {
                                lens(
                                    refrHeight,
                                    refrAmount,
                                    /* depthEffect = */ true,
                                    /* chromaticAberration = */ spec.chromaticAberration
                                )
                            }.onFailure {
                                capability.degrade()   // FULL → BLUR_ONLY
                                GlassLog.warn("lens", it)
                            }
                        }
                        // ② 模糊
                        if (level != GlassLevel.PLAIN) {
                            runCatching { blur(blurDp.toPx()) }
                                .onFailure {
                                    capability.degrade()   // → PLAIN
                                    GlassLog.warn("blur", it)
                                }
                            // ③ 亮度整理：这是 Apple 说的 "adjusts the luminosity"。
                            //    只靠霜化层盖白会发灰，靠 colorControls 才是玻璃的润泽感。
                            runCatching { colorControls(bright, contrast, spec.saturation) }
                                .onFailure { GlassLog.warn("colorControls", it) }
                        }
                    },
                    // 边缘高光：改用库自带的 HighlightStyle.Default。
                    // 旧版自己用 drawBehind 画一条 40% 高的白色渐变带，
                    // 结果是「一片白雾压在顶部」，不是玻璃边缘的菲涅尔亮边。
                    highlight = if (showHighlight) {
                        {
                            com.kyant.backdrop.highlight.Highlight(
                                width = 1.5.dp,
                                blurRadius = 6.dp,
                                alpha = (spec.highlightIntensity * intensity)
                                    .coerceIn(0f, 1f),
                                style = com.kyant.backdrop.highlight.HighlightStyle.Default(
                                    intensity = (spec.highlightIntensity * intensity)
                                        .coerceIn(0f, 1f),
                                    angle = spec.highlightAngle,
                                    falloff = spec.highlightFalloff
                                )
                            )
                        }
                    } else null
                )
            } else {
                // 兜底：无法采样时退化为半透明底，绝不用静态模糊假玻璃
                Modifier
            }
        )
        // 玻璃本底 + 极薄霜化层。
        //
        // v1.5.0：霜化层从 55% 降到 20%。它的职责只是「不让 PDF 正文原样透上来」，
        // 不再是「靠盖白营造磨砂感」—— 那件事由 blur + colorControls 完成。
        .drawBehind {
            drawRect(if (frostAlpha != null) colors.glassFrost.copy(alpha = frostAlpha) else colors.glassFrost)
            drawRect(colors.glassBase)
        }
        .then(
            if (highlightColor != null) {
                Modifier.drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                highlightColor.copy(alpha = highlightTint * 0.4f),
                                highlightColor.copy(alpha = highlightTint)
                            )
                        )
                    )
                }
            } else Modifier
        )
        .drawBehind {
            // 只保留一圈极细的边缘线（厚度感），不再画顶白雾。
            if (showHighlight) {
                drawGlassEdge(shape, colors.glassStroke, colors.glassStrokeBottom)
            }
        }

    Box(modifier = base, content = content)
}

/**
 * 内容层卡片材质（v1.5.0 新增）。
 *
 * ## 为什么不能对内容层用折射玻璃
 *
 * Apple HIG 在 Liquid Glass 页面写得很明确：
 *
 * > Don't use Liquid Glass in the content layer.
 * > Use Liquid Glass effects sparingly.
 *
 * 内容层的职责是**可读**。折射会让文字边缘跟着底层一起弯曲，
 * 长列表滚起来还会让整页都在扭动 —— 视觉噪音极大。
 * 内容层该用的是 standard material：半透明底 + 模糊 + 描边，没有折射。
 *
 * 所以列表行、设置分组、状态卡这类「承载信息」的容器统一走这里。
 */
@Composable
fun GlassContentCard(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes2.L,
    /** 磨砂强度：内容层比功能层轻，避免遮挡信息 */
    blur: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalColors.current
    val capability = LocalGlassCapability.current
    val scope = LocalLiquidGlassScope.current
    val source = scope?.backdrop

    val base = Modifier
        .clip(shape)
        .then(
            if (source != null && capability.level != GlassLevel.PLAIN) {
                Modifier.drawBackdrop(
                    backdrop = source,
                    shape = { shape },
                    // 只有模糊，没有 lens —— 这是内容层与功能层的关键区别
                    effects = {
                        runCatching { blur(blur.toPx()) }
                            .onFailure { GlassLog.warn("contentBlur", it) }
                    }
                )
            } else Modifier
        )
        .drawBehind {
            // 内容层底色更实一点，保证文字对比度
            drawRect(colors.glassFrost.copy(alpha = if (colors.isDark) 0.55f else 0.45f))
        }

    Box(modifier = base, content = content)
}

/**
 * 玻璃边缘：上缘亮线 + 下缘暗线，制造「厚度」。
 *
 * 单圈均匀描边看起来像贴纸；真实玻璃的侧面会折射光线，
 * 上缘接光发亮、下缘因厚度投出细暗线。参考图里这个特征很明显。
 *
 * 实现方式：用一条**从上到下由亮转暗**的垂直渐变去描边。
 * 比「先画整圈亮线再叠一条底部暗线」干净得多 —— 后者会在四角糊出
 * 双层重影，圆角越大越明显。
 */
private fun DrawScope.drawGlassEdge(shape: Shape, topColor: Color, bottomColor: Color) {
    val stroke = Stroke(width = 1.dp.toPx())
    val brush = Brush.verticalGradient(
        colors = listOf(topColor, topColor.copy(alpha = 0f), bottomColor),
        startY = 0f,
        endY = size.height
    )
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is androidx.compose.ui.graphics.Outline.Generic ->
            drawPath(outline.path, brush = brush, style = stroke)

        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val rr: RoundRect = outline.roundRect
            drawRoundRect(
                brush = brush,
                topLeft = Offset(rr.left, rr.top),
                size = Size(rr.width, rr.height),
                cornerRadius = CornerRadius(rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y),
                style = stroke
            )
        }

        is androidx.compose.ui.graphics.Outline.Rectangle ->
            drawRect(brush = brush, topLeft = Offset.Zero, size = size, style = stroke)
    }
}

// ---------------------------------------------------------------------------
// 采样作用域
// ---------------------------------------------------------------------------

/**
 * 采样作用域。把页面内容录制到 GraphicsLayer，供其下所有玻璃组件采样。
 *
 * 用法：
 * ```
 * LiquidGlassScope { backdrop ->
 *     Box(Modifier.fillMaxSize()) {
 *         ListContent()
 *         LiquidGlass(backdrop = backdrop) { ... }
 *     }
 * }
 * ```
 */
class LiquidGlassState internal constructor(initial: LayerBackdrop) {
    var backdrop: LayerBackdrop by mutableStateOf(initial)
        internal set
}

@Composable
fun rememberLiquidGlassState(layer: GraphicsLayer): LiquidGlassState {
    val backdrop = rememberLayerBackdrop(layer)
    return remember(layer) { LiquidGlassState(backdrop) }
}

val LocalLiquidGlassScope = androidx.compose.runtime.staticCompositionLocalOf<LiquidGlassState?> { null }

/**
 * 页面级采样容器（对应《开发规范 §9 层级与采样实现》）。
 *
 * 关键在于**内容层与玻璃层必须物理分离**：
 * ```
 * Box
 *  ├─ Box(drawWithContent { layer.record{ drawContent() }; drawLayer(layer) })  ← 只装 L1 背景 + L2 内容
 *  │    └─ content 的「内容部分」
 *  └─ CompositionLocalProvider(scope) { content 的「玻璃部分」 }              ← 不参与录制
 * ```
 * 若让玻璃与内容处于同一子树，玻璃的绘制会被 `record` 录进采样层，
 * 形成「玻璃采到自己」的递归，表现为全屏发灰或闪烁。
 *
 * 因此调用方需配合 [LiquidGlassScaffold]：内容用 `{ content }`，
 * 玻璃用 `{ overlay }`，两者由本组件分别放入录制层与覆盖层。
 */
@Composable
fun LiquidGlassScope(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.(LiquidGlassState) -> Unit = {}
) {
    val layer = rememberGraphicsLayer()
    val state = rememberLiquidGlassState(layer)

    Box(modifier.fillMaxSize()) {
        // ① 被采样的内容层：录制 → 立刻绘制，后面的玻璃即可采到帧
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
            content = { content() }
        )
        // ② 玻璃覆盖层：不录进 layer，因此采样到的是①的结果
        // 同样要显式撑满 —— 否则 overlay 根节点按内容高度从顶部排布，
        // 底部 Dock 会跑到屏幕上方（同 LiquidGlassHost 的说明）
        CompositionLocalProvider(LocalLiquidGlassScope provides state) {
            Box(Modifier.fillMaxSize()) {
                overlay(state)
            }
        }
    }
}
