package com.lpdf.reader.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.GlassTab
import com.lpdf.reader.ui.glass.GlassTabBar
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 五 Tab 宿主（v1.6.0）。
 *
 * ```
 * ┌────────────────────────────────┐
 * │                                │
 * │        当前 Tab 的内容          │  ← content 槽，整块由调用方给
 * │                                │
 * │   ╭──────────────────────────╮ │
 * │   │ 首页 文档 [书签] 发现 我的│ │  ← 玻璃底栏，药丸跟着选中项滑
 * │   ╰──────────────────────────╯ │
 * └────────────────────────────────┘
 * ```
 *
 * ## 三个设计决定
 *
 * ① **内容层和底栏在同一个 [LiquidGlassScope] 里**
 * 底栏要折射出下面的内容，就必须和内容在同一个采样源。
 * 如果底栏放进 NavHost 外面单独一层，它采到的会是空的。
 *
 * ② **转场方向跟着 Tab 序号走**
 * 从「文档」切到「书签」向左滑，反过来向右滑。
 *
 * ③ **转场只做位移 + 淡入，不做缩放**
 * 缩放会让下面的内容「跳一下尺寸」，而底栏玻璃是实时采样的 ——
 * 采样源一变形，玻璃里的倒影也跟着抽搐。位移 + 淡入没有这个问题。
 */
@Composable
fun RootTabHost(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    onReselect: (RootTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (RootTab) -> Unit
) {
    val colors = LocalColors.current
    // 记录上一次的 Tab 序号，用来判断转场方向（向后翻 = 新序号更大）。
    // 用 saveable 存序号而不是枚举名：进程被回收后恢复时不需要再解析字符串。
    var previousOrdinal by rememberSaveable { mutableStateOf(selected.ordinal) }
    val forward = selected.ordinal >= previousOrdinal
    // 在副作用里记录，不能在组合过程中写状态（会触发 Recompose 循环）
    LaunchedEffect(selected) { previousOrdinal = selected.ordinal }

    LiquidGlassScope(
        modifier = modifier.background(colors.pageBg),

        // -------- L2：被玻璃采样的内容层 --------
        content = {
            // AnimatedContent 在切换时会把旧页面整个移出组合，
            // remember 的状态随之销毁 —— 表现就是「切回文档页，列表滚回顶部了」，
            // 搜索词、筛选条件也一起丢。SaveableStateHolder 按 key 把每个 Tab 的
            // 状态存起来，切回来时原样恢复。
            val holder = rememberSaveableStateHolder()

            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    val slide = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 260)
                    (slideInHorizontally(slide) { w -> dir * w / 6 } + fadeIn(tween(200)))
                        .togetherWith(
                            slideOutHorizontally(slide) { w -> -dir * w / 6 } + fadeOut(tween(160))
                        )
                },
                label = "tabContent",
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                holder.SaveableStateProvider(tab.route) {
                    content(tab)
                }
            }
        },

        // -------- L4：玻璃底栏 --------
        overlay = {
            // 底部渐深：白底 PDF 缩略图会从底栏边缘透上来，压一层让文字更清楚。
            // 从 78% 高度开始，避免覆盖到内容区的可读范围。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.78f to Color.Transparent,
                            1f to colors.pageBg.copy(alpha = 0.55f)
                        )
                    )
            )
            TabDock(
                selected = selected,
                onSelect = onSelect,
                onReselect = onReselect,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    )
}

@Composable
private fun TabDock(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    onReselect: (RootTab) -> Unit,
    modifier: Modifier = Modifier
) {
    // 不要 remember{}：GlassTab 里存的是 onReselect 闭包，
    // 一旦 remember 住，宿主重组后传进来的新回调就再也进不去了 ——
    // 表现为「重复点 Tab 没反应」，而且极难排查。
    // 这里只有 5 个对象，每帧重建的开销可以忽略。
    val items = RootTab.bar.map { tab ->
        GlassTab(
            icon = tab.icon,
            label = tab.label,
            onReselect = { onReselect(tab) }
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
            )
    ) {
        GlassTabBar(
            items = items,
            selectedIndex = selected.ordinal,
            onSelect = { i ->
                RootTab.bar.getOrNull(i)?.let { if (it != selected) onSelect(it) }
            },
            modifier = Modifier.padding(horizontal = LSpace.pageHorizontal)
        )
    }
}
