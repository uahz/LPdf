package com.lpdf.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航链路的**源码级**契约测试。
 *
 * ## 为什么需要读源码
 *
 * 存储键不一致这个缺陷（v1.5.x 之前）不是某个函数算错，而是
 * **调用链上有一环漏传了参数**：列表页算键用 `item.lastModified`，
 * 阅读页 bind 时硬编码 `0L`。这类缺陷：
 *
 * - 单测算法本身测不出来（`keyOf` 一直是对的）
 * - 端到端测试也很难抓（要真机放标注再重开，且结果只是「标注消失」）
 *
 * 所以只能用最笨但有效的办法：把「谁必须传 mtime」写成断言，
 * 直接检查源码文本。它不是优雅的方案，但它确实会在有人删掉这行时变红。
 *
 * ## 注意
 *
 * 这些断言依赖具体写法（变量名、参数顺序）。改重构时如果误报，
 * 应当更新断言而不是删掉测试 —— 删掉就等于把防线撤了。
 */
class NavigationContractTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/lpdf/reader/$rel")
        assertTrue("源码文件不存在：${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `阅读页必须把 lastModified 传给 bind`() {
        val s = src("ui/page/reader/PdfReaderPage.kt")
        assertTrue(
            "PdfReaderPage 没有把 lastModified 传给 vm.bind —— " +
                "这会让阅读页和列表页算出不同的存储键，" +
                "表现为「标注画了重开就没了」「书签页看不到刚加的书签」",
            s.contains("vm.bind(uri, displayName, lastModified, null, startPage)")
        )
        // 反过来确认没有残留的硬编码 0L
        assertTrue(
            "PdfReaderPage 里仍存在 vm.bind(..., 0L, ...) 的硬编码 mtime",
            !s.contains("vm.bind(uri, displayName, 0L")
        )
    }

    @Test
    fun `路由必须携带 mtime 参数`() {
        val s = src("ui/LPdfApp.kt")
        assertTrue("Routes.reader 没有拼 mtime 参数", s.contains("\"&mtime="))
        assertTrue("导航图没有声明 mtime 参数", s.contains("navArgument(\"mtime\")"))
        assertTrue(
            "阅读页没从导航参数里读 mtime",
            s.contains("getLong(\"mtime\")")
        )
    }

    @Test
    fun `打开文档的四个入口都要带上 modified`() {
        // 首页「继续阅读」、首页「最近导入」、文档列表、书签页 ——
        // 任一入口漏传，从那个入口进去就丢标注
        val app = src("ui/LPdfApp.kt")
        assertTrue(
            "HomeTab 的 onContinue 没带 lastModified",
            app.contains("hit.doc.lastModified")
        )

        val list = src("ui/page/list/PdfListPage.kt")
        assertTrue(
            "文档列表打开时没带 lastModified",
            list.contains("onOpenReader(it2.uri, it2.displayName, it2.lastModified)")
        )

        val marks = src("ui/page/marks/BookmarksPage.kt")
        assertTrue(
            "书签页跳转时没带 lastModified",
            marks.contains("hit.doc.lastModified")
        )
    }

    @Test
    fun `书签页的 onOpenAt 签名必须含 modified`() {
        val s = src("ui/page/marks/BookmarksPage.kt")
        assertTrue(
            "BookmarksPage.onOpenAt 签名里没有 modified，调用方无法把 mtime 传下去",
            s.contains("onOpenAt: (uri: String, name: String, modified: Long, page: Int) -> Unit")
        )
    }
}
