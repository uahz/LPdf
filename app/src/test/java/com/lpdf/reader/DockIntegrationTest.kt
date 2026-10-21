package com.lpdf.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.LPdfApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实界面里的 Dock 定位断言（v1.3.3）。
 *
 * 前面的单元测试只是复刻结构，无法证明**仓库里的代码**是对的。
 * 这里直接跑完整的 `LPdfApp` → 列表页 → 覆盖层，
 * 用语义树找到「导入」按钮，断言它落在屏幕下半部分。
 *
 * 修复前该断言会失败：Dock 被排在 overlay 的 y=0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DockIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `列表页底栏的导入按钮应位于屏幕下半部分`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()

        val root = compose.onRoot().getBoundsInRoot()
        val node = compose.onNodeWithText("导入")
        val bounds = node.getBoundsInRoot()

        val screenHeight = root.bottom.value
        val dockCenter = (bounds.top.value + bounds.bottom.value) / 2f

        assertTrue(
            "屏幕高 $screenHeight，导入按钮中心 y=$dockCenter。" +
                "底栏落到了上半部分，说明它没有被贴底（overlay 坐标系问题）。",
            dockCenter > screenHeight / 2f
        )
    }
}
