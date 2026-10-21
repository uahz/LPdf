package com.lpdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动冒烟测试。
 *
 * 「打开就闪退」这类问题在静态审查里很难定位，这里把启动路径真正跑一遍：
 * Application.onCreate 的每一步初始化、依赖容器构造、各存储器首次触碰。
 * 任何 NPE / 类初始化失败都会在这里以异常形式暴露出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppStartupTest {

    @Test
    fun `依赖容器可完整构造`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        // 逐个触碰，把「懒加载里藏着的初始化异常」逼出来
        assertNotNull(container.prefs)
        assertNotNull(container.mediaStore)
        assertNotNull(container.validator)
        assertNotNull(container.thumbnails)
        assertNotNull(container.annotations)
        assertNotNull(container.bookmarks)
        assertNotNull(container.signatures)
        assertNotNull(container.linkImporter)
        assertNotNull(container.updateChecker)
        assertNotNull(container.resolver())
        assertNotNull(container.appContext())
    }

    @Test
    fun `LpdfApplication 能完成 onCreate`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        (app as Application).onCreate()
        assertNotNull("Application 创建后容器必须可用", (app as LpdfApplication).container)
    }

    @Test
    fun `PdfiumEngine 初始化不抛异常`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.lpdf.reader.data.pdf.PdfiumEngine.init(app)
        // 只验证 init 本身安全；真正的 native 加载在 Robolectric 下不可用
        assertNotNull(app)
    }

    @Test
    fun `崩溃记录器可安装且读写安全`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.lpdf.reader.core.util.CrashReporter.install(app)
        // 没有崩溃时不应有报告
        com.lpdf.reader.core.util.CrashReporter.clear(app)
        org.junit.Assert.assertFalse(com.lpdf.reader.core.util.CrashReporter.hasReport(app))
        org.junit.Assert.assertNull(com.lpdf.reader.core.util.CrashReporter.read(app))
    }

    @Test
    fun `Dispatcher 静态初始化不失败`() {
        // AppDispatchers 是 object，首次触碰会跑静态初始化；
        // Dispatchers.Main 缺 coroutines-android 时会在这里抛 ExceptionInInitializerError
        assertNotNull(com.lpdf.reader.core.di.AppDispatchers.io)
        assertNotNull(com.lpdf.reader.core.di.AppDispatchers.default)
        assertNotNull(com.lpdf.reader.core.di.AppDispatchers.pdf)
    }
}
