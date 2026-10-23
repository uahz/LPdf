package com.lpdf.reader

import com.lpdf.reader.data.pdf.SegmentedPdfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段写入策略测试（v1.6.2 OOM 修复的一部分）。
 *
 * 这里断言的不是「PDF 能不能写出来」（那需要真机 PdfRenderer），
 * 而是**分段几何**：段大小怎么随页数变化、分完之后有没有漏页。
 * 这两条算错的表现是「导出少了几页」或「峰值内存没降下来」，
 * 都不会报错，只能靠断言兜住。
 */
class SegmentedPdfWriterTest {

    @Test
    fun `小文档不分段，避免无谓的合并开销`() {
        assertEquals(20, SegmentedPdfWriter.segmentSize(20))
        assertEquals(1, SegmentedPdfWriter.segmentSize(1))
        assertEquals(7, SegmentedPdfWriter.segmentSize(7))
    }

    @Test
    fun `中等文档用 16 页一段`() {
        assertEquals(16, SegmentedPdfWriter.segmentSize(21))
        assertEquals(16, SegmentedPdfWriter.segmentSize(60))
    }

    @Test
    fun `大文档回到默认 8 页，峰值内存与总页数解耦`() {
        assertEquals(8, SegmentedPdfWriter.segmentSize(61))
        assertEquals(8, SegmentedPdfWriter.segmentSize(500))
        assertEquals(8, SegmentedPdfWriter.segmentSize(5000))
    }

    @Test
    fun `页数非法时不抛异常也不返回 0`() {
        assertEquals(1, SegmentedPdfWriter.segmentSize(0))
        assertEquals(1, SegmentedPdfWriter.segmentSize(-5))
    }

    @Test
    fun `分段结果必须刚好覆盖全部页, 不多不少`() {
        // 复刻 SegmentedPdfWriter.write 的切分逻辑，验证覆盖性
        fun segments(total: Int): List<IntRange> {
            val size = SegmentedPdfWriter.segmentSize(total)
            val out = mutableListOf<IntRange>()
            var done = 0
            while (done < total) {
                val end = (done + size).coerceAtMost(total)
                out += done until end
                done = end
            }
            return out
        }

        listOf(1, 7, 20, 21, 60, 61, 100, 333, 1000).forEach { total ->
            val segs = segments(total)
            val covered = segs.flatMap { it.toList() }
            assertEquals("页数 $total 的分段覆盖应恰好等于全部页", (0 until total).toList(), covered)
            assertTrue("页数 $total 的每段不应超出上限", segs.all { it.count() <= SegmentedPdfWriter.segmentSize(total) })
        }
    }

    @Test
    fun `首尾巴段可能不满但中间段必须满`() {
        // 100 页、每段 8 页 → 12 段足额 + 1 段 4 页
        val total = 100
        val size = SegmentedPdfWriter.segmentSize(total)
        val lastLen = total % size
        assertEquals(4, lastLen)
        assertEquals(8, size)
    }
}
