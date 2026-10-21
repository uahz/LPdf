package com.lpdf.reader

import com.lpdf.reader.data.pdf.PdfLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染尺寸上限测试（v1.3.3）。
 *
 * ## 触发这个问题的真机崩溃
 *
 * OnePlus PKG110 / Android 17，`CrashReporter` 抓到的原始栈：
 *
 * ```
 * RuntimeException: Canvas: trying to draw too large(527487968bytes) bitmap.
 *   at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:267)
 *   at android.graphics.BaseRecordingCanvas.drawBitmap(BaseRecordingCanvas.java:154)
 *   at androidx.compose.ui.graphics.AndroidCanvas  (混淆为 h0.a.n)
 *   at androidx.compose.ui.graphics.painter.BitmapPainter (k0.a.e)
 * ```
 *
 * 5.27 亿字节 ÷ 4（ARGB_8888）= 1.32 亿像素。这不是我们想渲染的尺寸。
 *
 * ## 根因
 *
 * `renderPage` 原来只限制**宽度**：
 *
 * ```kotlin
 * val width = targetWidthPx.coerceIn(64, MAX_RENDER_WIDTH)  // 2048
 * val w = page.getPageWidth(width)
 * val h = page.getPageHeight(width)                        // ← 这里失控
 * ```
 *
 * `getPageHeight(width)` 是「按给定宽度缩放后」的高度。对于长宽比极度悬殊的页面
 * （CAD 施工图、地图、超长扫描件），宽度 1080px 时高度可达几十万像素。
 * `MAX_RENDER_WIDTH` 只约束了 w，对面积毫无作用。
 *
 * ## 测试策略
 *
 * `renderSize` 需要真实的 `PdfPage`（native），单测里造不出来。
 * 因此这里直接对**约束函数** [PdfLimits.fitsHardwareTexture] 做断言 ——
 * 它是最后一道闸门，只要它判定为 false，就不会调用 `Bitmap.createBitmap`。
 */
class RenderSizeLimitTest {

    // ------------------------------------------------------------------
    // 真实崩溃数据：必须被拦下
    // ------------------------------------------------------------------

    @Test
    fun `5点27亿字节的位图必须被拒绝`() {
        // 原始崩溃：527487968 bytes / 4 = 131,871,992 像素
        // 假设宽度是常见的 1080，则高度约为 122,103
        assertFalse(
            "这个尺寸正是真机崩溃的那个，必须判定为不可渲染",
            PdfLimits.fitsHardwareTexture(1080, 122_103)
        )
    }

    @Test
    fun `超长施工图页面被拒绝`() {
        // 1:200 的地铁线路图，宽度 2048
        assertFalse(PdfLimits.fitsHardwareTexture(2048, 409_600))
    }

    // ------------------------------------------------------------------
    // 正常页面：不能被误杀
    // ------------------------------------------------------------------

    @Test
    fun `手机屏幕宽度的 A4 页面可以通过`() {
        // 1080 × 1527 是典型 A4 在 1080p 屏上的尺寸
        assertTrue(PdfLimits.fitsHardwareTexture(1080, 1527))
    }

    @Test
    fun `平板双页横版 A4 可以通过`() {
        // 2560 平板，双页拼版约 3600×2550
        assertTrue(PdfLimits.fitsHardwareTexture(3600, 2550))
    }

    @Test
    fun `最高允许宽度 2048 的正常比例页面可以通过`() {
        assertTrue(PdfLimits.fitsHardwareTexture(2048, 2896))
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `超过单边纹理上限被拒绝`() {
        // 8193 已超出保底纹理上限，即便总面积不大
        assertFalse(PdfLimits.fitsHardwareTexture(8193, 100))
    }

    @Test
    fun `恰好等于纹理上限可以通过`() {
        // 8192 × 8192 = 6710 万像素 > 4000 万，会被像素上限拦下
        assertFalse(
            "8192 见方总面积超标，应被像素上限拒绝",
            PdfLimits.fitsHardwareTexture(8192, 8192)
        )
        // 但 8192 × 4000 = 3276 万像素，在限内
        assertTrue(PdfLimits.fitsHardwareTexture(8192, 4000))
    }

    @Test
    fun `零或负尺寸一律拒绝`() {
        assertFalse(PdfLimits.fitsHardwareTexture(0, 100))
        assertFalse(PdfLimits.fitsHardwareTexture(100, 0))
        assertFalse(PdfLimits.fitsHardwareTexture(-1, 100))
    }

    @Test
    fun `像素上限与字节上限自洽`() {
        // MAX_BITMAP_BYTES 必须是 MAX_PIXELS × 4，否则两处判断会打架
        assertTrue(PdfLimits.MAX_BITMAP_BYTES == PdfLimits.MAX_PIXELS * 4L)
        // 上限本身应当在 150MB 以内，避免触发 OOM 而非 draw 崩溃
        assertTrue(PdfLimits.MAX_BITMAP_BYTES <= 160L * 1024 * 1024)
    }

    @Test
    fun `长宽比上限能覆盖常见的极端页面`() {
        // 16:1 应当能容下绝大多数工程图；真正的异形页面（>16:1）会被缩宽度
        assertTrue(PdfLimits.MAX_ASPECT >= 16f)
        // 不能设得太小，否则 1:3 的竖版长图会被误缩
        val tallPageAspect = 3f
        assertTrue(tallPageAspect < PdfLimits.MAX_ASPECT)
    }

    @Test
    fun `像素上限下最大正方形边长约 6324`() {
        // sqrt(40_000_000) ≈ 6324，这是本策略允许的最宽正方形
        val side = kotlin.math.sqrt(PdfLimits.MAX_PIXELS.toDouble()).toInt()
        assertTrue("实际边长 $side", side in 6300..6350)
        assertTrue(PdfLimits.fitsHardwareTexture(side, side))
        assertFalse(PdfLimits.fitsHardwareTexture(side + 200, side + 200))
    }
}
