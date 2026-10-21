package com.lpdf.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.LPdfApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 完整首屏渲染回归测试（v1.3.1）。
 *
 * 这是「点开应用立刻消失」那个崩溃的守门员：
 * 修复前，只要 `LPdfApp` 组合到第一个 `LiquidGlass`，
 * 玻璃库的 `lens()` 就会因为 shape 不是 `CornerBasedShape` 抛
 * `UnsupportedOperationException`，导致白屏或进程退出。
 *
 * 现在能走到 `waitForIdle()` 正常结束，说明整条首屏绘制链路是通的。
 *
 * 依赖 JDK 21 运行（玻璃库编译在 class file 65）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullScreenRenderProbeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `完整的 LPdfApp 可完成组合与首帧`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()
        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()
    }
}
