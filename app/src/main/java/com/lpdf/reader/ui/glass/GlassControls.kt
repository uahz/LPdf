package com.lpdf.reader.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion

/**
 * 玻璃圆形图标按钮。按压时缩放 0.94，并增强折射与高光。
 */
@Composable
fun GlassIconButton(
    icon: LIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    tint: Color? = null,
    showBg: Boolean = true
) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Motion.standard,
        label = "btnScale"
    )
    val color = tint ?: if (enabled) colors.text else colors.text.copy(alpha = 0.35f)
    val shape = SquircleShape(size.value / 2f, exponent = 4f)

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        if (showBg) {
            LiquidGlass(
                Modifier.matchParentSize(),
                shape = shape,
                showHighlight = pressed,
                pressed = pressed
            ) {}
        }
        LIconView(icon, size = iconSize, color = color)
    }
}

/**
 * 玻璃胶囊文字按钮。
 */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    contentColor: Color? = null,
    leading: LIcon? = null,
    enabled: Boolean = true
) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.standard, label = "pillScale")
    val shape = pillShape(height.value)
    val fg = contentColor ?: if (enabled) colors.text else colors.text.copy(alpha = 0.35f)

    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlass(
            Modifier.matchParentSize(),
            shape = shape,
            pressed = pressed
        ) {}
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leading != null) {
                LIconView(leading, size = 16.dp, color = fg)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = LType.BodyStrong, color = fg, textAlign = TextAlign.Center)
        }
    }
}

/**
 * iOS 风格玻璃拨杆开关：玻璃轨道 + 高光球，开启时用高亮色填充。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color? = null,
    contentDescription: String? = null
) {
    val colors = LocalColors.current
    val trackW = 48.dp
    val trackH = 28.dp
    val knob = 22.dp
    val shape = pillShape(28f)
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.toggle,
        label = "switchFill"
    )
    val knobPos = androidx.compose.animation.core.FastOutSlowInEasing.transform(fill)
    val active = accent ?: colors.accent

    Box(
        modifier
            .size(trackW, trackH)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
    ) {
        LiquidGlass(Modifier.matchParentSize(), shape = shape, showHighlight = false) {}
        // 开启态填充
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .drawBehind {
                    if (fill > 0.001f) {
                        drawRect(active.copy(alpha = 0.30f * fill))
                    } else {
                        drawRect(colors.text.copy(alpha = 0.10f))
                    }
                }
        )
        // 高光球
        Box(
            Modifier
                .padding(3.dp)
                .size(knob)
                .graphicsLayer {
                    val travel = trackW.toPx() - knob.toPx() - 6.dp.toPx()
                    translationX = travel * knobPos
                    shadowElevation = 3.dp.toPx()
                }
                .clip(pillShape(22f))
                .drawBehind { drawRect(Color.White.copy(alpha = 0.95f)) }
        )
    }
}

/**
 * 玻璃滑块：4dp 轨道 + 24dp 玻璃钮。
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    contentDescription: String? = null,
    steps: Int = 0
) {
    val colors = LocalColors.current
    val norm = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .height(36.dp)
            .fillMaxWidth()
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
    ) {
        val usable = (maxWidth - 24.dp).coerceAtLeast(1.dp)
        // 轨道
        LiquidGlass(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.Center),
            shape = pillShape(8f),
            showHighlight = false
        ) {}
        Box(
            Modifier
                .width(24.dp + usable * norm)
                .height(8.dp)
                .align(Alignment.CenterStart)
                .clip(pillShape(8f))
                .drawBehind { drawRect(colors.text.copy(alpha = 0.55f)) }
        )
        // 玻璃钮
        Box(
            Modifier
                .size(24.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = (usable.toPx() * norm)
                    shadowElevation = 4.dp.toPx()
                }
                .clip(pillShape(24f))
        ) {
            LiquidGlass(Modifier.matchParentSize(), shape = pillShape(24f)) {}
            Box(
                Modifier
                    .matchParentSize()
                    .clip(pillShape(24f))
                    .drawBehind { drawRect(Color.White.copy(alpha = 0.88f)) }
            )
        }
        // 触控层
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(valueRange) {
                    val applyAt: (Float) -> Unit = { x ->
                        val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + f * (valueRange.endInclusive - valueRange.start))
                    }
                    detectTapGestures { off -> applyAt(off.x) }
                }
                .pointerInput(valueRange) {
                    val applyAt: (Float) -> Unit = { x ->
                        val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + f * (valueRange.endInclusive - valueRange.start))
                    }
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        applyAt(change.position.x)
                    }
                }
        )
    }
}

/**
 * 玻璃分段控件（三段式：跟随系统 / 浅色 / 深色）。
 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp
) {
    val colors = LocalColors.current
    val count = options.size.coerceAtLeast(1)
    val shape = pillShape(height.value)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height(height),
        contentAlignment = Alignment.CenterStart
    ) {
        LiquidGlass(
            Modifier.matchParentSize(),
            shape = shape,
            showHighlight = false
        ) {}
        val segW = maxWidth / count
        val indicatorX = androidx.compose.animation.core.animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = Motion.segmented,
            label = "seg"
        )
        Box(
            Modifier
                // 用 lambda 重载：偏移量每帧都在变，非 lambda 版本会在每次重组时
                // 重新读取 state 并触发不必要的重排；lambda 版本把读取延迟到 layout 阶段。
                .offset { IntOffset((segW * indicatorX.value + 3.dp).roundToPx(), 0) }
                .width(segW - 6.dp)
                .height(height - 6.dp)
                .align(Alignment.CenterStart)
                .clip(pillShape(height.value))
                .drawBehind {
                    drawRect(Color.White.copy(alpha = if (colors.isDark) 0.20f else 0.88f))
                }
        )
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = LType.Caption,
                        color = if (i == selectedIndex) colors.text else colors.secondary
                    )
                }
            }
        }
    }
}

/**
 * 设置页分组卡片：LiquidGlass（XL 圆角）+ 竖向内容。
 */
@Composable
fun GlassGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalColors.current
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = LType.Caption,
                color = colors.secondary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
        // v1.5.0：设置分组属于**内容层**（承载可读信息），按 Apple HIG
        // 不能用折射玻璃 —— 折射会让文字边缘跟着底层一起弯曲，
        // 长列表滚动时整页都在扭。改走 GlassContentCard（纯模糊 + 底色）。
        GlassContentCard(
            Modifier.fillMaxWidth(),
            shape = Shapes2.XL
        ) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** 设置行内分隔线 */
@Composable
fun GlassDivider(startIndent: Dp = 16.dp) {
    val colors = LocalColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .drawBehind { drawRect(colors.separator) }
    )
}
