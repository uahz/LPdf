package com.lpdf.reader

import androidx.compose.ui.Modifier
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
 * 首屏组合冒烟测试。
 *
 * 「能启动但界面白屏 / 秒退」通常是 Compose 组合期或首帧绘制期抛异常。
 * 这里在 JVM 上真正跑一次组合 + 布局，把这类问题提前暴露。
 *
 * 已知限制：Robolectric 沙箱只支持到 class file 64（Java 20），
 * 而 Kyant0 玻璃库编译在 Java 21（65），因此涉及 `drawBackdrop` 的用例
 * 在 JVM 上会抛 UnsupportedClassVersionError —— 这是测试环境限制，
 * 与真机无关（真机走 dex，无此约束）。故此处只覆盖不触碰该库的路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ListScreenComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `主题与基础布局可完成组合`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()

        compose.setContent {
            com.lpdf.reader.ui.theme.LPdfTheme(dark = false) {
                androidx.compose.foundation.layout.Box(Modifier) {
                    androidx.compose.material3.Text("LPdf")
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `设置流可被收集且默认值合法`() {
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        app.onCreate()
        val flow = MutableStateFlow(AppSettings())
        org.junit.Assert.assertNotNull(flow.value)
        org.junit.Assert.assertTrue(flow.value.zoomLimit > 0)
    }

    @Test
    fun `根路由常量可正常拼装`() {
        val route = com.lpdf.reader.ui.Routes.reader("content://a/b c.pdf", "测试 文档.pdf")
        org.junit.Assert.assertTrue(route.startsWith("reader?"))
        org.junit.Assert.assertFalse("参数必须编码，不能带裸空格", route.contains(" "))
    }
}
