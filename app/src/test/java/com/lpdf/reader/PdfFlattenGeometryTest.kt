package com.lpdf.reader

import com.lpdf.reader.data.local.AnnotRect
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotationDoc
import com.lpdf.reader.data.pdf.PdfFlattener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标注扁平化的坐标换算测试。
 *
 * 为什么不测实际像素：Robolectric 的 Canvas 是 Shadow 实现，`drawRect` / `drawPath`
 * 不产生真实光栅化，`getPixel` 永远返回初始底色。因此本测试只覆盖「归一化坐标 → 页面像素」
 * 这一层纯计算逻辑 —— 它才是扁平化最容易出错、也最值得回归保护的部分。
 * 端到端（PDFium 渲染 + PdfDocument 写入）依赖 native so，只能在真机验证。
 */
class PdfFlattenGeometryTest {

    private val pageW = 1200f
    private val pageH = 1600f

    @Test
    fun `高亮矩形换算到页面像素`() {
        val a = Annotation.Highlight(
            id = "h1",
            rects = listOf(AnnotRect(0.25f, 0.25f, 0.75f, 0.75f)),
            color = 0xFFFFD60A
        )
        val rects = PdfFlattener.flatRects(a, pageW, pageH)
        assertEquals(1, rects.size)
        val r = rects.first()
        assertEquals(300f, r.left, 0.01f)
        assertEquals(400f, r.top, 0.01f)
        assertEquals(900f, r.right, 0.01f)
        assertEquals(1200f, r.bottom, 0.01f)
    }

    @Test
    fun `高亮多矩形逐个换算且顺序保持`() {
        val a = Annotation.Highlight(
            id = "h2",
            rects = listOf(
                AnnotRect(0f, 0f, 1f, 0.1f),
                AnnotRect(0f, 0.5f, 1f, 0.6f)
            ),
            color = 0xFFFFD60A
        )
        val rects = PdfFlattener.flatRects(a, pageW, pageH)
        assertEquals(2, rects.size)
        assertEquals(0f, rects[0].top, 0.01f)
        assertEquals(160f, rects[0].bottom, 0.01f)
        assertEquals(800f, rects[1].top, 0.01f)
        assertEquals(960f, rects[1].bottom, 0.01f)
    }

    @Test
    fun `非矩形类标注无矩形输出`() {
        val ink = Annotation.Ink("i1", listOf(0.1f to 0.1f, 0.9f to 0.9f), 0.003f, 0xFF000000)
        assertTrue(PdfFlattener.flatRects(ink, pageW, pageH).isEmpty())

        val note = Annotation.Note("n1", 0.5f, 0.5f, "x")
        assertTrue(PdfFlattener.flatRects(note, pageW, pageH).isEmpty())
    }

    @Test
    fun `墨迹点序列换算保持比例`() {
        val a = Annotation.Ink(
            id = "i2",
            points = listOf(0.1f to 0.1f, 0.5f to 0.5f, 0.9f to 0.9f),
            width = 0.004f,
            color = 0xFF000000
        )
        val pts = PdfFlattener.flatPoints(a, pageW, pageH)
        assertEquals(3, pts.size)
        assertEquals(120f, pts[0].first, 0.01f)
        assertEquals(160f, pts[0].second, 0.01f)
        assertEquals(600f, pts[1].first, 0.01f)
        assertEquals(800f, pts[1].second, 0.01f)
        assertEquals(1080f, pts[2].first, 0.01f)
        assertEquals(1440f, pts[2].second, 0.01f)
    }

    @Test
    fun `墨迹线宽按页宽归一化后不小于下限`() {
        // 归一化线宽 × 页宽 = 实际像素线宽
        val wide = Annotation.Ink("i3", listOf(0f to 0f, 1f to 1f), 0.004f, 0xFF000000)
        assertEquals(4.8f, PdfFlattener.flatStrokeWidth(wide, pageW), 0.01f)

        // 极小线宽被抬到下限 2f，避免导出后看不见
        val hair = Annotation.Ink("i4", listOf(0f to 0f, 1f to 1f), 0.00001f, 0xFF000000)
        assertEquals(2f, PdfFlattener.flatStrokeWidth(hair, pageW), 0.01f)
    }

    @Test
    fun `颜色 Long 转为 ARGB Int 不丢 alpha`() {
        // 0xAARRGGBB
        assertEquals(0xFFFFD60A.toInt(), PdfFlattener.argb(0xFFFFD60A))
        // 半透明色
        assertEquals(0x80FF0000.toInt(), PdfFlattener.argb(0x80FF0000))
    }

    @Test
    fun `超高亮色不会溢出为负数错误`() {
        // 0xFF 开头的高位在转 Int 后应为负数，这是 ARGB 的正常表示
        val v = PdfFlattener.argb(0xFFFF375F)
        assertTrue("应为负值（alpha=FF）", v < 0)
        assertEquals(0xFF, (v ushr 24) and 0xFF)
        assertEquals(0x37, (v ushr 8) and 0xFF)
        assertEquals(0x5F, v and 0xFF)
    }

    @Test
    fun `空文档不产生任何矩形`() {
        val doc = AnnotationDoc("empty")
        assertTrue(doc.isEmpty())
        assertTrue(doc.all().isEmpty())
    }

    @Test
    fun `边界值 0 与 1 换算到页面两端`() {
        val a = Annotation.Highlight(
            "h5",
            listOf(AnnotRect(0f, 0f, 1f, 1f)),
            0xFFFFFFFF
        )
        val r = PdfFlattener.flatRects(a, pageW, pageH).first()
        assertEquals(0f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
        assertEquals(pageW, r.right, 0.01f)
        assertEquals(pageH, r.bottom, 0.01f)
    }

    @Test
    fun `不同页面尺寸下比例保持一致`() {
        val a = Annotation.Highlight("h6", listOf(AnnotRect(0.5f, 0.5f, 1f, 1f)), 0xFFFFFFFF)
        val small = PdfFlattener.flatRects(a, 600f, 800f).first()
        assertEquals(300f, small.left, 0.01f)
        assertEquals(400f, small.top, 0.01f)

        val large = PdfFlattener.flatRects(a, 2400f, 3200f).first()
        assertEquals(1200f, large.left, 0.01f)
        assertEquals(1600f, large.top, 0.01f)
    }
}
