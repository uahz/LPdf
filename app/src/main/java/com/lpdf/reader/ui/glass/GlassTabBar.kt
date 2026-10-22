package com.lpdf.reader.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import kotlin.math.roundToInt

/**
 * iOS 26 液态玻璃底栏（v1.7.2 重做）。
 *
 * ```
 *  ┌─────────────────────────────────────────┐  64dp 外筐
 *  │ ╭─────────────────────────────────────╮ │  4dp 内边距
 *  │ │  ⌂      ▦      ◉      ♪             │ │  56dp 内层
 *  │ │ 首页   文档   书签   发现            │ │
 *  │ ╰─────────────────────────────────────╯ │
 *  └─────────────────────────────────────────┘
 *      （选中项下方压出一滴玻璃）
 * ```
 *
 * ## v1.7.2 为什么要整条重写
 *
 * 旧版是按「看起来差不多」猜的：常驻玻璃药丸 + 每项都挂文字 + 支持横向拖动。
 * 与 iOS 26 真机截图逐项对比后，有三处结构性错误：
 *
 * 1. **常驻药丸是错的**。iOS 26 的选中反馈是「按下时压出一滴玻璃」，
 *    松手即消散；选中态本身只有图标着色 + 微放大。常驻的一片实心药丸
 *    让整个底栏看起来像十年前的 TabLayout。
 * 2. **标签只给选中项**。iOS 的 tab bar 是纯图标，只有当前项显示文字。
 *    五项全挂文字，在 5 寸屏上直接糊成一片 —— 用户截图里的「文档」两个字
 *    直接压在设置页的正文上。
 * 3. **不接受横向拖动**。tab bar 不是滑块。拖动会让人以为可以连续切换，
 *    还会和系统边缘返回手势打架。
 *
 * ## 尺寸结构（对齐 Kyant0/LiquidBottomTabs）
 *
 * | 层 | 高度 | 圆角 |
 * |---|---|---|
 * | 外筐 | 64dp | 32dp 胶囊 |
 * | 内层 | 56dp | 28dp 胶囊 |
 *
 * ## 双层 backdrop 为什么必要
 *
 * 玻璃滴要折射的是**底栏自己**（图标与文字），不是底下的页面。
 * 所以底栏内部再录一层 layer backdrop，与外层页面的 backdrop 合成：
 * ```
 * combined = rememberCombinedBackdrop(pageBackdrop, tabBarLayerBackdrop)
 * ```
 * 只用一个 backdrop 时，玻璃滴下面直接透出页面内容，滴里空空如也。
 *
 * @param items 底栏项
 * @param selectedIndex 选中下标；-1 表示当前没有选中项
 * @param onSelect 点击回调
 * @param height 外筐高度；内层固定 = height - 8dp
 * @param compact 紧凑模式：整条不显示文字（阅读页工具栏）
 * @param followSelection 指示器是否跟随选中项滑动
 */
@Composable
fun GlassTabBar(
    items: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    compact: Boolean = false,
    followSelection: Boolean = true
) {
    val colors = LocalColors.current
    val density = LocalDensity.current
    val capability = LocalGlassCapability.current
    val pageBackdrop = LocalLiquidGlassScope.current?.backdrop

    val itemCount = items.size.coerceAtLeast(1)
    val barHeight = height
    val innerHeight = height - 8.dp

    // 底栏自己的内容层：玻璃滴要在它**之上**采样，才能折射出图标与文字
    val tabBarLayer = rememberGraphicsLayer()
    val tabBarBackdrop = rememberLayerBackdrop(tabBarLayer)
    val combined = rememberCombinedBackdrop(pageBackdrop ?: tabBarBackdrop, tabBarBackdrop)

    // 当前按下的项；-1 = 没有
    var pressedIndex by remember { mutableStateOf(-1) }
    // 玻璃滴的「鼓起程度」：0 = 静息，1 = 按到底
    val progress = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val itemWidthPx = totalWidthPx / itemCount
        val itemWidthDp = maxWidth / itemCount

        // 指示器中心 x（px）。随选中项弹簧滑动 —— 直接跳会让「液态」感消失
        val indicatorX = remember { Animatable(0f) }
        LaunchedEffect(selectedIndex, itemWidthPx, followSelection) {
            if (!followSelection || selectedIndex < 0) return@LaunchedEffect
            val target = (selectedIndex + 0.5f) * itemWidthPx
            if (indicatorX.value == 0f) {
                // 首帧直接落位，否则开屏会看到一滴玻璃从左边缘滑到当前项
                indicatorX.snapTo(target)
            } else {
                indicatorX.animateTo(
                    target,
                    spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
        LaunchedEffect(pressedIndex) {
            progress.animateTo(
                if (pressedIndex >= 0) 1f else 0f,
                tween(if (pressedIndex >= 0) 140 else 280)
            )
        }

        val outerShape = Shapes2.pill(barHeight.value)
        val innerShape = Shapes2.pill(innerHeight.value)

        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    tabBarLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tabBarLayer)
                }
        ) {
            // ---------------- 外筐：整条玻璃底板 ----------------
            //
            // 视觉层次自上而下：
            //   ① drawBackdrop：vibrancy + blur + 边缘 lens（库负责）
            //   ② onDrawSurface：半透明底色 —— 这一层是「内容不再透过来」的关键。
            //      旧版只在 drawBehind 里叠 12% + 20% 白，在深色底上几乎等于透明，
            //      设置页的正文会原样从底栏里透出来（用户截图）。
            //   ③ 边缘描边：极细亮上缘 + 暗下缘，给出玻璃的厚度
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (pageBackdrop != null && capability.level != GlassLevel.PLAIN) {
                            Modifier.drawBackdrop(
                                backdrop = pageBackdrop,
                                shape = { outerShape },
                                effects = {
                                    runCatching { vibrancy() }
                                    runCatching { blur(with(density) { 12.dp.toPx() }) }
                                    runCatching {
                                        lens(
                                            with(density) { 24.dp.toPx() },
                                            with(density) { 24.dp.toPx() },
                                            /* depthEffect = */ true,
                                            /* chromaticAberration = */ true
                                        )
                                    }
                                },
                                highlight = {
                                    Highlight(
                                        width = 1.4.dp,
                                        blurRadius = 5.dp,
                                        alpha = 0.5f,
                                        style = HighlightStyle.Default(
                                            intensity = 0.5f,
                                            angle = 135f,
                                            falloff = 0.6f
                                        )
                                    )
                                },
                                shadow = { Shadow.Default },
                                onDrawSurface = {
                                    drawRect(materialTint(colors.isDark))
                                }
                            )
                        } else {
                            Modifier.drawWithContent {
                                drawRect(
                                    if (colors.isDark) Color(0xFF26262A) else Color(0xFFF7F7F9)
                                )
                                drawContent()
                            }
                        }
                    )
                    .drawWithContent {
                        drawContent()
                        // 外圈描边：亮上缘（从 40% 白渐变收尾）+ 极淡下缘暗线
                        drawOutline(
                            outline = outerShape.createOutline(size, layoutDirection, this),
                            color = if (colors.isDark) Color.White.copy(alpha = 0.14f)
                            else Color.White.copy(alpha = 0.55f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            )

            // ---------------- 内层轮廓 ----------------
            // iOS 的底栏其实是「两片玻璃叠着」：外圈负责与页面接壤的折射，
            // 内圈负责内容区的亮度整理。少这一层会显得平板。
            Box(
                Modifier
                    .padding(4.dp)
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawOutline(
                            outline = innerShape.createOutline(size, layoutDirection, this),
                            color = if (colors.isDark) Color.White.copy(alpha = 0.05f)
                            else Color.White.copy(alpha = 0.30f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            )

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                items.forEachIndexed { index, item ->
                    TabItem(
                        item = item,
                        selected = index == selectedIndex,
                        // iOS：只有选中项显示文字；紧凑模式一个都不显示
                        showLabel = !compact && index == selectedIndex,
                        onPressChange = { down -> pressedIndex = if (down) index else -1 },
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .width(itemWidthDp - 8.dp)
                            .fillMaxSize()
                    )
                }
            }

            // ---------------- 玻璃滴 ----------------
            //
            // 只在按压时真正「鼓」起来；静息时以 0.35 的弱透镜存在，
            // 让选中项有一层极淡的玻璃轮廓 —— 这是 iOS 26 的 Resting 形态。
            val active = when {
                pressedIndex >= 0 -> pressedIndex
                followSelection && selectedIndex in items.indices -> selectedIndex
                else -> -1
            }
            if (active >= 0 && capability.level != GlassLevel.PLAIN) {
                // 跟随时用 indicatorX（滑动动画），按压时用按下的位置
                val centerX = when {
                    pressedIndex >= 0 && !followSelection -> (pressedIndex + 0.5f) * itemWidthPx
                    indicatorX.value > 0f -> indicatorX.value
                    else -> (active + 0.5f) * itemWidthPx
                }
                val dropWidth = itemWidthDp - 12.dp
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (centerX - dropWidth.toPx() / 2f).roundToInt(),
                                TAB_LABEL_HEIGHT.roundToPx() / 2
                            )
                        }
                        .width(dropWidth)
                        .height(innerHeight - 4.dp)
                        .drawBackdrop(
                            backdrop = combined,
                            shape = { Shapes2.pill((innerHeight.value - 4f) / 2f) },
                            effects = {
                                val grow = lerp(0.35f, 1f, progress.value)
                                runCatching {
                                    lens(
                                        with(density) { 16.dp.toPx() * grow },
                                        with(density) { 14.dp.toPx() * grow },
                                        /* depthEffect = */ true,
                                        /* chromaticAberration = */ true
                                    )
                                }
                            },
                            highlight = {
                                Highlight(
                                    width = 1.2.dp,
                                    blurRadius = 4.dp,
                                    alpha = 0.12f + 0.5f * progress.value,
                                    style = HighlightStyle.Default(
                                        intensity = 0.30f + 0.5f * progress.value,
                                        angle = 135f,
                                        falloff = 0.55f
                                    )
                                )
                            },
                            onDrawSurface = {
                                drawRect(
                                    Color.White.copy(
                                        alpha = if (colors.isDark) {
                                            0.015f + 0.09f * progress.value
                                        } else {
                                            0.04f + 0.20f * progress.value
                                        }
                                    )
                                )
                            }
                        )
                )
            }
        }
    }
}

/**
 * 底栏「材料」底色。
 *
 * 这不是随便挑的灰，而是 iOS 26 液态玻璃在两种模式下的等效不透明度：
 * - 浅色：25% 白。叠在 blur(12dp) + vibrancy 之上，底层文字被糊成色块，
 *   图标对比度仍 ≥ 4.5:1。
 * - 深色：55% 黑。深色底几乎不散射光，需要更多吸收层才能压住白底 PDF 的透出。
 */
private fun materialTint(dark: Boolean): Color =
    if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.25f)

/** 选中项文字标签的行高。固定住，保证图标在两种状态下垂直位置一致 */
private val TAB_LABEL_HEIGHT = 12.dp

/** 底栏一项的数据 */
data class GlassTab(
    val icon: LIcon,
    val label: String,
    /** 重复点击当前项时的动作（如滚动列表到顶） */
    val onReselect: (() -> Unit)? = null,
    /** 中央主按钮：图标渲染成实心圆底 + 白图标 */
    val primary: Boolean = false
)

/**
 * 单个 Tab 项。
 *
 * 交互只保留 iOS tab bar 真实存在的反馈：按下高光 + 0.92 微缩，
 * 松手欠阻尼回弹（略微过冲）。不拦截拖拽 —— 横向滑动交还给系统手势。
 */
@Composable
private fun TabItem(
    item: GlassTab,
    selected: Boolean,
    showLabel: Boolean,
    onPressChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalColors.current
    val latestClick by rememberUpdatedState(onClick)
    val latestPress by rememberUpdatedState(onPressChange)

    var pressed by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        scale.animateTo(
            if (pressed) 0.92f else 1f,
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
        )
    }
    // 选中态：图标轻微放大（1.0 → 1.06）+ 着色。没有常驻背景块。
    val emphasis = remember { Animatable(if (selected) 1.06f else 1f) }
    LaunchedEffect(selected) {
        emphasis.animateTo(
            if (selected) 1.06f else 1f,
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
        )
    }

    val tint = when {
        item.primary -> Color.White
        selected -> colors.accent
        else -> colors.text.copy(alpha = 0.70f)
    }

    Column(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        latestPress(true)
                        tryAwaitRelease()
                        pressed = false
                        latestPress(false)
                    },
                    onTap = { latestClick() }
                )
            }
            .graphicsLayer {
                scaleX = scale.value * emphasis.value
                scaleY = scale.value * emphasis.value
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.primary) {
            // 中央主按钮：独立实心圆，不随选中态变（iOS 的「＋」）
            Box(
                Modifier
                    .size(34.dp)
                    .drawWithContent {
                        drawCircle(colors.accent)
                        drawContent()
                    },
                contentAlignment = Alignment.Center
            ) {
                LIconView(item.icon, size = 18.dp, color = Color.White, strokeWidth = 2.2.dp)
            }
            return@Column
        }

        // 图标区高度改用 weight 分配：外层 Column 已经居中（verticalArrangement
        // = Center），固定高度会让「有标签 / 没标签」两种状态的重心不同，
        // 切换 Tab 时整排图标会上下跳一下。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LIconView(
                item.icon,
                // 纯图标形态下，文字不再是「看得见的信息」，
                // 必须挂到无障碍描述上，否则读屏只能念出「按钮」。
                modifier = Modifier.semantics { contentDescription = item.label },
                size = if (selected) 24.dp else 23.dp,
                color = tint,
                strokeWidth = if (selected) 2.0.dp else 1.7.dp
            )
        }

        if (showLabel) {
            Text(
                item.label,
                style = LType.Micro,
                color = colors.accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.height(TAB_LABEL_HEIGHT)
            )
        } else {
            Spacer(Modifier.height(TAB_LABEL_HEIGHT))
        }
    }
}
