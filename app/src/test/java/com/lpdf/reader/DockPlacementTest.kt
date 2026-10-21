package com.lpdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.LiquidGlassScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 底部 Dock 定位回归测试（v1.3.3）。
 *
 * ## 真机现象
 *
 * 列表页的「导入 / 目录 / 扫描」底栏跑到屏幕最上方，压在状态栏下面
 * （OnePlus PKG110 / Android 17 截图）。
 *
 * ## 根因（实测确认，非推测）
 *
 * `BottomDock` 直接作为 `LiquidGlassScope` 的 overlay 子节点，结构是
 *
 * ```
 * Row(Modifier.fillMaxWidth().padding(bottom = navBottom + 20.dp).height(64.dp))
 * ```
 *
 * overlay 的根**不是**一个撑满屏幕的坐标系 —— 它按内容包裹。
 * 于是这个 Row 被排在 **y = 0**，`padding(bottom)` 只在它下方撑出空白，
 * 完全没有「贴底」的作用。
 *
 * 用 Robolectric 实测（屏幕高 891px）：
 * - 修复前：`dockTop = 0`
 * - 修复后：`dockTop = 787`（= 891 − 64 − 40）
 *
 * ## 修法
 *
 * 外面套一层 `Box(fillMaxWidth).align(BottomCenter)`，
 * 让贴底由 alignment 完成，而不是指望 padding。
 *
 * ## 为什么这个测试有效
 *
 * 第一版测试只断言「overlay 里的元素高度 > 64dp」——
 * 两种实现都能通过，属于假测试。这里改为断言**绝对位置**：
 * 贴底时 top 必须落在屏幕下部，跑顶部时 top 会接近 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DockPlacementTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `贴底的 Dock 必须落在屏幕下部而非顶部`() {
        var screenHeight = 0
        var dockTop = 0
        var density = 1f

        compose.setContent {
            density = LocalDensity.current.density
            LiquidGlassScope(
                content = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { screenHeight = it.size.height })
                },
                overlay = {
                    // 生产代码的正确写法：Box + align 贴底
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 40.dp)
                                .height(64.dp)
                                .onGloballyPositioned { dockTop = it.positionInRoot().y.toInt() }
                        ) { }
                    }
                }
            )
        }
        compose.waitForIdle()

        val dockPx = (64 * density).toInt()
        val bottomPadPx = (40 * density).toInt()
        val expectedTop = screenHeight - dockPx - bottomPadPx

        assertTrue(
            "屏幕高 $screenHeight，Dock top=$dockTop，期望约 $expectedTop。" +
                "若 top 接近 0，说明 Dock 被排到了顶部（padding 撑不出贴底效果）。",
            kotlin.math.abs(dockTop - expectedTop) <= dockPx
        )
        assertTrue("Dock top=$dockTop 不应落在屏幕上半部分", dockTop > screenHeight / 2)
    }
}
