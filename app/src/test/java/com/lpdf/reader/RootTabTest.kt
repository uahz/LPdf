package com.lpdf.reader

import com.lpdf.reader.ui.nav.RootTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 五 Tab 的结构约束。
 *
 * 底栏的药丸滑块位置是 `index * itemWidth` 算出来的，直接依赖枚举声明顺序，
 * 所以「顺序」不是随手排的，而是有 UI 后果的常量。这里把它钉死，
 * 免得以后有人觉得「书签放最后更顺眼」就顺手挪一下 —— 那会让底栏的
 * 高亮框和文字错位。
 */
class RootTabTest {

    @Test
    fun `底栏顺序固定为 首页-文档-书签-发现-我的`() {
        assertEquals(
            listOf("首页", "文档", "书签", "发现", "我的"),
            RootTab.bar.map { it.label }
        )
    }

    @Test
    fun `bar 与枚举声明顺序一致`() {
        assertEquals(RootTab.entries.toList(), RootTab.bar)
    }

    @Test
    fun `路由互不重复`() {
        val routes = RootTab.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `每个 Tab 都有图标和非空文案`() {
        RootTab.entries.forEach { tab ->
            assertNotNull("${tab.name} 缺图标", tab.icon)
            assertTrue("${tab.name} 文案为空", tab.label.isNotBlank())
        }
    }

    @Test
    fun `fromRoute 能反查且不串台`() {
        RootTab.entries.forEach { tab ->
            assertEquals(tab, RootTab.fromRoute(tab.route))
        }
        // 大小写不同不算命中，避免路由表模糊匹配带来的隐蔽 bug
        assertEquals(RootTab.HOME, RootTab.fromRoute("tab_home"))
        assertNull(RootTab.fromRoute("TAB_HOME"))
        assertNull(RootTab.fromRoute(null))
        assertNull(RootTab.fromRoute(""))
        // 阅读页 / 工具页不是 Tab
        assertNull(RootTab.fromRoute("reader"))
        assertNull(RootTab.fromRoute("tools"))
    }

    @Test
    fun `首页是默认 Tab`() {
        // startDestination 与 selected 初值都取 HOME；顺序变了这里会先红
        assertEquals(RootTab.HOME, RootTab.bar.first())
    }
}
