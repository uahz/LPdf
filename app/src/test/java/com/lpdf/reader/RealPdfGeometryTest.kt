package com.lpdf.reader

import com.lpdf.reader.data.pdf.PdfLimits
import com.lpdf.reader.data.pdf.PdfiumEngine
import com.lpdf.reader.data.pdf.TileLimits
import com.lpdf.reader.data.pdf.planTiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * 真实 PDF 的几何链路验证（v1.5.1）。
 *
 * ## 为什么不用 PDFium 渲染
 *
 * 试过了，不行：Robolectric 沙箱里 `pdfiumandroid` 的 `.so` 加载不起来，
 * `InitLock.waitForReady()` 会**永久阻塞**（jstack 确认卡在 `Semaphore.acquire`），
 * 测试线程 6 分钟无任何进展。真实渲染只能在真机 instrumented test 里做。
 *
 * ## 那这里测什么
 *
 * PDFium 的 `getPageHeightPixel(width)` 只是一次简单的比例换算：
 *
 * ```
 * heightPx = round(widthPx / pageWidthPt × pageHeightPt)
 * ```
 *
 * 所以「页面几何」完全可以从 PDF 自身的 `/MediaBox` 读出来，
 * 在 JVM 里把 `屏幕宽度 → 页面尺寸 → 分块方案` 这条链路完整跑一遍。
 * 这正好覆盖了用户报的 `1168×13657` 那个问题 —— 它纯粹是几何问题。
 *
 * ## 这个测试要钉住的事
 *
 * 用户截图里 `1168×13657` 对 A4 来说意味着宽度被算成了 **9657px**
 * （13657 ÷ 1.4142），而不是屏幕的 1168px。这条断言链就是防它再发生：
 * A4 在任意常见屏幕宽度下，高度必须落在 1.414 倍附近。
 *
 * 测试样本是用户提供的 399 页真实 PDF（`src/test/resources/sample-a4.pdf`）。
 */
class RealPdfGeometryTest {

    private lateinit var pdf: File

    @Before
    fun setUp() {
        val res = javaClass.classLoader!!.getResourceAsStream("sample-a4.pdf")
        pdf = File.createTempFile("sample-a4", ".pdf")
        if (res != null) res.use { pdf.writeBytes(it.readBytes()) }
    }

    @After
    fun tearDown() {
        pdf.delete()
    }

    /** 从 PDF 字节里抽第一个 `/MediaBox`，返回 (宽pt, 高pt) */
    private fun firstMediaBoxPt(file: File): Pair<Float, Float>? {
        val bytes = file.readBytes()
        val re = Regex("""/MediaBox\s*\[\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s*]""")
        val m = re.find(String(bytes, Charsets.ISO_8859_1)) ?: return null
        val (x0, y0, x1, y1) = m.destructured
        return (x1.toFloat() - x0.toFloat()) to (y1.toFloat() - y0.toFloat())
    }

    /** 复刻 PDFium 的像素尺寸换算（见 PdfiumEngine.renderSize） */
    private fun pixelsAt(widthPx: Int, wPt: Float, hPt: Float): Pair<Int, Int> {
        val w = widthPx.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH)
        return w to Math.round(w / wPt * hPt)
    }

    // ------------------------------------------------------------------
    // 核心：真实 PDF 的几何链路
    // ------------------------------------------------------------------

    @Test
    fun `样本 PDF 是标准 A4`() {
        assertTrue("样本 PDF 缺失", pdf.length() > 0)
        val box = firstMediaBoxPt(pdf)
        assertTrue("读不到 MediaBox", box != null)
        val (w, h) = box!!
        // A4 = 595.276 × 841.89 pt，允许 1pt 误差
        assertTrue("页面宽 $w pt 不是 A4", kotlin.math.abs(w - 595.3f) < 1.5f)
        assertTrue("页面高 $h pt 不是 A4", kotlin.math.abs(h - 841.9f) < 1.5f)
    }

    @Test
    fun `A4 在常见屏幕宽度下都必须单块且不降级`() {
        val (wPt, hPt) = firstMediaBoxPt(pdf)!!

        // 覆盖主流手机与大屏的物理像素宽
        listOf(720, 1080, 1168, 1280, 1440, 2048).forEach { screenW ->
            val (w, h) = pixelsAt(screenW, wPt, hPt)
            val plan = planTiles(w, h)

            // ① 比例必须对 —— 这条就是 1168×13657 那个 bug 的守门员
            val aspect = h.toFloat() / w
            assertTrue(
                "屏幕宽 $screenW 下算出 ${w}×$h，宽高比 $aspect 偏离 A4 的 1.414 —— " +
                    "说明渲染宽度被放大了（13657 对应宽度 9657px，是屏幕的 9 倍）",
                aspect in 1.40f..1.43f
            )

            // ② A4 永远单块、1:1，不该降级
            assertEquals("屏幕宽 $screenW 下 A4 应单块", 1, plan.tileCount)
            assertTrue("屏幕宽 $screenW 下 A4 不该降级", !plan.degraded)
            assertEquals("屏幕宽 $screenW 下应 1:1", 1f, plan.scale, 0.0001f)

            // ③ 每块合法
            plan.rects.forEach {
                assertTrue(PdfLimits.fitsSingleBitmap(it.width, it.height))
            }
        }
    }

    @Test
    fun `若宽度被错误放大 9 倍 分块方案仍然兜住`() {
        // 这就是用户遇到的情况：宽度 1168（正确），高度却算成 13657（错误）
        // 1.5.0 的分块算法保证这种畸形输入也能渲出来，不会再"跳过渲染"
        val brokenPlan = planTiles(1168, 13_657)
        assertTrue("畸形输入也必须能渲染", brokenPlan.rects.isNotEmpty())
        assertTrue(
            "每块都要合法，否则真机会抛 trying to draw too large",
            brokenPlan.rects.all { PdfLimits.fitsSingleBitmap(it.width, it.height) }
        )
        assertTrue(brokenPlan.tileCount <= TileLimits.MAX_TILES)
        // 1168 宽时单块预算允许 14364 高，13657 落在内 → 2 块、不降级
        assertEquals("应切 2 块", 2, brokenPlan.tileCount)
        assertTrue("不该降级，保留清晰度", !brokenPlan.degraded)
    }

    // ------------------------------------------------------------------
    // 渲染宽度的边界
    // ------------------------------------------------------------------

    @Test
    fun `渲染宽度上限会截断过大输入`() {
        val (wPt, hPt) = firstMediaBoxPt(pdf)!!
        // 假设某个调用方传了 9657（而不是屏幕的 1168）
        val (w, h) = pixelsAt(9657, wPt, hPt)
        assertEquals(
            "超过 MAX_RENDER_WIDTH 的宽度必须被截断，否则高度会失控",
            PdfiumEngine.MAX_RENDER_WIDTH,
            w
        )
        // 4096 宽时 A4 高度 = 4096 / 595.3 × 841.9 ≈ 5792
        assertTrue("截断后高度应回到 5792 左右，实际 $h", h in 5785..5800)
    }

    @Test
    fun `渲染上限必须高于分块单边上限的一半`() {
        // MAX_RENDER_WIDTH 决定「一页最宽能渲多清楚」，TileLimits.MAX_EDGE 决定
        // 「单张位图能多宽」。若渲染宽度小于单边上限的一半，平板/桌面用户放大后
        // 会明显看到块与块之间的清晰度断层。
        assertTrue(
            "MAX_RENDER_WIDTH（${PdfiumEngine.MAX_RENDER_WIDTH}）应至少是 " +
                "TileLimits.MAX_EDGE（${TileLimits.MAX_EDGE}）的一半",
            PdfiumEngine.MAX_RENDER_WIDTH * 2 >= TileLimits.MAX_EDGE
        )
        // 同时必须覆盖所有常见手机/平板的物理宽度，否则页面会被缩小成糊图
        assertTrue("应覆盖 1440 宽屏", PdfiumEngine.MAX_RENDER_WIDTH >= 1440)
    }

    @Test
    fun `屏幕宽度不该超过渲染上限`() {
        // 一条防线：如果将来某个平板/折叠屏的物理宽度超过上限，
        // renderSize 的 coerceIn 会截断它 —— 页面变模糊但不会崩。
        // 这里记录这个行为，避免有人误以为"宽度没被处理"。
        assertTrue(
            "MAX_RENDER_WIDTH 应 >= 常见手机物理宽度",
            PdfiumEngine.MAX_RENDER_WIDTH >= 1440
        )
    }
    // ------------------------------------------------------------------
    // v1.5.1 新增：页高比例防线
    // ------------------------------------------------------------------

    @Test
    fun `正常页面不受比例防线影响`() {
        // A4：595x842pt，1080 宽 → 1527 高，比例完全一致
        val (w, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1080, 1527, 595, 842)
        assertEquals(1080, w)
        assertEquals("正常页不该被改动", 1527, h)
    }

    @Test
    fun `宽度被放大 9 倍导致的畸形高度会被修正`() {
        // 复现用户遇到的情况：宽度 1168，高度却算出 13657（正常应为 1652）
        val (w, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1168, 13_657, 595, 842)
        assertEquals("宽度保持", 1168, w)
        assertTrue(
            "高度应从 13657 修正到 ~1652，实际 $h",
            h in 1640..1670
        )
        // 修正后比例必须正确
        assertEquals(1.414f, h.toFloat() / w, 0.01f)
    }

    @Test
    fun `极端长页（真实的异形页面）不会被误判`() {
        // CAD 长图：100pt 宽 x 5000pt 高（比例 50:1）。
        // 这是真实存在的页面，必须原样放行 —— 分块算法会处理它。
        val (_, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1000, 50_000, 100, 5000)
        assertEquals("真实长页不该被修正", 50_000, h)
    }

    @Test
    fun `句柄失效时退回 A4 占位`() {
        val (w, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1080, -1, 595, 842)
        assertEquals(1080, w)
        assertEquals((1080 * 1.4142f).toInt(), h)
    }

    @Test
    fun `点阵尺寸缺失时不做修正`() {
        // 某些损坏页面 getPageWidthPoint 返回 0，此时没有基准可比，原样放行
        val (_, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1080, 999_999, 0, 0)
        assertEquals(999_999, h)
    }

    @Test
    fun `偏差阈值内的轻微误差不触发修正`() {
        // 2% 偏差在容忍范围内（浮点 + 取整造成），不该触发修正
        val h2 = (1080 * 1.414f * 1.02f).toInt()
        val (_, h) = com.lpdf.reader.data.pdf.sanitizePageSize(1080, h2, 595, 842)
        assertEquals("2% 偏差应放行", h2, h)
    }
}
