package com.lpdf.reader

import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.data.local.Bookmark
import com.lpdf.reader.data.local.LibraryAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0「书签页 / 首页继续阅读」的归并逻辑。
 *
 * 这两块最容易出错的地方都不是 UI，而是**键的对应关系**：
 * 书签文件用 `hash(uri|mtime)` 命名，键里不含 uri，无法从键反推属于哪个文档。
 * 一旦聚合逻辑对不上，用户看到的就是「书签页空着」或者「点了跳错文件」。
 * 所以这里全部用纯函数测，不碰 Android 运行时。
 */
class LibraryAggregatorTest {

    private fun doc(
        id: String,
        uri: String = "content://docs/$id",
        name: String = "$id.pdf",
        modified: Long = 1000L,
        pages: Int? = 10
    ) = PdfItem(
        id = id,
        uri = uri,
        displayName = name,
        sizeBytes = 1024L,
        lastModified = modified,
        pageCount = pages
    )

    /** 模拟真实 keyOf：uri 与 mtime 都参与，任一变化键就变 */
    private fun keyOf(item: PdfItem): String =
        Integer.toHexString((item.uri + "|" + item.lastModified).hashCode())

    // ---------------- bookmarks ----------------

    @Test
    fun `书签按文档归并且新的排前面`() {
        val a = doc("a")
        val b = doc("b")
        val store = mapOf(
            keyOf(a) to listOf(
                Bookmark("1", 2, "第二章", 100L),
                Bookmark("2", 5, "总结", 300L)
            ),
            keyOf(b) to listOf(Bookmark("3", 0, "封面", 200L))
        )

        val hits = LibraryAggregator.bookmarks(
            listOf(a, b), store.keys, ::keyOf
        ) { k -> store[k].orEmpty() }

        assertEquals(3, hits.size)
        // 400 没有，最大是 300
        assertEquals(listOf(300L, 200L, 100L), hits.map { it.createdAt })
        // 排序后第一条应属于 a 的第 5 页
        assertEquals("a", hits[0].doc.id)
        assertEquals(5, hits[0].page)
        assertEquals("b", hits[1].doc.id)
    }

    @Test
    fun `孤儿书签不显示`() {
        val a = doc("a")
        // 这个键对应的文档已经不在列表里（被删 / 改名）
        val orphanKey = "deadbeef"

        val hits = LibraryAggregator.bookmarks(
            listOf(a), setOf(orphanKey, keyOf(a)), ::keyOf
        ) { k ->
            if (k == orphanKey) listOf(Bookmark("9", 1, "旧书签", 500L)) else emptyList()
        }

        // 孤儿键即使书签还在磁盘上，也不该出现在结果里
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `文件被修改后旧书签自动失效`() {
        val before = doc("a", modified = 1000L)
        val after = doc("a", modified = 2000L) // 同一个 uri，改过时间
        val store = mapOf(keyOf(before) to listOf(Bookmark("1", 3, "旧", 100L)))

        // 拿「改后」的文档去查，键已经不同
        val hits = LibraryAggregator.bookmarks(
            listOf(after), store.keys, ::keyOf
        ) { k -> store[k].orEmpty() }

        assertTrue("键随 mtime 变化，旧书签应当查不到", hits.isEmpty())
    }

    @Test
    fun `空输入直接返回空表`() {
        assertTrue(
            LibraryAggregator.bookmarks(emptyList(), setOf("x"), ::keyOf) { emptyList() }.isEmpty()
        )
        assertTrue(
            LibraryAggregator.bookmarks(listOf(doc("a")), emptySet(), ::keyOf) { emptyList() }.isEmpty()
        )
    }

    @Test
    fun `同一文档多个书签各自成条`() {
        val a = doc("a")
        val store = mapOf(
            keyOf(a) to (1..5).map { Bookmark("$it", it, "第${it}页", it * 10L) }
        )

        val hits = LibraryAggregator.bookmarks(listOf(a), store.keys, ::keyOf) { k ->
            store[k].orEmpty()
        }

        assertEquals(5, hits.size)
        // 五个书签都挂在同一个文档上
        assertEquals(listOf("a"), hits.map { it.doc.id }.distinct())
        // 页号不应被合并或去重
        assertEquals(listOf(5, 4, 3, 2, 1), hits.map { it.page })
    }

    // ---------------- reading ----------------

    @Test
    fun `没有进度的文档不进继续阅读`() {
        val a = doc("a")
        val b = doc("b")
        val progress = mapOf(keyOf(a) to 3, keyOf(b) to 0)

        val hits = LibraryAggregator.reading(listOf(a, b), progress, ::keyOf)

        assertEquals(1, hits.size)
        assertEquals("a", hits[0].doc.id)
        assertEquals(3, hits[0].page)
    }

    @Test
    fun `进度越界时收敛到末页`() {
        // 文档被替换过：原来读到第 50 页，现在只有 5 页（合法 0..4）。
        // 收敛到末页而不是首页 —— 用户确实读到后面了，退到首页反而丢进度
        val a = doc("a", pages = 5)
        val hits = LibraryAggregator.reading(listOf(a), mapOf(keyOf(a) to 50), ::keyOf)

        assertEquals(1, hits.size)
        assertEquals(4, hits[0].page)
        assertEquals(5, hits[0].totalPages)
    }

    @Test
    fun `页数未知时保留原进度`() {
        // 还没解析出 pageCount（null），不能因为「不知道总页数」就把进度清 0
        val a = doc("a", pages = null)
        val hits = LibraryAggregator.reading(listOf(a), mapOf(keyOf(a) to 7), ::keyOf)

        assertEquals(7, hits[0].page)
        assertEquals(0, hits[0].totalPages)
    }

    @Test
    fun `继续阅读按修改时间倒序`() {
        val old = doc("old", modified = 100L)
        val mid = doc("mid", modified = 500L)
        val new = doc("new", modified = 900L)
        val progress = mapOf(
            keyOf(old) to 1, keyOf(mid) to 2, keyOf(new) to 3
        )

        val hits = LibraryAggregator.reading(listOf(mid, new, old), progress, ::keyOf)

        assertEquals(listOf("new", "mid", "old"), hits.map { it.doc.id })
    }

    @Test
    fun `进度表里的陌生键被忽略`() {
        val a = doc("a")
        val progress = mapOf("unknown" to 5, keyOf(a) to 2)

        val hits = LibraryAggregator.reading(listOf(a), progress, ::keyOf)

        assertEquals(1, hits.size)
        assertEquals("a", hits[0].doc.id)
    }

    @Test
    fun `负进度不算在读`() {
        // -1 常被用作「未开始」哨兵值
        val a = doc("a")
        val hits = LibraryAggregator.reading(listOf(a), mapOf(keyOf(a) to -1), ::keyOf)
        assertTrue(hits.isEmpty())
    }
}
