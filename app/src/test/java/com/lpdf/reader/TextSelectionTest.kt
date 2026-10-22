package com.lpdf.reader

import com.lpdf.reader.data.pdf.CharBox
import com.lpdf.reader.data.pdf.TextLayer
import com.lpdf.reader.data.pdf.selectByDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 文本选择的核心换算（v1.7.0）。
 *
 * 这段逻辑纯计算，脱开 PDFium 就能测 —— 而它恰恰是最容易出错的地方：
 * 反向拖、跨行、手指落在行间空白、扫描件无文字层，
 * 这些情况在真机上要复现一次得翻页、对准、重试，根本不可能逐个验证。
 */
class TextSelectionTest {

    /**
     * 造一个两行的文字层。**布局必须看这张表**，写测试时别再心算 x：
     *
     * ```
     * 行 0 (y 0.20~0.24)   行 1 (y 0.30~0.34)
     * A  0.10~0.15          F  0.10~0.15
     * B  0.15~0.20          G  0.15~0.20
     * C  0.20~0.25          H  0.20~0.25
     * D  0.25~0.30          I  0.25~0.30
     * E  0.30~0.35          J  0.30~0.35
     * ```
     *
     * 取坐标时务必落在某个字框**内部**（例如选 C 用 0.22，不是 0.25）。
     * 字框是左闭右开的区间，落在边界或字缝里，吸附到哪一侧是不确定的。
     */
    private fun twoLines(): TextLayer {
        val chars = mutableListOf<CharBox>()
        "ABCDE".forEachIndexed { i, c ->
            val l = 0.10f + i * 0.05f
            chars += CharBox(c, 0, l, 0.20f, l + 0.05f, 0.24f)
        }
        "FGHIJ".forEachIndexed { i, c ->
            val l = 0.10f + i * 0.05f
            chars += CharBox(c, 1, l, 0.30f, l + 0.05f, 0.34f)
        }
        return TextLayer(0, chars, 2)
    }

    @Test
    fun `同一行内拖选只选中区间内的字符`() {
        // 从 A(0.12) 拖到行尾之外(0.32)：第 0 行整行
        val sel = selectByDrag(twoLines(), 0.12f, 0.22f, 0.32f, 0.22f)
        assertEquals("ABCDE", sel.text.replace("\n", ""))
        assertTrue(sel.chars.all { it.line == 0 })
    }

    @Test
    fun `同一行内只选中拖过的那几个字`() {
        // B(0.17) → D(0.27)，期望 "BCD"
        val sel = selectByDrag(twoLines(), 0.17f, 0.22f, 0.27f, 0.22f)
        assertEquals("BCD", sel.text)
    }

    @Test
    fun `反向拖动与正向拖动结果一致`() {
        val fwd = selectByDrag(twoLines(), 0.12f, 0.22f, 0.42f, 0.22f)
        val rev = selectByDrag(twoLines(), 0.42f, 0.22f, 0.12f, 0.22f)
        assertEquals(fwd.text, rev.text)
        assertEquals("ABCDE", fwd.text)
    }

    @Test
    fun `跨行拖选会在行末换行`() {
        // 从第 0 行 C（0.20~0.25）拖到第 1 行 H（0.35~0.40），H 实际字框 0.20~0.25，x 取 0.22 落在其内部
        // 坐标必须落在字框内部：字与字之间是 0 宽缝隙，落在缝里吸附到哪边不确定
        val sel = selectByDrag(twoLines(), 0.22f, 0.22f, 0.22f, 0.32f)
        assertEquals("CDE\nFGH", sel.text)
    }

    @Test
    fun `拖选产生的矩形按行合并`() {
        val sel = selectByDrag(twoLines(), 0.12f, 0.22f, 0.42f, 0.32f)
        // 两行 → 两个长条，而不是逐字的 8 个
        assertEquals(2, sel.rects.size)
        // 第一个长条覆盖 A 左沿到 E 右沿
        assertTrue(abs(sel.rects[0].left - 0.10f) < 0.01f)
        assertTrue(abs(sel.rects[0].right - 0.35f) < 0.01f)
    }

    @Test
    fun `手指落在行间空白时吸附到更近的一行`() {
        // y=0.27 恰好在两行之间（行0 底 0.24，行1 顶 0.30）
        // 离第 1 行更近，应选到第 1 行
        val sel = selectByDrag(twoLines(), 0.12f, 0.27f, 0.12f, 0.27f)
        assertEquals("F", sel.text)
    }

    @Test
    fun `扫描件没有文字层时返回空选区而不是崩溃`() {
        val empty = TextLayer(3, emptyList(), 0)
        val sel = selectByDrag(empty, 0.5f, 0.5f, 0.6f, 0.6f)
        assertTrue(sel.isEmpty)
        assertEquals("", sel.text)
        assertEquals(0, sel.rects.size)
    }

    @Test
    fun `拖到页面外沿不会越界`() {
        val sel = selectByDrag(twoLines(), -0.5f, -0.5f, 1.5f, 1.5f)
        // 起点吸附到 A，终点吸附到 J，整页文字都在选区里
        assertEquals("ABCDE\nFGHIJ", sel.text)
    }

    @Test
    fun `单字点选只选一个字`() {
        val sel = selectByDrag(twoLines(), 0.12f, 0.22f, 0.12f, 0.22f)
        assertEquals("A", sel.text)
        assertEquals(1, sel.rects.size)
    }
}
