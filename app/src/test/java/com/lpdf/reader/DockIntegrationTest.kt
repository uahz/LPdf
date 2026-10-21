package com.lpdf.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
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
        // v1.4.0 起底栏是 iOS 玻璃药丸：中央的「导入」是纯图标主按钮，
        // 不再渲染文字标签。改用同一条底栏里的「全部」来定位 ——
        // 它和「导入」同高同排，位置断言等价。
        // 筛选条上也有一个「全部」，所以要按 y 坐标挑出底栏里那个：
        // 底栏项是 selectable，语义上带 Selected 标记；这里用位置断言
        // 更直接 —— 取所有「全部」里最靠下的那个。
        val allNodes = compose.onAllNodesWithText("全部").fetchSemanticsNodes()
        val dock = allNodes.maxByOrNull { it.positionInRoot.y }
            ?: error("底栏「全部」项未找到")
        val bounds = dock.boundsInRoot

        // root 来自 getBoundsInRoot()，是 DpRect；dock 来自 boundsInRoot，是像素 Rect。
        // 统一换成像素再比，避免密度不同导致误判。
        val density = compose.density.density
        val screenHeight = root.bottom.value * density
        val dockCenter = (bounds.top + bounds.bottom) / 2f

        assertTrue(
            "屏幕高 $screenHeight，底栏「全部」中心 y=$dockCenter。" +
                "底栏落到了上半部分，说明它没有被贴底（overlay 坐标系问题）。",
            dockCenter > screenHeight / 2f
        )
    }
}
