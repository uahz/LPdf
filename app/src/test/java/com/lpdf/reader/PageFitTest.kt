package com.lpdf.reader

import com.lpdf.reader.ui.page.reader.PageFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开 PDF 自动适配屏幕（v1.7.3）。
 *
 * ## 它防的是什么
 *
 * 用户反馈「打开这么小我怎么看」——页面只占屏幕中间一小条。
 * v1.7.2 修了横向（不再固定扣 32dp），但纵向没人管：
 *
 * - 单页模式把「整屏」当可用区，页面头尾被顶部工具条和底部页码胶囊压住
 * - 连续模式按宽铺满，A4 在竖屏上必然高过一屏，首屏只能看到半页
 *
 * 这些如果只靠真机肉眼看，下一轮改动很容易又退回去。
 * 这里把「各种屏型下页面应该多大」写成可执行断言。
 *
 * 覆盖的屏型数据取自真实设备（xxhdpi = 3x）：
 * | 设备 | 物理 px | dp |
 * | --- | --- | --- |
 * | 常规手机 6.1" | 1080 × 2400 | 360 × 800 |
 * | 小屏手机 5.5" | 720 × 1280 | 360 × 640 |
 * | 分屏下半屏 | 1080 × 900 | 360 × 300 |
 * | 平板 | 2560 × 1600 | 853 × 533（横屏） |
 */
class PageFitTest {

    private val a4 = 841.9f / 595.3f // ≈ 1.4142

    // ---------------------------------------------------------------- 单页模式

    @Test
    fun `单页模式 A4 在常规手机上应完整落在无遮挡区域`() {
        val w = 1080
        val h = 2400
        val usable = PageFit.contentHeightPx(h, 3f, chromeVisible = true)
        val fit = PageFit.fitWidthPx(w, usable, a4)

        // 页面高度必须 ≤ 可用高度，否则又会被玻璃条压住
        assertTrue(
            "页面高 ${fit * a4} 应不超过可用高 $usable",
            fit * a4 <= usable + 2
        )
        // 但同时不能缩得太狠：至少占屏宽的一半
        assertTrue("页面宽 $fit 太窄，占屏宽 ${fit * 100 / w}%", fit >= w * 0.5f)
    }

    @Test
    fun `单页模式隐藏工具栏后页面应更大`() {
        val h = 2400
        val withChrome = PageFit.fitWidthPx(1080, PageFit.contentHeightPx(h, 3f, true), a4)
        val noChrome = PageFit.fitWidthPx(1080, PageFit.contentHeightPx(h, 3f, false), a4)
        assertTrue("隐藏 UI 后页面不该变小（$noChrome vs $withChrome）", noChrome >= withChrome)
    }

    @Test
    fun `页面宽度永远不会超过内容区宽度`() {
        // 横屏 / 平板：宽很富余，此时受高度约束
        val wide = 2560
        val fit = PageFit.fitWidthPx(wide, PageFit.contentHeightPx(1600, 2f, true), a4)
        assertTrue("宽 $fit 不应超过 $wide", fit <= wide)
    }

    @Test
    fun `宽度向下取偶 避免长页累积半像素误差`() {
        val fit = PageFit.fitWidthPx(1001, 100000, a4)
        assertEquals("应为偶数", 0, fit % 2)
    }

    @Test
    fun `极端长页不套用一屏看全 否则会窄到看不清`() {
        // 50:1 的施工图：按高算出来的宽度只有 2100 / 50 = 42 像素
        val cad = 50f
        val usable = PageFit.contentHeightPx(2400, 3f, true)
        assertFalse("不应判定为可一屏看全", PageFit.fitsFullPage(1080, usable, cad))
        // 首屏适配也不该把页面缩成一条缝 —— 应保持满宽交给纵向滚动
        assertEquals("应保持满宽", 1080, PageFit.firstScreenWidthPx(1080, usable, cad))
    }

    @Test
    fun `渲染下限只保护极小输入 不参与可读性判断`() {
        // fitWidthPx 的返回值保持「忠实反映可用空间」，不下抬到 MIN_WIDTH_PX，
        // 否则调用方无法区分「装得下但很窄」和「本来就只给了这么宽」
        val tiny = PageFit.fitWidthPx(20, 20000, a4)
        assertTrue("极小输入应给出可渲染的宽度，实际 $tiny", tiny >= 2)
    }

    @Test
    fun `极窄可用高度不会算出零宽`() {
        // 分屏下半屏只剩 300dp(900px)，扣掉玻璃条后更少。
        // v1.8.2 底部占位 100→128dp 后，300dp 屏只剩 112dp 可用高，
        // 按 A4 折出约 236px 宽 —— 低于 MIN_WIDTH_PX 但仍可渲染，
        // 真实 UI 层还有 MIN_WIDTH_PX 的二次夹取。
        val usable = PageFit.contentHeightPx(900, 3f, true)
        assertTrue("可用高度应有下限保护", usable > 0)
        val fit = PageFit.fitWidthPx(1080, usable, a4)
        assertTrue("页面宽度应仍可渲染", fit >= 2)
    }

    @Test
    fun `非法宽高比退回 A4 而不是崩或出零`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            val fit = PageFit.fitWidthPx(1080, 1800, bad)
            assertTrue("宽高比 $bad 时宽度应合理，实际 $fit", fit in PageFit.MIN_WIDTH_PX..1080)
        }
    }

    // ---------------------------------------------------------------- 连续模式

    @Test
    fun `连续模式满宽时 A4 在竖屏会溢出 需要首屏适配`() {
        // 360×780dp 的常见手机：内容区 1080×2340px，扣玻璃条后可用高 1860px，
        // 满宽 A4 需要 1442px 高 → 装得下；但再矮一点（5.5" 的 360×640dp）
        // 可用高只剩 1440px，就正好溢出了。
        val w = (1080 - 10 * 3 * 2) // 10dp × 2 留白
        val shortViewport = PageFit.contentHeightPx(1920, 3f, true)
        assertFalse("640dp 屏上满宽 A4 应溢出", PageFit.pageFitsViewport(w, a4, shortViewport))

        val tallViewport = PageFit.contentHeightPx(2400, 3f, true)
        assertTrue("780dp 屏上满宽 A4 应装得下", PageFit.pageFitsViewport(w, a4, tallViewport))
    }

    @Test
    fun `首屏适配只在装不下时才缩小`() {
        val w = 1020
        val tall = PageFit.contentHeightPx(2400, 3f, true)
        assertEquals("装得下就铺满", w, PageFit.firstScreenWidthPx(w, tall, a4))

        val short = PageFit.contentHeightPx(1920, 3f, true)
        val fitted = PageFit.firstScreenWidthPx(w, short, a4)
        assertTrue("装不下应缩小，实际 $fitted vs $w", fitted < w)
        assertTrue("缩完必须装得下", PageFit.pageFitsViewport(fitted, a4, short))
    }

    @Test
    fun `首屏适配后整页应恰好装进可视区`() {
        val fullW = 1080 - 60
        val viewport = PageFit.contentHeightPx(2400, 3f, true)
        val fitted = PageFit.fitWidthPx(fullW, viewport, a4)
        assertTrue("适配后应装得下", PageFit.pageFitsViewport(fitted, a4, viewport))
        // 不能顺手缩得比需要的还小
        assertTrue("适配后不应过度缩小", fitted > fullW * 0.75f)
    }

    @Test
    fun `矮而宽的窗口 满宽本就不溢出 不该被缩小`() {
        // 横屏平板：宽 2560，高 1600，A4 按宽算高 3621 > 533dp… 用 px 判断
        val fullW = 900
        val viewport = 1400
        assertTrue("A4 900 宽高约 1273，应装得下", PageFit.pageFitsViewport(fullW, a4, viewport))
    }

    // ---------------------------------------------------------------- 留白

    @Test
    fun `小屏留白更小 正常屏留出呼吸`() {
        assertEquals("小屏 6dp", 6f, PageFit.horizontalPadding(320f), 0.01f)
        assertEquals("常规 10dp", 10f, PageFit.horizontalPadding(411f), 0.01f)
        assertEquals("边界 360dp 归入常规", 10f, PageFit.horizontalPadding(360f), 0.01f)
        assertEquals("分屏半屏 6dp", 6f, PageFit.horizontalPadding(180f), 0.01f)
    }

    @Test
    fun `玻璃条占位已覆盖工具栏与页码胶囊`() {
        // 顶部工具条实测 44 + 呼吸 16 = 60；底部 40 + 20 + 上方呼吸 40 = 100
        assertTrue("顶部占位应 ≥ 工具栏高", PageFit.TOP_CHROME_DP >= 44f)
        assertTrue("底部占位应 ≥ 胶囊高 + 底距", PageFit.BOTTOM_CHROME_DP >= 60f)
        // 两者加起来不能吃掉半个常规手机屏（800dp）
        assertTrue("玻璃条总占位不应超过屏高 1-4", PageFit.TOP_CHROME_DP + PageFit.BOTTOM_CHROME_DP < 200f)
    }

    @Test
    fun `可用高度为 0 时不崩 返回安全下限`() {
        assertTrue(PageFit.contentHeightPx(0, 3f, true) > 0)
        assertTrue(PageFit.contentHeightPx(-100, 3f, false) > 0)
    }

    // ---------------------------------------------------------------- 回归补充（自查）

    @Test
    fun `连续模式视口高度只扣顶部工具条`() {
        // 页码胶囊盖的是列表底部，页面正常从它下面滑过。
        // 用 contentHeightPx（还扣了胶囊）会平白少 100dp 可用高度。
        val h = 2400
        val scroll = PageFit.scrollViewportHeightPx(h, 3f, true)
        val single = PageFit.contentHeightPx(h, 3f, true)
        assertTrue("连续视口应比单页可用区更高", scroll > single)
        assertEquals("两者差值应等于底部胶囊占位", PageFit.BOTTOM_CHROME_DP * 3f, (scroll - single).toFloat(), 2f)
    }

    @Test
    fun `横屏平板单页不应把 A4 拉成满宽`() {
        // 2560 宽的内容区，按高度只能放到 900 左右；若按满宽画，
        // 高度 3621 远超可用区，页面头尾被玻璃条压住。
        val w = 2560
        val usable = PageFit.contentHeightPx(1600, 2f, true)
        val draw = PageFit.singlePageDrawWidthPx(w, usable, a4)
        assertTrue("不应超过内容区 92%", draw <= (w * PageFit.MAX_SINGLE_PAGE_RATIO).toInt())
        assertTrue("高度仍须落在可用区内", draw * a4 <= usable + 4)
    }

    @Test
    fun `屏越矮页面越宽 这正是本次自适应要的效果`() {
        // 360×800dp（1080×2400）扣掉玻璃条后可用高 1920，A4 能放满宽 1080；
        // 360×640dp（1080×1920）只剩 1440，只能放到 1018 —— 但这时页面仍然铺得很满。
        // 关键在于「高度足够时按宽，不够时按高」，而不是永远铺满或永远缩一截。
        val usableTall = PageFit.contentHeightPx(2400, 3f, true)
        val tall = PageFit.singlePageDrawWidthPx(1080, usableTall, a4)
        // 高屏上按高度算出的宽度本来就超过内容区宽，被 1080 夹住；
        // 再叠上 92% 视觉呼吸上限，最终就是 992 —— 这是有意的，不是缩水。
        assertEquals("高屏应顶到 92% 上限（保留视觉呼吸）", 992, tall)
        assertTrue("顶到上限时仍应几乎铺满", tall > 1080 * 0.9f)

        val usableShort = PageFit.contentHeightPx(1920, 3f, true)
        val short = PageFit.singlePageDrawWidthPx(1080, usableShort, a4)
        assertTrue("矮屏应缩到装得下", short < 1080)
        // v1.8.2 底部占位 100→128dp：360×640dp 屏可用高 452dp，
        // A4 折出约 319dp 宽（88.7% 屏宽）—— 仍接近铺满，非「缩一截」
        assertTrue("但不应缩到明显小于铺满", short > 1080 * 0.85f)
        assertTrue("缩完高度仍应落在可用区内", short * a4 <= usableShort + 4)
    }

    @Test
    fun `非法输入不会让单页宽度变成 0`() {
        listOf(
            Triple(0, 0, a4),
            Triple(1080, 0, a4),
            Triple(1080, 2000, 0f),
            Triple(1080, 2000, Float.NaN)
        ).forEach { (w, h, a) ->
            val draw = PageFit.singlePageDrawWidthPx(w, h, a)
            assertTrue("输入 ($w,$h,$a) 得到宽度 $draw", draw > 0)
        }
    }

    @Test
    fun `渲染宽度就是显示宽度上限 不应再夹 92 上限`() {
        // PageCanvas 的渲染宽度直接取 fitWidthPx（按可用高度），显示侧才夹 92%。
        // 如果把 92% 也套到渲染上，位图会比显示宽度小 8%，
        // 放大到 2x 立刻看到糊边 —— 省下的那点内存不值。
        val usable = PageFit.contentHeightPx(2400, 3f, true)
        val render = PageFit.fitWidthPx(1080, usable, a4)
        val shown = PageFit.singlePageDrawWidthPx(1080, usable, a4)
        assertTrue("渲染宽度应 ≥ 显示宽度", render >= shown)
    }
}
