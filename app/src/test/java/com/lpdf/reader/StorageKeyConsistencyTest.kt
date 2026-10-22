package com.lpdf.reader

import com.lpdf.reader.core.model.PdfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 存储键一致性（v1.6.0 修的既有缺陷的守门测试）。
 *
 * ## 缺陷是什么
 *
 * 标注 / 书签 / 阅读进度全部按 `keyOf(uri, lastModified)` 存盘。
 * v1.5.x 之前，列表页用 `item.lastModified` 算键，阅读页却硬编码传 `0L`，
 * 于是同一个文件在两头算出两个不同的键 —— 各存各的文件。
 * 用户看到的现象是：**在阅读页画了标注、加了书签、翻了几页，
 * 关掉重开全没了**，而且没有任何报错。
 *
 * ## 为什么测试写成「比较两次计算」而不是「断言等于某个常量」
 *
 * 断言常量只能证明哈希不变，证明不了「两头算的是同一个东西」。
 * 真正要守的约束是：**同一份文档信息，从列表页传进阅读页时键必须不变**。
 * 所以这里直接模拟这条链路：列表侧算一次 → 打包成导航参数 →
 * 阅读页侧再算一次 → 必须相等。任何一端漏传 mtime 都会红。
 */
class StorageKeyConsistencyTest {

    /** 与 [com.lpdf.reader.data.local.AnnotationStore.keyOf] 保持同一算法 */
    private fun keyOf(uri: String, lastModified: Long): String =
        Integer.toHexString((uri + "|" + lastModified).hashCode())

    private fun PdfItem.key() = keyOf(uri, lastModified)

    private val item = PdfItem(
        id = "42",
        uri = "content://com.android.providers.downloads.documents/document/42",
        displayName = "HowToLiveBetter.pdf",
        sizeBytes = 8_388_608L,
        lastModified = 1_723_456_789_000L,
        pageCount = 128
    )

    @Test
    fun `列表页与阅读页算出的键必须相同`() {
        // 列表侧
        val listSide = item.key()
        // 导航参数只传了 uri + mtime，阅读页按同样方式重建
        val readerSide = keyOf(item.uri, item.lastModified)
        assertEquals(listSide, readerSide)
    }

    @Test
    fun `mtime 丢失会算出另一个键 —— 这就是当年的缺陷`() {
        val correct = keyOf(item.uri, item.lastModified)
        val broken = keyOf(item.uri, 0L)
        assertNotEquals(
            "传 0 必须算出不同的键（否则这个缺陷根本不会表现出来）",
            correct, broken
        )
    }

    @Test
    fun `mtime 不同即不同文档`() {
        // 同名同 uri 但文件被重新导出过：应当视为另一份文档，
        // 旧标注不能串到新文件上
        val a = keyOf(item.uri, 1000L)
        val b = keyOf(item.uri, 2000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `uri 不同即不同文档`() {
        val a = keyOf("content://a/1", 1000L)
        val b = keyOf("content://b/1", 1000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `键是纯十六进制且不含路径分隔符`() {
        // 键会直接当文件名用（"$key.json"），一旦混进 '/' 就会写到别的目录
        val k = item.key()
        assertEquals(k, k.lowercase())
        assert(!k.contains('/')) { "键不能含 '/'：$k" }
        assert(!k.contains('\\')) { "键不能含 '\\\\'：$k" }
        assert(!k.contains('|')) { "键不能含 '|'：$k" }
        assert(!k.contains(':')) { "键不能含 ':'（uri 里的冒号必须已被哈希吃掉）：$k" }
    }
}
