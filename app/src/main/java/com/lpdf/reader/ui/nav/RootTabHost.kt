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
            // 底部渐深（v1.7.2 加强）。
            //
            // 旧版是 `pageBg.copy(alpha = 0.55f)`，从 78% 高度开始渐入。
            // 问题在于它**只是一层 55% 的半透明色**：设置页的文字仍然
            // 原样透上来，和底栏的图标叠在一起（用户截图里「默认保存方式」
            // 直接压在「文档 / 书签」上）。
            //
            // 现在改成三段：
            //   ① 92% 的不透明底 —— 内容在底栏区域基本被压平
            //   ② 起止点上移到 0.62，给底栏留出足够的过渡带
            //   ③ 底栏本身另有 blur + 25%/55% 材料底色，两层叠加后
            //      对比度足够，不再需要靠「看清底下」来维持玻璃感
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to colors.pageBg.copy(alpha = 0f),
                            0.86f to colors.pageBg.copy(alpha = 0.72f),
                            1f to colors.pageBg
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
    //
    // v1.7.2：五个 Tab 全部是纯图标（iOS 26 的真实形态）。
    // 旧版每项都挂文字，中文两字在窄屏上必然压到相邻项；
    // iOS 只在当前项下方显示文字，所以这里的 label 只作为无障碍描述使用，
    // 是否显示由 GlassTabBar 内部按选中态决定。
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
            // 64dp 外筐（= iOS 26 的 tab bar 高度）。阅读页工具栏仍用 56dp 紧凑形态，
            // 两者的差异是有意的：工具栏只是一排瞬动按钮，不该有 tab bar 的体量。
            height = 64.dp,
            modifier = Modifier.padding(horizontal = LSpace.pageHorizontal)
        )
    }
}
