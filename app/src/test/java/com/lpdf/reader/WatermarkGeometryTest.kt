package com.lpdf.reader

import com.lpdf.reader.data.pdf.PdfWriterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水印几何计算测试。
 *
 * 水印绘制依赖真实 Canvas 光栅化，Robolectric 的 Shadow Canvas 不执行绘制指令，
 * 所以这里只验证「尺寸与步进」这类纯数学部分——它们才是水印观感的主要变量。
 */
class WatermarkGeometryTest {

    private val a4Width = 595f
    private val a0Width = 2384f

    @Test
    fun `字号按页宽比例换算`() {
        val size = PdfWriterEngine.watermarkTextSize(a4Width, 0.06f)
        assertEquals(a4Width * 0.06f, size, 0.01f)
    }

    @Test
    fun `极小页宽时字号不低于绝对下限`() {
        // 名片尺寸 85pt 宽，6% 只有 5.1pt，肉眼几乎不可见
        val size = PdfWriterEngine.watermarkTextSize(85f, 0.06f)
        assertEquals(8f, size, 0.01f)
    }

    @Test
    fun `极大页宽时字号不超过页宽四成`() {
        val size = PdfWriterEngine.watermarkTextSize(a0Width, 0.40f)
        assertEquals(a0Width * 0.4f, size, 0.01f)
    }

    @Test
    fun `比例参数被夹到合法区间`() {
        // 传入负数不应产生负字号
        val tiny = PdfWriterEngine.watermarkTextSize(a4Width, -1f)
        assertTrue(tiny >= 8f)
        // 传入超大值不应糊满整页
        val huge = PdfWriterEngine.watermarkTextSize(a4Width, 99f)
        assertEquals(a4Width * 0.4f, huge, 0.01f)
    }

    @Test
    fun `平铺步进随文字变宽而增大`() {
        val (stepXShort, _) = PdfWriterEngine.watermarkTileSteps(a4Width, 40f, 0.28f, true)
        val (stepXLong, _) = PdfWriterEngine.watermarkTileSteps(a4Width, 400f, 0.28f, true)
        assertTrue("长文字需要更大的列距", stepXLong > stepXShort)
    }

    @Test
    fun `平铺行距按页宽比例`() {
        val (_, step) = PdfWriterEngine.watermarkTileSteps(a4Width, 100f, 0.28f, true)
        assertEquals(a4Width * 0.28f, step, 0.01f)
    }

    @Test
    fun `行距有 60pt 下限`() {
        // 极小页面 + 极小间隙，仍应保证至少 60pt 行距，否则水印糊成一团
        val (_, step) = PdfWriterEngine.watermarkTileSteps(40f, 10f, 0.05f, true)
        assertEquals(60f, step, 0.01f)
    }

    @Test
    fun `列距有 80pt 下限`() {
        val (stepX, _) = PdfWriterEngine.watermarkTileSteps(40f, 1f, 0.05f, true)
        assertEquals(80f, stepX, 0.01f)
    }

    @Test
    fun `无文字时按页宽三成估算占位`() {
        // 图片水印没有 measureText 结果，用页宽 30% 兜底
        val (stepX, _) = PdfWriterEngine.watermarkTileSteps(1000f, 0f, 0.28f, false)
        val expectedMeasured = 1000f * 0.3f
        val expectedGap = 1000f * 0.28f
        assertEquals(expectedMeasured + expectedGap * 0.8f, stepX, 0.01f)
    }
}
