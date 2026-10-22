package com.lpdf.reader.ui.page.reader

import androidx.compose.ui.unit.dp

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 打开 PDF 时的页面尺寸适配策略（v1.7.3）。
 *
 * ## 这一层是干什么的
 *
 * v1.7.2 已经把「渲染宽度」对齐到「实测内容区宽度」，但那只解决了
 * **横向**：页面左右不再各空一大块。打开文档的第一眼仍然是
 * 「页面只占屏幕中间一小条」——原因是**纵向**没人管：
 *
 * - 连续滚动模式：页面按内容区宽度等比撑开，A4 在竖屏上必然高过一屏，
 *   接着还要滚动才能看到整页，用户第一眼的感受就是「只看到一小条」。
 * - 单页模式：`BoxWithConstraints` 里用 `minOf(wPx, hPx / aspect)` 等比装进
 *   整个屏幕，但那是**整屏**，没有扣掉上下压着的玻璃工具条与页码胶囊，
 *   于是页面被这两层盖住头尾。400dp 高的可见区里，一条顶部工具条
 *   （约 56dp）加一条底部胶囊（约 96dp）就吃掉了 38%。
 *
 * ## 策略
 *
 * 一条规则：**让整页完整落在「没有任何 UI 压着」的区域里**。
 *
 * 1. 可用区域 = 内容区 − 状态栏 − 导航栏 − 顶部工具条 − 底部胶囊
 * 2. 页面宽度取 `min(内容区宽 − 左右留白, 可用高 ÷ 宽高比)`
 *    —— 即「宽不够就按宽，高不够就按高」，永远不会溢出
 * 3. 宽度向下取整到 2 的整数倍，避免长页把 1px 的取整误差累积成几十像素的
 *    页脚空白
 *
 * 纯函数，不依赖 Compose 运行时，因此可以直接单测各种屏型。
 * 「打开就能看全一页」这件事以前只能靠真机肉眼看，现在有了可执行的断言。
 */
object PageFit {

    /**
     * 连续滚动模式的左右留白。
     *
     * 小屏（可用宽 < 360dp，含分屏半屏、折叠屏外屏）几乎不留白，
     * 因为那点宽度比呼吸感更值钱；正常手机留 10dp。
     */
    fun horizontalPadding(contentWidthDp: Float): Float =
        if (contentWidthDp < 360f) 6f else 10f

    /** 连续模式两页之间的间距（dp），同时用作首尾的上下留白 */
    const val PAGE_GAP_DP = 12f

    /**
     * 阅读页在竖屏下的「玻璃条」占位（dp）。
     *
     * 数值与 [PdfReaderPage] 里工具栏 / 页码胶囊的实测高度一致：
     * 顶部工具条 44 + 上下各 8 的呼吸 = 60；底部胶囊 40 + 距底 20 + 上方 40 的呼吸 = 100。
     * 改 UI 高度时**必须同步这里**，否则页面又会钻到玻璃底下 ——
     * 这两个常量就是「玻璃条会盖住多少」的唯一事实来源。
     */
    const val TOP_CHROME_DP = 60f
    const val BOTTOM_CHROME_DP = 100f

    /**
     * 单屏（单页模式）能用来放页面的高度。
     *
     * @param availableHeightPx 内容区高度（已扣系统栏，由调用方实测）
     * @param density 屏幕密度，用于把 dp 占位换算成 px
     * @param chromeVisible 玻璃 UI 是否可见。隐藏时整屏都是可用区，
     *   页面会自动变大 —— 这正是 iOS / 微信读书的观感
     */
    fun contentHeightPx(
        availableHeightPx: Int,
        density: Float,
        chromeVisible: Boolean
    ): Int {
        if (availableHeightPx <= 0) return 1
        if (!chromeVisible) return availableHeightPx
        val reserve = ((TOP_CHROME_DP + BOTTOM_CHROME_DP) * density).toInt()
        // 再紧的屏也要留出一条缝，否则除零后页面会变成 0 宽
        return (availableHeightPx - reserve).coerceAtLeast((80 * density).toInt())
    }

    /**
     * 单页模式「一屏看全」的页面宽度（px）。
     *
     * @param contentWidthPx 内容区宽度（物理像素）
     * @param contentHeightPx 见 [contentHeightPx]
     * @param aspect 页高 / 页宽 = `heightPx / widthPx`
     */
    fun fitWidthPx(contentWidthPx: Int, contentHeightPx: Int, aspect: Float): Int {
        val safeAspect = aspect.takeIf { it.isFinite() && it > 0.01f } ?: 1.4142f
        val byWidth = contentWidthPx.coerceAtLeast(1)
        val byHeight = (contentHeightPx / safeAspect).toInt()
        // 向下取 2 的倍数：Bitmap 与 raster 在奇数宽上更容易出现半像素模糊
        val raw = min(byWidth, byHeight).coerceAtLeast(2)
        return raw / 2 * 2
    }

    /** 渲染宽度下限：低于这个宽度页面上什么都看不清，宁可让它溢出一点点 */
    const val MIN_WIDTH_PX = 240

    /**
     * 「一屏看全」模式是否成立。
     *
     * 极端长页（50:1 的 CAD 图、地图导出）在手机上按高算出来的宽度会小到
     * 只有几十像素 —— 整页是看全了，但一个字也认不出。
     * 这种情况下退回「按宽铺满 + 纵向滚动」，那才是这类内容正确的看法。
     */
    fun fitsFullPage(contentWidthPx: Int, contentHeightPx: Int, aspect: Float): Boolean {
        val w = fitWidthPx(contentWidthPx, contentHeightPx, aspect)
        // 被判为「不能一屏看全」的唯一理由：照高度装完之后窄到读不出字。
        // 判断依据必须是 fitWidthPx 的返回值，不能用 MIN_WIDTH_PX ——
        // 后者是渲染下限（240px），比可读下限宽松得多。
        return w >= (contentWidthPx * MIN_FULL_PAGE_RATIO).roundToInt()
    }

    /**
     * 一屏看全时，页面宽度至少占内容区宽度的比例。
     *
     * 0.35 而不是 0.5：手机上 A4 一屏一页大约落在 0.62 左右，
     * 留出足够余量给更「瘦高」的纸张（Legal、A4 双栏扫描件）。
     * 低于这个值说明页面被高度压得太扁，横向已经读不出字，
     * 此时宁可纵向滚动。
     */
    const val MIN_FULL_PAGE_RATIO = 0.35f

    /**
     * 连续滚动模式下，首屏是否**已经**完整显示一页。
     *
     * 判断依据：页面高 ≤ 可用高。满足时用户不该被自动缩放打扰，
     * 不满足时首屏会露出「半页 + 下一半页开头」，观感就是「只能看一小条」。
     */
    fun pageFitsViewport(pageWidthPx: Int, aspect: Float, availableHeightPx: Int): Boolean {
        val safeAspect = aspect.takeIf { it.isFinite() && it > 0.01f } ?: 1.4142f
        return pageWidthPx * safeAspect <= availableHeightPx
    }

    /**
     * 连续模式首屏适配后应该用的页面宽度。
     *
     * - 满宽已经装得下 → 原样返回（铺满，不动）
     * - 装不下但不能缩 → 原样返回（极端长页，交给纵向滚动）
     * - 装不下且缩完还读得出 → 缩到「一屏恰好一页」
     */
    fun firstScreenWidthPx(contentWidthPx: Int, availableHeightPx: Int, aspect: Float): Int {
        if (availableHeightPx <= 0 || contentWidthPx <= 0) return contentWidthPx
        if (pageFitsViewport(contentWidthPx, aspect, availableHeightPx)) return contentWidthPx
        val fitted = fitWidthPx(contentWidthPx, availableHeightPx, aspect)
        return if (fitted >= (contentWidthPx * MIN_FULL_PAGE_RATIO).roundToInt()) {
            fitted
        } else {
            contentWidthPx
        }
    }

    /** dp 常量转 Compose `Dp`，供 UI 直接用（避免调用方再写一遍 `6.dp` / `10.dp`） */
    val smallPadding = 6.dp
    val normalPadding = 10.dp
}
