package com.lpdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.GlassLevel
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.glass.LocalGlassCapability
import com.lpdf.reader.ui.glass.glassCapabilityForTest
import com.lpdf.reader.ui.theme.LPdfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 玻璃降级渲染探测（v1.3.2）。
 *
 * 分成两条链路各测一遍：
 *
 * - **一级降级（BLUR_ONLY）**：不调用 `lens()`，只走 blur。
 *   这是 ROM 阉割 AGSL 时用户实际看到的形态。
 * - **二级降级（PLAIN）**：连 `drawBackdrop` 都不挂，纯 DrawScope 绘制。
 *   这是连 RenderEffect 都不可用时的最坏情况。
 *
 * 两者都必须能完成组合与首帧 —— 降级的意义就在于此：
 * 宁可玻璃难看，也不能崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassFallbackRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `BLUR_ONLY 级别可正常绘制`() {
        compose.setContent {
            LPdfTheme(dark = false) {
                CompositionLocalProvider(
                    LocalGlassCapability provides glassCapabilityForTest(GlassLevel.BLUR_ONLY)
                ) {
                    LiquidGlassScope(
                        content = {
                            Box(
                                Modifier
                                    .size(240.dp)
                                    .background(Color(0xFF3366CC))
                            )
                        },
                        overlay = { state ->
                            LiquidGlass(
                                modifier = Modifier.size(120.dp),
                                backdrop = state.backdrop
                            ) {
                                Box(Modifier.size(20.dp).background(Color.White))
                            }
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `PLAIN 级别可正常绘制`() {
        compose.setContent {
            LPdfTheme(dark = true) {
                CompositionLocalProvider(
                    LocalGlassCapability provides glassCapabilityForTest(GlassLevel.PLAIN)
                ) {
                    LiquidGlassScope(
                        content = {
                            Box(
                                Modifier
                                    .size(240.dp)
                                    .background(Color(0xFF222222))
                            )
                        },
                        overlay = { state ->
                            LiquidGlass(
                                modifier = Modifier.size(120.dp),
                                backdrop = state.backdrop
                            ) { }
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
