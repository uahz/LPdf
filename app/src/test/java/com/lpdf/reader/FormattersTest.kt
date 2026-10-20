package com.lpdf.reader

import com.lpdf.reader.core.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数单测：不依赖 Android 运行时，验证列表/阅读页展示逻辑。
 */
class FormattersTest {

    @Test
    fun `文件大小分级显示`() {
        assertEquals("512 B", Formatters.fileSize(512))
        assertEquals("1 KB", Formatters.fileSize(1024))
        assertEquals("1.0 MB", Formatters.fileSize(1024L * 1024))
        assertEquals("1.50 GB", Formatters.fileSize(1024L * 1024 * 1024 * 3 / 2))
    }

    @Test
    fun `页码范围解析`() {
        assertEquals(listOf(1, 2, 3, 7), Formatters.parsePageRanges("1-3,7", 10))
        assertEquals(listOf(1, 2, 3), Formatters.parsePageRanges("3~1", 10))
        assertEquals(emptyList<Int>(), Formatters.parsePageRanges("", 10))
        // 越界自动裁剪
        assertEquals(listOf(1, 2, 3), Formatters.parsePageRanges("1-99", 3))
    }

    @Test
    fun `中间省略保留扩展名`() {
        val long = "a-very-long-document-name-that-should-be-truncated.pdf"
        val shown = Formatters.middleEllipsis(long, 20)
        assertTrue(shown.endsWith(".pdf"))
        assertTrue(shown.length <= 21)
    }

    @Test
    fun `采样主色被柔化`() {
        val softened = Formatters.softenColor(0xFF000000)
        // 纯黑被提亮，避免玻璃高亮在深色文档上变成黑块
        assertTrue(Formatters.luminance(softened) > 0.12f)
    }
}
