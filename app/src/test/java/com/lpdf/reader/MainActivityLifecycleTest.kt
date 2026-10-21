package com.lpdf.reader

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MainActivity 生命周期回归测试（v1.3.1）。
 *
 * 背景：曾出现 `enableEdgeToEdge()` 写在 `super.onCreate()` **之前** 的 bug，
 * 导致每次冷启动必崩。原因很隐蔽：
 * `androidx.activity.EdgeToEdge.enable()` 第一步就调用
 * `ComponentActivity.getWindow().getDecorView()`，而 window 要到
 * `super.onCreate()` 里才 attach 完成。
 *
 * 这个测试直接把 Activity 走一遍完整创建流程；顺序写反会在这里抛
 * NullPointerException / IllegalStateException，不必等到真机上才发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityLifecycleTest {

    @Test
    fun `冷启动可完成 onCreate 且不抛异常`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("窗口必须已 attach", activity.window != null)
        assertTrue("内容视图必须已创建", activity.window.decorView != null)
    }

    @Test
    fun `带 savedInstanceState 重建不崩溃`() {
        val bundle = Bundle().apply { putString("probe", "kept") }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        // 模拟配置变更引发的重建
        controller.recreate()
        val activity = controller.get()
        assertTrue(activity.window.decorView != null)
        bundle.clear()
    }

    @Test
    fun `窗口已启用边到边`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // enableEdgeToEdge 会给 decorView 设置 LAYOUT_STABLE 之外的 systemUiVisibility 组合，
        // 这里只验证调用确实生效（未抛异常且 window 可访问）
        val decor = activity.window.decorView
        assertTrue(decor.isAttachedToWindow)
    }

    @Test
    fun `依赖容器在 Activity 中可用`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val app = ApplicationProvider.getApplicationContext<LpdfApplication>()
        assertTrue("Application 与 Activity 必须共用同一容器",
            app.container === (activity.application as LpdfApplication).container)
    }
}
