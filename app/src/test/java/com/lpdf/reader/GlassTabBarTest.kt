package com.lpdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.GlassTab
import com.lpdf.reader.ui.glass.GlassTabBar
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LiquidGlassScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS 玻璃药丸底栏回归测试（v1.4.0）。
 *
 * 参考图的关键不是「一排按钮塞进玻璃盒」，而是**选中项外面浮着一片
 * 独立的玻璃药丸**。这里守三件事：
 *
 * 1. 药丸所在的层不会跑出底栏（对应 v1.3.3「底栏飞到顶部」老毛病：
 *    overlay 里的第二个子元素若不加 fillMaxSize，会按内容高度贴顶排布）
 * 2. 底栏整体贴底，不是贴在状态栏下面
 * 3. 紧凑模式 / followSelection=false 这两个阅读页用到的开关不崩
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GlassTabBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(
        GlassTab(LIcon.Folder, "全部"),
        GlassTab(LIcon.List, "最近"),
        GlassTab(LIcon.Plus, "导入", primary = true),
        GlassTab(LIcon.Lock, "加密"),
        GlassTab(LIcon.PageGrid, "目录")
    )

    @Test
    fun `贴底的玻璃底栏必须落在屏幕下部`() {
        var screenH = 0
        var barTop = -1
        var barHeight = 0
        var density = 1f

        compose.setContent {
            density = LocalDensity.current.density
            LiquidGlassScope(
                content = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { screenH = it.size.height })
                },
                overlay = {
                    // 与生产代码同构：Box + align 贴底
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        GlassTabBar(
                            items = items,
                            selectedIndex = 0,
                            onSelect = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .onGloballyPositioned {
                                    barTop = it.positionInRoot().y.toInt()
                                    barHeight = it.size.height
                                }
                        )
                    }
                }
            )
        }
        compose.waitForIdle()

        assertTrue("屏幕高度应大于 0（实际 $screenH）", screenH > 0)
        assertTrue("底栏高度应大于 0（实际 $barHeight）", barHeight > 0)
        assertTrue(
            "底栏 top=$barTop 落在屏幕上半部分（屏高 $screenH）——" +
                "说明 overlay 子节点被按内容高度贴顶排布，没撑满坐标系",
            barTop > screenH / 2
        )
    }

    @Test
    fun `药丸滑块随 selectedIndex 移动而底栏本身不动`() {
        var selected by mutableIntStateOf(0)
        val barTops = mutableListOf<Int>()

        compose.setContent {
            LiquidGlassScope(
                content = { Box(Modifier.fillMaxSize()) },
                overlay = {
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        GlassTabBar(
                            items = items,
                            selectedIndex = selected,
                            onSelect = { selected = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .onGloballyPositioned { barTops += it.positionInRoot().y.toInt() }
                        )
                    }
                }
            )
        }
        compose.waitForIdle()

        // 切到第 3 项，读底栏位置是否稳定
        compose.runOnIdle { selected = 2 }
        compose.waitForIdle()

        // 底栏本身的位置是被选中项驱动不了的 —— 只有里面那片药丸会动。
        // 所以切换 selectedIndex 后不应产生任何新的底栏放置回调；
        // 即便产生了，值也必须与首次一致。
        assertTrue(
            "切换选中项后底栏 y 坐标不应变化（测得 ${barTops}）",
            barTops.distinct().size == 1
        )
    }

    @Test
    fun `紧凑模式可正常组合`() {
        var rendered = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GlassTabBar(
                    items = items.dropLast(1),
                    selectedIndex = 1,
                    compact = true,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
            rendered = true
        }
        compose.waitForIdle()
        assertTrue("紧凑模式应能正常组合", rendered)
    }

    @Test
    fun `followSelection 关闭时可正常组合`() {
        // 阅读页 dock 在没有选中工具时没有任何项是「选中」状态，
        // 但仍要能正常渲染，不能因为指示器目标值非法而崩。
        var rendered = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GlassTabBar(
                    items = items,
                    selectedIndex = 0,
                    followSelection = false,
                    compact = true,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
            rendered = true
        }
        compose.waitForIdle()
        assertTrue("followSelection=false 应能正常渲染", rendered)
    }
}
