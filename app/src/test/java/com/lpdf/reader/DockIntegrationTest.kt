package com.lpdf.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.LPdfApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实界面里的底栏定位断言。
 *
 * 前面的单元测试只是复刻结构，无法证明**仓库里的代码**是对的。
 * 这里直接跑完整的 `LPdfApp` → Tab 宿主 → 覆盖层，
 * 用语义树找到底栏项，断言它落在屏幕下半部分。
 *
 * 修复前该断言会失败：Dock 被排在 overlay 的 y=0。
 *
 * v1.6.0 起底栏从「筛选胶囊」换成五 Tab（首页/文档/书签/发现/我的），
 * 定位基准随之从「全部」改成「文档」——它只出现在底栏一处，不会再和
 * 上方筛选条里的同名文字混淆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DockIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Tab 底栏应贴底且五个 Tab 齐全`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()

        val root = compose.onRoot().getBoundsInRoot()
        // root 来自 getBoundsInRoot()，是 DpRect；底栏项来自 boundsInRoot，是像素 Rect。
        // 统一换成像素再比，避免密度不同导致误判。
        val density = compose.density.density
        val screenHeight = root.bottom.value * density

        // ---- 1. 五个 Tab 都渲染出来了 ----
        // 首页的标题也叫「我的」以外的名字，所以这里逐个确认底栏项存在。
        // 「文档」不会被首页正文重复（首页不列出「文档」字样）。
        val labels = listOf("首页", "文档", "书签", "发现", "我的")
        labels.forEach { label ->
            val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            assertTrue("底栏缺少「$label」", nodes.isNotEmpty())
        }

        // ---- 2. 底栏落在屏幕下半部分 ----
        val dockNodes = compose.onAllNodesWithText("文档").fetchSemanticsNodes()
        assertTrue("底栏「文档」项未找到", dockNodes.isNotEmpty())
        // 「文档」在底栏只出现一次；若将来正文里也出现，取最靠下的那个更稳
        val dock = dockNodes.maxByOrNull { it.positionInRoot.y }!!
        val bounds = dock.boundsInRoot
        val dockCenter = (bounds.top + bounds.bottom) / 2f

        assertTrue(
            "屏幕高 $screenHeight，底栏「文档」中心 y=$dockCenter。" +
                "底栏落到了上半部分，说明它没有被贴底（overlay 坐标系问题）。",
            dockCenter > screenHeight / 2f
        )
    }

    @Test
    fun `五个 Tab 的底栏文字都在同一水平线上`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()

        // 五个标签应当横排；只要有一个 y 明显偏移，就说明底栏内部布局坏了
        val ys = listOf("首页", "文档", "书签", "发现", "我的").mapNotNull { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes()
                .maxByOrNull { it.positionInRoot.y }
                ?.boundsInRoot?.let { (it.top + it.bottom) / 2f }
        }
        assertEquals(5, ys.size)
        val spread = (ys.max() - ys.min()).absoluteValue
        assertTrue("五个 Tab 的中心 y 相差 $spread px，说明没有对齐", spread < 4f)
    }
}

private val Float.absoluteValue: Float get() = if (this < 0f) -this else this
