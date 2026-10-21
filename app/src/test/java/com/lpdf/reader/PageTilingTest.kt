package com.lpdf.reader

import com.lpdf.reader.data.pdf.PdfLimits
import com.lpdf.reader.data.pdf.TileLimits
import com.lpdf.reader.data.pdf.planTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 分块渲染几何测试（v1.5.0 核心）。
 *
 * ## 为什么这个测试最重要
 *
 * 用户的原话是「不管 PDF 页面尺寸文件多大都要渲染，不能停止渲染」。
 * 旧版遇到 `1168×13657` 就直接回一句「页面尺寸超出设备渲染上限，已跳过渲染」，
 * 用户看到的是一张白纸 —— 这是最糟的失败方式。
 *
 * 现在「能不能渲染」不再是判断题，而是几何题：把整页切成若干张都合法的位图。
 * 切法的正确性完全可以脱离 Android 环境验证，所以这里直接测纯函数 [planTiles]。
 *
 * ## 不变量
 *
 * 无论输入多离谱，必须同时满足：
 * 1. 每块都满足单张位图约束（[PdfLimits.fitsSingleBitmap]）
 * 2. 每块都落在整页范围内
 * 3. 块之间不重叠、整页无缝覆盖（否则接缝处会露白线）
 * 4. 降级时宽高比不变（否则标注全部错位）
 */
class PageTilingTest {

    /** 用若干真实/极端尺寸跑同一组不变量 */
    private fun assertInvariants(w: Int, h: Int) {
        val plan = planTiles(w, h)
        assertTrue("方案不能为空", plan.rects.isNotEmpty())

        plan.rects.forEach { r ->
            assertTrue(
                "[$w×$h] 块 ${r.left},${r.top} ${r.width}×${r.height} 超出单张位图上限",
                PdfLimits.fitsSingleBitmap(r.width, r.height)
            )
            assertTrue("[$w×$h] 块左边界越界", r.left >= 0 && r.top >= 0)
            assertTrue("[$w×$h] 块右边界越界", r.left + r.width <= w)
            assertTrue("[$w×$h] 块下边界越界", r.top + r.height <= h)
        }

        // 覆盖检查：同一行内相邻块必须首尾相接，最后一块贴到右边
        val byTop = plan.rects.groupBy { it.top }
        byTop.forEach { (_, row) ->
            val sorted = row.sortedBy { it.left }
            assertEquals("[$w×$h] 行首必须从 0 开始", 0, sorted.first().left)
            sorted.zipWithNext().forEach { (a, b) ->
                assertEquals(
                    "[$w×$h] 出现缝隙或重叠：${a.left + a.width} != ${b.left}",
                    a.left + a.width,
                    b.left
                )
            }
        }
        // 铺满检查的对象是「缩放后的整页尺寸」：
        // 分块模式 s=1 → 就是 w/h；降级模式 s<1 → 是 round(s·w)/round(s·h)
        val sw = (w * plan.scale).toInt().coerceIn(1, w)
        val sh = (h * plan.scale).toInt().coerceIn(1, h)
        val rightMost = plan.rects.maxOf { it.left + it.width }
        val bottomMost = plan.rects.maxOf { it.top + it.height }
        assertTrue(
            "[$w×$h] 右侧未铺满：$rightMost < $sw（scale=${plan.scale}）",
            rightMost >= sw - 1
        )
        assertTrue(
            "[$w×$h] 下方未铺满：$bottomMost < $sh（scale=${plan.scale}）",
            bottomMost >= sh - 1
        )
    }

    // ------------------------------------------------------------------
    // 必须能渲染：这些以前全都被拒绝
    // ------------------------------------------------------------------

    @Test
    fun `截图里的 1168x13657 单块即可完整清晰渲染`() {
        // 这是用户真机截图上报错的那一页
        val plan = planTiles(1168, 13657)
        assertTrue("1168×13657 高度未超 8192×2，应能切块搞定", plan.tileCount <= TileLimits.MAX_TILES)
        assertFalse("这个尺寸不该降级 —— 用户要的是清晰渲染", plan.degraded)
        assertEquals("不该缩放", 1f, plan.scale, 0.0001f)
        assertInvariants(1168, 13657)
    }

    @Test
    fun `1080x122103 极端长页降级后仍能出图且宽高比不变`() {
        // 5.27 亿字节崩溃报告对应的页面
        val plan = planTiles(1080, 122_103)
        assertTrue("这个尺寸必然超预算，应降级", plan.degraded)
        assertTrue("每块都要合法", plan.rects.all { PdfLimits.fitsSingleBitmap(it.width, it.height) })

        // 关键：降级后仍要「看得清」。旧公式法会缩到 72px 宽，等于没渲染。
        val renderedW = plan.rects.maxOf { it.left + it.width }
        assertTrue(
            "降级后宽度只有 $renderedW px，太糊了 —— 至少要能用满 4 块预算",
            renderedW >= 200
        )
        assertEquals("应把 4 块全部用上", TileLimits.MAX_TILES, plan.tileCount)

        // 宽高比：缩放前后误差必须在 1% 以内
        val origin = 1080.0 / 122_103.0
        val renderedH = plan.rects.maxOf { it.top + it.height }
        val scaled = renderedW.toDouble() / renderedH.toDouble()
        assertTrue(
            "降级破坏了宽高比（$origin vs $scaled）—— 标注会全部错位",
            abs(origin - scaled) / origin < 0.01
        )
    }

    @Test
    fun `任何尺寸都不再被拒绝`() {
        val samples = listOf(
            1168 to 13657,
            1080 to 122_103,
            2048 to 409_600,
            100 to 100,
            3600 to 2550,
            1080 to 1527,
            9 to 100_000,        // 极窄极长
            16_000 to 200,       // 极宽极扁
            50_000 to 50_000     // 离谱大图
        )
        samples.forEach { (w, h) ->
            val plan = planTiles(w, h)
            assertTrue("[$w×$h] 没有给出任何可渲染块", plan.rects.isNotEmpty())
            assertInvariants(w, h)
        }
    }

    // ------------------------------------------------------------------
    // 保清晰度：预算内的长页不该降级
    // ------------------------------------------------------------------

    @Test
    fun `预算内的长页保持 1x 缩放不降级`() {
        // 1168 宽时单块预算允许 14364 高，13657 落在内
        val plan = planTiles(1168, 13_657)
        assertFalse(plan.degraded)
        assertEquals(1f, plan.scale, 0.0001f)
    }

    @Test
    fun `A4 页面只有一块且不降级`() {
        val plan = planTiles(1080, 1527)
        assertEquals(1, plan.tileCount)
        assertFalse(plan.degraded)
        val r = plan.rects.first()
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(1080, r.width)
        assertEquals(1527, r.height)
    }

    // ------------------------------------------------------------------
    // 超宽页横向切分
    // ------------------------------------------------------------------

    @Test
    fun `超宽页按列切分而不是缩成一条线`() {
        // 宽度超过 8192 必须横向切 —— 只纵向切的话每块宽度仍然超限
        val plan = planTiles(16_000, 200)
        assertFalse(plan.degraded)
        assertTrue("16000 宽至少要切 2 列", plan.rects.map { it.left }.distinct().size >= 2)
        assertInvariants(16_000, 200)
    }

    // ------------------------------------------------------------------
    // 退化输入
    // ------------------------------------------------------------------

    @Test
    fun `零或负数不会抛异常`() {
        listOf(0 to 0, -1 to 100, 100 to -1, 0 to 1000).forEach { (w, h) ->
            val plan = planTiles(w, h)
            assertTrue("[$w×$h] 退化输入也要给出至少一块", plan.rects.isNotEmpty())
            plan.rects.forEach { r ->
                assertTrue("块尺寸必须为正", r.width > 0 && r.height > 0)
            }
        }
    }

    @Test
    fun `块数上限与单块预算自洽`() {
        assertEquals(
            TileLimits.MAX_TILES.toLong() * TileLimits.MAX_TILE_PIXELS,
            TileLimits.MAX_PAGE_PIXELS
        )
        // 4 块 × 64MB = 256MB，已接近低端机 Java heap 上限，不能再多
        assertTrue(TileLimits.MAX_TILES <= 4)
    }

    @Test
    fun `每块都满足单张纹理上限的完整校验`() {
        // 随机化一批尺寸，确保算法在各种比例下都不产出非法块
        val widths = listOf(320, 720, 1080, 1168, 1440, 2048, 4096, 9000)
        val heights = listOf(100, 480, 1527, 5000, 13657, 30_000, 122_103)
        widths.forEach { w ->
            heights.forEach { h ->
                planTiles(w, h).rects.forEach { r ->
                    assertTrue(
                        "[$w×$h] 产出非法块 ${r.width}×${r.height}",
                        PdfLimits.fitsSingleBitmap(r.width, r.height)
                    )
                }
            }
        }
    }
}
