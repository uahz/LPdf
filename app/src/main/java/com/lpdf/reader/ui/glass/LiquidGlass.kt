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
import com.kyant.backdrop.effects.lens
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.LocalGlass

/**
 * 液态玻璃容器 —— 项目内所有玻璃组件的唯一入口。
 *
 * 实现要点（对应《视觉规范 §2.1 渲染管线》）：
 * 1. 由 [LiquidGlassScope] 捕获内容层（L1+L2）的 GraphicsLayer，作为采样源
 * 2. 通过 Kyant0 AndroidLiquidGlass 的 [drawBackdrop] 在 GPU 上执行 AGSL：
 *    透镜位移（lens）→ 模糊（blur）→ 高光（highlight）→ 阴影/内阴影
 * 3. 叠加玻璃本底、边缘描边与顶部流动高光带
 *
 * 严禁用「半透明色块 + 高斯模糊贴图」冒充玻璃。
 */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes2.XL,
    /** 采样源；为 null 时自动使用最近的 [LiquidGlassScope] */
    backdrop: LayerBackdrop? = null,
    /** 折射强度，null 取全局规范值 0.12 */
    refraction: Float? = null,
    /** 模糊半径，null 取设置值 */
    blur: Dp? = null,
    /** 高亮色（实时采样底层内容得到），null 关闭高亮染色 */
    highlightColor: Color? = null,
    /** 高亮染色透明度 */
    highlightTint: Float = 0.12f,
    /** 是否绘制顶部高光带 */
    showHighlight: Boolean = true,
    /** 拖拽时的高光偏移（px），让光"跟着手走" */
    highlightOffset: Offset = Offset.Zero,
    /** 按压态：折射增强、缩放由调用方处理 */
    pressed: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = LocalLiquidGlassScope.current
    val spec = LocalGlass.current
    val colors = LocalColors.current
    val source = backdrop ?: scope?.backdrop
    val density = LocalDensity.current
    // 设备能力：AGSL 不可用时自动退化为模糊玻璃，而不是崩掉
    val capability = LocalGlassCapability.current
    val level = capability.level

    val refr = (refraction ?: spec.refraction) * if (pressed) 1.2f else 1f
    val blurDp = blur ?: spec.blurDp.dp
    val highlightAlpha = spec.highlightAlpha * (if (pressed) 1.45f else 1f)

    val base = Modifier
        .clip(shape)
        .then(
            if (source != null && level != GlassLevel.PLAIN) {
                Modifier.drawBackdrop(
                    backdrop = source,
                    shape = { shape },
                    effects = {
                        // 三级降级：
                        //  FULL      → lens（AGSL 折射）+ blur
                        //  BLUR_ONLY → 跳过 lens，保留 blur/highlight
                        //  PLAIN     → 不进入本分支
                        // 每个效果单独 try/catch：某些 ROM 的 lens 会在 updateEffects
                        // 里抛 UnsupportedOperationException，一旦冒到绘制循环就是闪退。
                        if (level == GlassLevel.FULL) {
                            runCatching {
                                lens(refr * 100f * spec.intensity, 24f * spec.intensity, true, false)
                            }.onFailure {
                                capability.degrade()   // FULL → BLUR_ONLY
                                GlassLog.warn("lens", it)
                            }
                        }
                        if (level != GlassLevel.PLAIN) {
                            runCatching { blur(blurDp.toPx()) }
                                .onFailure {
                                    capability.degrade()   // → PLAIN
                                    GlassLog.warn("blur", it)
                                }
                        }
                    },
                    highlight = {
                        com.kyant.backdrop.highlight.Highlight(
                            width = 2.dp,
                            blurRadius = 8.dp,
                            alpha = highlightAlpha
                        )
                    }
                )
            } else {
                // 兜底：无法采样时退化为半透明底，绝不使用静态模糊假玻璃
                Modifier
            }
        )
        // 玻璃本底
        .drawBehind {
            if (level == GlassLevel.PLAIN) {
                // PLAIN：连 backdrop 都没有，只靠 6% 白的本底会糊成一团。
                // 加深本底并加一层暗角，保证文字仍然可读（此时用户看到的是
                // 「磨砂塑料」而不是「玻璃」，视觉降级但可用）。
                drawRect(colors.glassBase.copy(alpha = (colors.glassBase.alpha + 0.55f).coerceAtMost(0.72f)))
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.06f), Color.Transparent)
                    )
                )
            } else {
                drawRect(colors.glassBase)
            }
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
            // 顶部流动高光带
            if (showHighlight) {
                drawTopHighlight(highlightOffset, highlightAlpha, size)
            }
            // 边缘描边（1dp 内描）
            drawOutlineStroke(shape, colors.glassStroke)
        }

    Box(modifier = base, content = content)
}

/** 顶部高光带：上边缘 40% 高度内从白色渐变到透明，并随 [offset] 轻微位移 */
private fun DrawScope.drawTopHighlight(offset: Offset, alpha: Float, size: Size) {
    val h = size.height * 0.40f
    val start = Offset(offset.x * 0.06f, offset.y * 0.02f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
            startY = start.y,
            endY = start.y + h
        ),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, h)
    )
    // 左上角斜向扫描光，增强"玻璃砖"的实感
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = alpha * 0.55f), Color.Transparent),
            start = Offset(size.width * 0.05f + start.x, 0f),
            end = Offset(size.width * 0.55f + start.x, size.height * 0.5f)
        ),
        size = Size(size.width, size.height * 0.5f)
    )
}

/** 按形状绘制 1dp 描边（直接使用 DrawScope 的描边绘制，避免 Canvas 兼容问题） */
private fun DrawScope.drawOutlineStroke(shape: Shape, color: Color) {
    val stroke = Stroke(width = 1.dp.toPx())
    // DrawScope 自身即 Density，可直接用于生成轮廓
    val outline = shape.createOutline(size, layoutDirection, this)
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Generic ->
            drawPath(outline.path, color = color, style = stroke)

        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val rr: RoundRect = outline.roundRect
            drawRoundRect(
                color = color,
                topLeft = Offset(rr.left, rr.top),
                size = Size(rr.width, rr.height),
                cornerRadius = CornerRadius(rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y),
                style = stroke
            )
        }

        is androidx.compose.ui.graphics.Outline.Rectangle ->
            drawRect(color = color, topLeft = Offset.Zero, size = size, style = stroke)
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
        CompositionLocalProvider(LocalLiquidGlassScope provides state) {
            overlay(state)
        }
    }
}
