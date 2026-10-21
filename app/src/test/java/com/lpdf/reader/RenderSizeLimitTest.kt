package com.lpdf.reader

import com.lpdf.reader.data.pdf.PdfLimits
import com.lpdf.reader.data.pdf.planTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整页渲染尺寸约束测试（v1.3.3 建立，v1.5.0 重写）。
 *
 * ## 历史背景
 *
 * 真机崩溃 `Canvas: trying to draw too large(527487968bytes)` 的根因是
 * 「整页塞一张位图」这个模型本身错误 —— 5.27 亿字节 ÷ 4 = 1.32 亿像素，
 * 远超 GPU 单边纹理上限 8192。
 *
 * ## v1.5.0 之后这里测什么
 *
 * 只测**单张位图**的约束（判断对象是 tile，不是整页）。
 * 「这页能不能渲染」不再是问题 —— [planTiles] 保证答案永远是「能」，
 * 那条链路的测试在 [PageTilingTest]。
 *
 * 两者的分工：
 * - 本文件：一张位图能不能上纹理 → [PdfLimits.fitsSingleBitmap]
 * - PageTilingTest：一页怎么切成若干张合法位图 → [planTiles]
 */
class RenderSizeLimitTest {

    // ------------------------------------------------------------------
    // 真实崩溃数据：单张位图必须被拒绝
    // ------------------------------------------------------------------

    @Test
    fun `5点27亿字节的单张位图必须被拒绝`() {
        // 原始崩溃：527487968 bytes / 4 = 131,871,992 像素
        assertFalse(
            "这个尺寸正是真机崩溃的那个，单张位图必须判定为不可上纹理",
            PdfLimits.fitsSingleBitmap(1080, 122_103)
        )
    }

    @Test
    fun `同样的页面切成块之后每一块都合法`() {
        // 关键回归：这个页面在 v1.4 之前会被直接拒绝渲染
        val plan = planTiles(1080, 122_103)
        assertTrue("必须给出分块方案", plan.rects.isNotEmpty())
        plan.rects.forEach { r ->
            assertTrue(
                "块 ${r.left},${r.top} ${r.width}×${r.height} 超出单张纹理上限",
                PdfLimits.fitsSingleBitmap(r.width, r.height)
            )
        }
    }

    @Test
    fun `超长施工图页面被拒绝为单张位图`() {
        assertFalse(PdfLimits.fitsSingleBitmap(2048, 409_600))
    }

    // ------------------------------------------------------------------
    // 正常页面：不能被误杀
    // ------------------------------------------------------------------

    @Test
    fun `手机屏幕宽度的 A4 页面可以通过`() {
        assertTrue(PdfLimits.fitsSingleBitmap(1080, 1527))
    }

    @Test
    fun `平板双页横版 A4 可以通过`() {
        assertTrue(PdfLimits.fitsSingleBitmap(3600, 2550))
    }

    @Test
    fun `最高允许宽度 2048 的正常比例页面可以通过`() {
        assertTrue(PdfLimits.fitsSingleBitmap(2048, 2896))
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `超过单边纹理上限被拒绝`() {
        assertFalse(PdfLimits.fitsSingleBitmap(8193, 100))
    }

    @Test
    fun `恰好等于纹理上限可以通过`() {
        // 8192 × 8192 = 6710 万像素 > 4000 万，会被像素上限拦下
        assertFalse(
            "8192 见方总面积超标，应被像素上限拒绝",
            PdfLimits.fitsSingleBitmap(8192, 8192)
        )
        // 但 8192 × 4000 = 3276 万像素，在限内
        assertTrue(PdfLimits.fitsSingleBitmap(8192, 4000))
    }

    @Test
    fun `零或负尺寸一律拒绝`() {
        assertFalse(PdfLimits.fitsSingleBitmap(0, 100))
        assertFalse(PdfLimits.fitsSingleBitmap(100, 0))
        assertFalse(PdfLimits.fitsSingleBitmap(-1, 100))
    }

    @Test
    fun `像素上限与字节上限自洽`() {
        assertEquals(PdfLimits.MAX_PIXELS * 4L, PdfLimits.MAX_BITMAP_BYTES)
        assertTrue(PdfLimits.MAX_BITMAP_BYTES <= 160L * 1024 * 1024)
    }

    @Test
    fun `像素上限下最大正方形边长约 6324`() {
        val side = kotlin.math.sqrt(PdfLimits.MAX_PIXELS.toDouble()).toInt()
        assertTrue("实际边长 $side", side in 6300..6350)
        assertTrue(PdfLimits.fitsSingleBitmap(side, side))
        assertFalse(PdfLimits.fitsSingleBitmap(side + 200, side + 200))
    }

    @Test
    fun `分块上限必须比单张上限更严格`() {
        // 单块 4096² 比单张 4000 万像素更保守 —— 这是刻意留的 2 倍余量，
        // 因为驱动上传纹理时常有同样大小的暂存副本。
        assertTrue(
            "分块策略必须比单张位图上限更保守，否则等于没留余量",
            com.lpdf.reader.data.pdf.TileLimits.MAX_TILE_PIXELS < PdfLimits.MAX_PIXELS
        )
        assertEquals(8192, com.lpdf.reader.data.pdf.TileLimits.MAX_EDGE)
        assertEquals(PdfLimits.MAX_TEXTURE_EDGE, com.lpdf.reader.data.pdf.TileLimits.MAX_EDGE)
    }
}
