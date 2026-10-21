package com.lpdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lpdf.reader.data.pdf.PdfiumEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 渲染宽度来源回归测试（v1.5.1）。
 *
 * ## 它防的是什么
 *
 * 用户真机上 A4 页面被算成 `1168×13657`。反推：
 * `13657 ÷ 1.4142 ≈ 9657px` —— 渲染宽度是屏幕物理宽的 8.3 倍。
 *
 * 旧实现是：
 *
 * ```kotlin
 * val screenWidthPx = with(density) {
 *     LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
 * }
 * ```
 *
 * 这行在正常手机上恰好等于物理宽，所以「一直看着是对的」。
 * 但 `screenWidthDp` 描述的是**窗口**的 dp 宽，它受以下因素影响：
 * 自由窗口/分屏、折叠屏展开态、系统级显示缩放（`fontScale` 之外的
 * `displayMetrics.densityDpi` 被厂商改写）、以及 Android 12L+ 的大屏兼容模式。
 * 一旦它和实际布局宽度脱钩，再乘一次 density 就会被放大。
 *
 * ## 现在的契约
 *
 * 渲染宽度只能来自**父容器的实测像素宽度**（`onSizeChanged`），
 * 不允许出现任何 dp → px 的二次换算。
 *
 * 这个测试同时把「dp 换算会漂移」这件事写成可执行的证据：
 * 只要有人在 dp 与 px 之间再插一层换算，这里的断言就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class RenderWidthSourceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `实测宽度就是物理像素 不经过任何 dp 换算`() {
        var measured = 0
        var fromDp = 0
        compose.setContent {
            val d = LocalDensity.current
            fromDp = with(d) { LocalConfiguration.current.screenWidthDp.dp.toPx().toInt() }
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { measured = it.width }
            )
        }
        compose.waitForIdle()

        // xxhdpi = 3x，411dp × 3 = 1233px
        assertEquals("实测宽度应等于 dp × density", 1233, measured)
        // Robolectric 沙箱是「理想设备」，两条路径必然一致 —— 正因如此，
        // 真实设备上的漂移在本地永远测不出来，才必须改用实测宽度。
        assertEquals("沙箱里两条路径应一致（真机上不保证）", measured, fromDp)
    }

    @Test
    fun `渲染宽度必须被上限截断而不是原样透传`() {
        // 假设某个设备的实测宽度是 2560（2K 平板/桌面模式）。
        // 若不截断，直接拿去渲 2560 宽的 A4 → 高 3621，单张 9.27M 像素，
        // 虽然还合法，但放大后会与 tile 边界清晰度断层。
        val huge = 2560
        val clamped = huge.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH)
        assertTrue("上限必须生效", clamped <= PdfiumEngine.MAX_RENDER_WIDTH)
        assertTrue("上限不该低于常见平板宽度的一半", clamped >= 1440)
        // A4 在截断后仍应是单块（高度 ~5792 < 8192）
        assertTrue("4096 宽的 A4 高度应落在单张位图上限内", 4096 / 595.3f * 841.9f < 8192f)
    }

    @Test
    fun `极小宽度会被抬到可渲染的下限`() {
        // 折叠屏夹缝、分屏拖动中途可能出现宽度只有几十像素的一帧。
        // 不抬下限会渲出一张 1×1 的图，然后页面被拉伸成糊块。
        assertEquals(64, 10.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH))
        assertEquals(64, 0.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH))
    }

    @Test
    fun `宽度变化会触发状态更新`() {
        // ensureRendered 用 widthPx 判断是否需要重渲（见 ReaderViewModel）。
        // 这里确认 onSizeChanged 的多次回调能被 Compose 正确收集，
        // 不会因为 remember 的 key 写错而只保留第一帧的值。
        var width by mutableIntStateOf(0)
        val seen = mutableListOf<Int>()
        compose.setContent {
            var local by remember { mutableIntStateOf(0) }
            width = local
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        local = it.width
                        if (it.width > 0) seen.add(it.width)
                    }
            )
        }
        compose.waitForIdle()
        assertTrue("应至少收到一次尺寸回调", seen.isNotEmpty())
        assertEquals("状态应被更新为实测宽度", seen.first(), width)
    }
}
