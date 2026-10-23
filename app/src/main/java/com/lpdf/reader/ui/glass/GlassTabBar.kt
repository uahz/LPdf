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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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
 * iOS 26 悬浮玻璃底栏（v1.8.1 整体重写）。
 *
 * ```
 *      ╭─────────────────────────────────────╮  ← 悬浮胶囊（左右留边、底部离导航条 12dp）
 *      │  ⌂     ▦     ◉     ♪     ☀          │
 *      │ 首页   文档   书签   发现   我的       │
 *      ╰─────────────────────────────────────╯
 *           （选中项：胶囊高亮块滑到该格正中）
 * ```
 *
 * ## v1.8.1 为什么推倒重写
 *
 * 旧版（v1.7.2）的选中指示器是一颗「玻璃滴」，它的定位公式有两处硬伤，
 * 真机上表现就是用户截图里的**选中卡片溢出底栏、压到相邻图标、文字悬在栏外**：
 *
 * 1. 垂直偏移写死 `TAB_LABEL_HEIGHT / 2`，滴的位置整体下坠，超出外筐；
 * 2. 滴的尺寸（itemWidth - 12dp × innerHeight - 4dp）和文字槽的布局彼此独立计算，
 *    任何一处取整误差都会让两者错位。
 *
 * 新版把指示器改成**一个普通的胶囊色块**，用与 Tab 槽完全相同的算术定位：
 * `x = index * itemWidth + (itemWidth - pillWidth) / 2`、垂直严格居中。
 * 数值上不可能再溢出，也不再依赖 backdrop lens 的渲染结果——
 * 玻璃折射只留给外筐本体，指示器用实色 + 描边，任何设备上都一致。
 *
 * ## 悬浮形态
 *
 * 宽度 = 屏宽 - 2 × pageHorizontal（调用方给），高度 64dp，
 * 底部离系统导航条 12dp（TabDock 负责）——就是 iOS 26 的悬浮 tab bar。
 *
 * @param items 底栏项
 * @param selectedIndex 选中下标；-1 表示当前没有选中项
 * @param onSelect 点击回调
 * @param height 外筐高度
 * @param compact 紧凑模式：整条不显示文字（阅读页工具栏）
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
    val innerHeight = height - 8.dp

    // 底栏自己的内容层：外筐玻璃要折射出图标与文字
    val tabBarLayer = rememberGraphicsLayerSafe()
    val tabBarBackdrop = rememberLayerBackdrop(tabBarLayer)

    // 指示器中心 x（px）。随选中项弹簧滑动 —— 直接跳会让「液态」感消失
    val indicatorX = remember { Animatable(0f) }
    var pressedIndex by remember { mutableStateOf(-1) }
    val pressProgress = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val itemWidthPx = totalWidthPx / itemCount
        val itemWidthDp = maxWidth / itemCount

        LaunchedEffect(selectedIndex, itemWidthPx, followSelection) {
            if (!followSelection || selectedIndex < 0) return@LaunchedEffect
            val target = (selectedIndex + 0.5f) * itemWidthPx
            if (indicatorX.value == 0f) {
                // 首帧直接落位，否则开屏会看到高亮块从左边缘滑到当前项
                indicatorX.snapTo(target)
            } else {
                indicatorX.animateTo(
                    target,
                    spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
        LaunchedEffect(pressedIndex) {
            pressProgress.animateTo(
                if (pressedIndex >= 0) 1f else 0f,
                tween(if (pressedIndex >= 0) 140 else 280)
            )
        }

        val outerShape = Shapes2.pill(height.value)

        // ---------------- 指示器几何（与 Tab 槽同一套算术，保证严格对齐） ----------------
        //
        // 槽：x ∈ [i*w, (i+1)*w]，垂直占满内层。
        // 指示器：宽 itemWidth - 12dp，高 innerHeight - 8dp，在槽内水平居中、垂直居中。
        // 没有第二个坐标系、没有写死的偏移 —— 不可能再溢出。
        // v1.8.6：胶囊收敛为「刚好包住图标+文字」的紧凑尺寸。
        // v1.8.4 放大到 itemWidth-8dp × innerHeight-4dp 后，5 个 Tab 的槽宽
        // 本来就 ~70dp，胶囊几乎占满整槽 —— 真机上是一颗压到相邻图标的大椭圆。
        // 现在宽 itemWidth-22dp、高 44dp，只包住内容本身。
        val pillWidth = itemWidthDp - 22.dp
        val pillHeight = 44.dp
        val pillCenterX = when {
            pressedIndex >= 0 && !followSelection -> (pressedIndex + 0.5f) * itemWidthPx
            indicatorX.value > 0f -> indicatorX.value
            selectedIndex >= 0 -> (selectedIndex + 0.5f) * itemWidthPx
            else -> 0f
        }
        val pillLeft = pillCenterX - with(density) { pillWidth.toPx() } / 2f
        val pillTop = with(density) { ((height - pillHeight) / 2f).toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    tabBarLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tabBarLayer)
                }
        ) {
            // ---------------- 外筐：整条玻璃底板（悬浮胶囊） ----------------
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
                                            with(density) { 20.dp.toPx() },
                                            with(density) { 20.dp.toPx() },
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
                        drawOutline(
                            outline = outerShape.createOutline(size, layoutDirection, this),
                            color = if (colors.isDark) Color.White.copy(alpha = 0.14f)
                            else Color.White.copy(alpha = 0.55f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            )

            // ---------------- 选中指示器（胶囊高亮块） ----------------
            //
            // 画在图标层之下，颜色用 accent 的淡色 + 极细描边。
            // 跟随 indicatorX 弹簧滑动；按压其它项时按压缩放反馈。
            if (pillCenterX > 0f) {
                val scale = lerp(1f, 0.94f, pressProgress.value)
                Box(
                    Modifier
                        .offset { IntOffset(pillLeft.roundToInt(), pillTop.roundToInt()) }
                        .width(pillWidth)
                        .height(pillHeight)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.6f + 0.4f * (1f - pressProgress.value)
                        }
                        .drawWithContent {
                            val r = pillHeight.toPx() / 2f
                            drawRoundRect(
                                color = colors.accent.copy(
                                    alpha = if (colors.isDark) 0.24f else 0.15f
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                            )
                        }
                )
            }

            // ---------------- Tab 项 ----------------
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                items.forEachIndexed { index, item ->
                    TabItem(
                        item = item,
                        selected = index == selectedIndex,
                        // iOS 26：选中项显示文字（在栏内、图标下方），其余纯图标
                        showLabel = !compact && index == selectedIndex,
                        onPressChange = { down -> pressedIndex = if (down) index else -1 },
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .width(itemWidthDp - 8.dp)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 底栏「材料」底色。
 *
 * 浅色：25% 白（叠在 blur 之上，图标对比度 ≥ 4.5:1）；
 * 深色：55% 黑（深色底几乎不散射光，需要更多吸收层压住白底 PDF 的透出）。
 */
private fun materialTint(dark: Boolean): Color =
    if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.25f)

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
 * 图标与文字都严格排布在**本槽内**：Column 居中，图标占 weight(1f)，
 * 文字槽固定 12dp（不显示时用等高 Spacer 占位，两种状态重心一致）。
 * 交互只保留 iOS 真实存在的反馈：按压缩放 + 选中微放大。
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
    // 选中态：图标轻微放大 + 着色
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

        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LIconView(
                item.icon,
                // 纯图标形态下，文字挂到无障碍描述上，否则读屏只能念出「按钮」
                modifier = Modifier.semantics { contentDescription = item.label },
                size = if (selected) 24.dp else 23.dp,
                color = tint,
                strokeWidth = if (selected) 2.0.dp else 1.7.dp
            )
        }

        Spacer(Modifier.height(2.dp))

        // v1.8.6：文字始终占位（alpha 切换），且**整组图标+文字垂直居中**。
        // 旧版图标区 weight(1f) 拉满高度、文字落到槽底 —— 文字永远贴着栏底边。
        // 现在不拉伸，icon+2dp+文字作为一组居中；未选中时文字透明占位，
        // 所有 Tab 的图标位置恒定，选中时整组也不跳动。
        Text(
            item.label,
            style = LType.Micro,
            color = colors.accent,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = if (showLabel) 1f else 0f }
        )
    }
}

/** rememberGraphicsLayer 在无玻璃能力的设备上可能不可用，这里兜底 */
@Composable
private fun rememberGraphicsLayerSafe() = androidx.compose.ui.graphics.rememberGraphicsLayer()
