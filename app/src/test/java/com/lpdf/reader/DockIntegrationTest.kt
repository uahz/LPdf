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

    /**
     * 取底栏某一项的中心 y（像素）。
     *
     * v1.7.2 起底栏是 iOS 26 形态：**纯图标 + 只有选中项显示文字**。
     * 所以不能再靠 `onAllNodesWithText` 找五项 —— 未选中的项没有文字节点。
     * 改为用图标的 contentDescription 定位（[TabItem] 的 `LIconView` 会带出
     * 无障碍描述），文本匹配只作为兜底。
     */
    private fun tabCenterY(label: String): Float? {
        val byDesc = compose.onAllNodes(
            androidx.compose.ui.test.hasContentDescription(label)
        ).fetchSemanticsNodes()
        val node = byDesc.maxByOrNull { it.positionInRoot.y }
            ?: compose.onAllNodesWithText(label).fetchSemanticsNodes()
                .maxByOrNull { it.positionInRoot.y }
            ?: return null
        val b = node.boundsInRoot
        return (b.top + b.bottom) / 2f
    }

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
        // v1.7.2：底栏是纯图标 + 仅选中项文字，所以按内容描述找，
        // 不能再依赖「五项都有文字节点」。
        val labels = listOf("首页", "文档", "书签", "发现", "我的")
        labels.forEach { label ->
            assertTrue("底栏缺少「$label」", tabCenterY(label) != null)
        }

        // ---- 2. 底栏落在屏幕下半部分 ----
        val dockCenter = tabCenterY("文档")!!
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

        // 五个图标应当横排；只要有一个 y 明显偏移，就说明底栏内部布局坏了
        val ys = listOf("首页", "文档", "书签", "发现", "我的").mapNotNull { tabCenterY(it) }
        assertEquals(5, ys.size)
        val spread = (ys.max() - ys.min()).absoluteValue
        assertTrue("五个 Tab 的中心 y 相差 $spread px，说明没有对齐", spread < 4f)
    }
}

private val Float.absoluteValue: Float get() = if (this < 0f) -this else this
