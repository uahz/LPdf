package com.lpdf.reader.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * iOS 26 风格玻璃底栏（v1.4.0）。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ ╭────────╮                                   │  ← 整条毛玻璃底板
 * │ │  ◉ 首页│  数码    ＋    发现    我的        │  ← 选中项外浮一个玻璃药丸
 * │ ╰────────╯                                   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## 与旧版的区别
 *
 * 旧底栏是「一排平铺按钮装进一个玻璃盒」。参考图的关键是**选中项有独立的
 * 玻璃药丸**：它有自己的折射、自己的边缘色散、自己在整条底板上"浮"起来一层。
 * 两条玻璃重叠处会互相折射，那圈彩色描边就是这么来的。
 *
 * ## 动效
 *
 * - 药丸滑块用弹簧追目标位置，**切换时不瞬移**，中间会带一点惯性
 * - 按压时缩小到 0.94，松手弹回（欠阻尼，有一点点过冲）
 * - 拖动底栏时药丸轻微滞后，产生"弹性跟随"的错觉
 */
@Composable
fun GlassTabBar(
    items: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 62.dp,
    /** 紧凑模式：只显示图标不显示文字（阅读页工具条） */
    compact: Boolean = false,
    /** 药丸是否跟随选中项；false 时只做按压反馈（如「上一页/下一页」这类按钮） */
    followSelection: Boolean = true
) {
    val colors = LocalColors.current
    val density = LocalDensity.current
    val heightPx = with(density) { height.toPx() }

    // 药丸滑块的实时位移。用 Animatable 而不是 animateDpAsState：
    // 拖动底栏时需要能"拖住"滑块不让弹簧立刻拉回去。
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val itemWidthPx = totalWidthPx / items.size

        val indicatorX = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }

        LaunchedEffect(selectedIndex, itemWidthPx, dragging) {
            if (!dragging) {
                indicatorX.animateTo(
                    targetValue = selectedIndex * itemWidthPx,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }

        LiquidGlass(
            modifier = Modifier.fillMaxSize(),
            shape = Shapes2.pill(height.value),
            showHighlight = true
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(itemWidthPx, items.size) {
                        // 横向拖动底栏 = 直接拖动药丸。
                        // iOS 的 tab bar 不允许拖动，但用户要的是「液态」手感 ——
                        // 手指划过时药丸跟着走，松手吸附到最近一项。
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                val target = (indicatorX.value / itemWidthPx)
                                    .roundToInt()
                                    .coerceIn(0, items.lastIndex)
                                onSelect(target)
                            },
                            onDragCancel = { dragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val raw = indicatorX.value + dragAmount
                            // 越界阻尼：拖出两端时不硬顶，而是逐渐变沉
                            val max = (items.size - 1) * itemWidthPx
                            val damped = when {
                                raw < 0f -> Motion.rubberBand(raw, itemWidthPx * 0.35f)
                                raw > max -> max + Motion.rubberBand(raw - max, itemWidthPx * 0.35f)
                                else -> raw
                            }
                            // snapTo 是挂起函数：拖动回调不能挂起，
                            // 甩到 scope 里执行，同时保证不排队堆积
                            scope.launch { indicatorX.snapTo(damped) }
                        }
                    }
            ) {
                // ① 选中药丸：浮动在原玻璃之上，位置由指示器驱动
                //
                // 这是 iOS 观感的核心：药丸自带一层玻璃，压在底板上方，
                // 两片玻璃重叠处的折射差异会自然产生那圈彩色边缘。
                if (followSelection) Box(
                    Modifier
                        .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                        .width(with(density) { itemWidthPx.toDp() })
                        .height(height)
                        .padding(4.dp)
                ) {
                    LiquidGlass(
                        modifier = Modifier.fillMaxSize(),
                        shape = Shapes2.pill(height.value - 8f),
                        refraction = 0.20f,
                        showHighlight = true,
                        pressed = dragging
                    ) {}
                }

                // ② 图标与文字：永远画在药丸之上
                Row(Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        TabItem(
                            item = item,
                            selected = followSelection && index == selectedIndex,
                            compact = compact,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            onClick = {
                                if (index != selectedIndex) onSelect(index)
                                else item.onReselect?.invoke()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 底栏一项的数据 */
data class GlassTab(
    val icon: LIcon,
    val label: String,
    /** 重复点击当前项时的动作（如滚动列表到顶） */
    val onReselect: (() -> Unit)? = null,
    /** 中央主按钮：图标渲染成实心圆底 + 白图标 */
    val primary: Boolean = false
)

@Composable
private fun TabItem(
    item: GlassTab,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按压回弹：0.94 压下去，松手用欠阻尼弹簧收回（略微过冲，像真的弹了一下）
    var scaleTarget by remember { mutableStateOf(1f) }
    LaunchedEffect(pressed) { scaleTarget = if (pressed) 0.90f else 1f }
    val scale = animateFloatSpring(scaleTarget, Motion.toggle)

    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.primary) {
            // 中央按钮：iOS 的「＋」是一个独立实心胶囊，不随选中态变
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                LIconView(item.icon, size = 20.dp, color = Color.White, strokeWidth = 2.2.dp)
            }
        } else if (compact) {
            // 紧凑模式：只有图标，选中用强调色 + 稍粗描边
            LIconView(
                item.icon,
                size = 21.dp,
                color = if (selected) colors.accent else colors.text,
                strokeWidth = if (selected) 2.2.dp else 1.7.dp
            )
        } else {
            LIconView(
                item.icon,
                size = 22.dp,
                color = if (selected) colors.accent else colors.text,
                strokeWidth = if (selected) 2.1.dp else 1.7.dp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.label,
                style = LType.Micro,
                color = if (selected) colors.accent else colors.secondary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** 弹簧驱动的 Float 值，避免每处都写 animateFloatAsState + spec */
@Composable
private fun animateFloatSpring(target: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>): Float {
    val v = remember { Animatable(target) }
    LaunchedEffect(target, spec) { v.animateTo(target, spec) }
    return v.value
}
