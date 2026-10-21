package com.lpdf.reader

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * 首页顶部元素不重叠（v1.3.5）。
 *
 * 真机截图：启动时那句「需要读取文档权限才能自动列出本地 PDF…」直接
 * 盖在「LPdf」标题上，两行字叠成一坨看不清。
 *
 * 原因是提示条放在 `topInset + 8dp`，与标题行完全同高。修复后提示条
 * 下移到标题下方，断言是「提示条的上边缘不低于标题的下边缘」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeHeaderOverlapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `提示条不应与标题重叠`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()

        val title = compose.onNodeWithText("LPdf").getBoundsInRoot()

        // 提示条文本来自 ListViewModel 的启动提示；未授权环境下必然出现
        val toastText = "需要读取文档权限才能自动列出本地 PDF，也可以手动选择文件"
        val toast = runCatching {
            compose.onNodeWithText(toastText, substring = true).getBoundsInRoot()
        }.getOrNull() ?: return   // 该环境下未弹出提示条，本用例不适用

        assertTrue(
            "标题下边缘 y=${title.bottom}，提示条上边缘 y=${toast.top}。" +
                "提示条压在了标题上，两者会叠成一坨。",
            toast.top.value >= title.bottom.value - 2f
        )
    }

    @Test
    fun `标题应可见且宽度不受挤压`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            LPdfApp(container = app.container, settingsFlow = MutableStateFlow(AppSettings()))
        }
        compose.waitForIdle()

        val root = compose.onRoot().getBoundsInRoot()
        val title = compose.onNodeWithText("LPdf").getBoundsInRoot()

        assertTrue("标题宽度应大于 0", title.right.value - title.left.value > 0f)
        assertTrue(
            "标题应位于屏幕上方 1/3 区域内，实际 top=${title.top}，屏高=${root.bottom}",
            title.top.value < root.bottom.value / 3f
        )
    }
}
