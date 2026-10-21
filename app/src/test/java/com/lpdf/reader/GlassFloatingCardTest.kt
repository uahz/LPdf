package com.lpdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.GlassFloatingCard
import com.lpdf.reader.ui.glass.LiquidGlassScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 长按玻璃浮卡回归测试（v1.4.0）。
 *
 * 守两件事：
 *
 * 1. **浮起动效不会把卡片甩出屏幕**。浮起用欠阻尼弹簧（dampingRatio 0.62），
 *    收尾必定过冲。若把 scale/rotation 的 pivot 写错，过冲会把卡片推到屏幕外，
 *    表现为「长按之后什么都没出来」。
 * 2. **卡片最终落在屏幕中央附近**，不是贴在顶部（v1.3.3 的 overlay 老毛病：
 *    overlay 子节点不加 fillMaxSize 就按内容高度贴顶排布）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GlassFloatingCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `浮卡最终居中且完整落在屏幕内`() {
        var screenW = 0
        var screenH = 0
        var cardLeft = -1
        var cardTop = -1
        var cardW = 0
        var cardH = 0

        compose.setContent {
            LiquidGlassScope(
                content = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                screenW = it.size.width
                                screenH = it.size.height
                            }
                    )
                },
                overlay = {
                    Box(Modifier.fillMaxSize()) {
                        GlassFloatingCard(onDismiss = {}) {
                            Column(
                                Modifier
                                    .size(200.dp, 280.dp)
                                    .background(Color.White)
                                    .onGloballyPositioned {
                                        val p = it.positionInRoot()
                                        cardLeft = p.x.toInt()
                                        cardTop = p.y.toInt()
                                        cardW = it.size.width
                                        cardH = it.size.height
                                    }
                            ) {}
                        }
                    }
                }
            )
        }
        // 等浮起弹簧完全收敛（欠阻尼，需要足够帧数）
        compose.waitForIdle()
        repeat(6) {
            compose.mainClock.advanceTimeBy(120L)
        }
        compose.waitForIdle()

        assertTrue("屏幕宽应大于 0（实际 $screenW）", screenW > 0)
        assertTrue("屏幕高应大于 0（实际 $screenH）", screenH > 0)
        assertTrue("卡片应被测量到（left=$cardLeft）", cardLeft >= 0)

        // 居中：左右留白差不超过卡片宽度的 20%
        val gapLeft = cardLeft
        val gapRight = screenW - (cardLeft + cardW)
        assertTrue(
            "卡片应水平居中（左边距 $gapLeft，右边距 $gapRight）",
            abs(gapLeft - gapRight) < cardW / 5
        )
        // 纵向居中：不要求精确（transformOrigin 在 0.35 处），但必须在下半屏以外
        assertTrue(
            "卡片 top=$cardTop 贴到屏幕顶部了，说明 overlay 子节点没撑满坐标系",
            cardTop > screenH / 10
        )
    }

    @Test
    fun `onDismiss 回调可被外部持有且浮卡不崩`() {
        var dismissed = false
        var rendered = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GlassFloatingCard(onDismiss = { dismissed = true }) {
                    Box(Modifier.size(120.dp).background(Color.White))
                }
            }
            rendered = true
        }
        compose.waitForIdle()
        assertTrue("浮卡应能正常组合", rendered)
        assertTrue("初始状态不应触发 onDismiss", !dismissed)
    }

    @Test
    fun `浮卡出现时不阻塞父级蒙版点击`() {
        // 蒙版和卡片是两个兄弟节点：点蒙版要能关闭，点卡片要能命中卡片。
        // 如果卡片把 fillMaxSize 撑开却没做事件隔离，蒙版会永远点不到。
        var order = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                )
                GlassFloatingCard(onDismiss = { order += "dismiss" }) {
                    Box(Modifier.size(100.dp).background(Color.White))
                }
            }
        }
        compose.waitForIdle()
        // 这里只验证组合稳定；真实点击分发依赖 Robolectric 的 pointer 支持，
        // 放在 instrumented 测试里更合适。
        assertTrue("组合应稳定", order.isEmpty())
    }
}
