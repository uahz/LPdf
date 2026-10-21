package com.lpdf.reader.data.pdf

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分块阈值。
 *
 * 这些数值不是拍脑袋，每一档都有明确的物理依据：
 */
object TileLimits {

    /**
     * 单张位图单边上限 = GPU 纹理上限。
     *
     * 8192 是业界保底值（Adreno / Mali 从 GLES3 起都至少支持 8192）。
     * 超过它，Bitmap 本身能建出来，但 `RecordingCanvas.drawBitmap` 会抛
     * `trying to draw too large(N bytes)` —— v1.3.2 的真机崩溃就是这么来的。
     */
    const val MAX_EDGE = 8192

    /**
     * 单块像素上限 = 4096² ≈ 1677 万像素 ≈ 64MB（ARGB_8888）。
     *
     * 为什么不直接用到 8192×8192（6700 万像素 = 256MB）：
     * 一块位图上传纹理时，驱动往往还要一份同样大小的暂存副本，实际峰值是 2 倍。
     * 256MB 的块在中低端机上必然 OOM。限制在 64MB 是留了 2 倍余量的安全值。
     */
    const val MAX_TILE_PIXELS = 16_777_216L

    /**
     * 一页最多切几块。
     *
     * 4 块 × 64MB = 256MB，已经接近低端机（Java heap 256~512MB）的上限。
     * 超过 4 块说明这个页面本身已经极端到「切了也看不清」，
     * 此时降清晰度让用户至少能看到整页，比给一堆看不清的碎块更有意义。
     */
    const val MAX_TILES = 4

    /** 一页所有块的总像素预算 */
    const val MAX_PAGE_PIXELS = MAX_TILES.toLong() * MAX_TILE_PIXELS
}

/**
 * 计算分块方案（v1.5.0 核心算法）。
 *
 * 纯函数，不碰 Bitmap、不碰 PDFium —— 因此可以直接单测几何正确性，
 * 这是「改对了」和「只是没报错」的区别所在。
 *
 * ## 算法
 *
 * 1. **横向**：宽度超过 8192 就分列（超宽页，如横向展板）
 * 2. **纵向**：每行的高度同时受 8192 单边与 64MB 单块预算约束
 * 3. **预算判定**：块数 ≤ [TileLimits.MAX_TILES] 且总像素在预算内 → 分块渲染
 * 4. 否则 **整体等比缩小** 到单块能装下，保持宽高比
 *
 * ## 为什么降级也要保持宽高比
 *
 * 标注是归一化坐标，UI 高度靠 `heightPx / widthPx` 算。
 * 一旦降级时把高度截断（旧版 `renderSize()` 的第③步就是这么干的），
 * 页面会被压扁，标注全部错位。这里只用**等比缩放**，绝不单独截断某一边。
 *
 * @param fullWidthPx 按屏幕宽度算出的整页理想宽（px）
 * @param fullHeightPx 对应的整页理想高（px），必须与 PDF 真实比例一致
 */
fun planTiles(fullWidthPx: Int, fullHeightPx: Int): TilePlan {
    val w = fullWidthPx.coerceAtLeast(1)
    val h = fullHeightPx.coerceAtLeast(1)

    // ① 按 1x 切块，装得下就保 1:1 清晰度 —— 这是绝大多数页面的路径
    if (fits(w, h)) {
        return TilePlan(w, h, scale = 1f, degraded = false, rects = gridTiles(w, h))
    }

    // ② 超预算 → 二分求「仍然合法」的最大缩放系数
    //
    // 为什么要二分而不是套公式：
    // 直观的写法是 s = sqrt(MAX_TILES × 单块预算 / 总像素)，但对
    // 1080×122103 这种页面算出的 s 会被「单边 8192」再压一刀，最后落到
    // 72×8192 —— 技术上「渲染了」，实际一片糊，等于没解决问题。
    //
    // 合法性的真实形状是「整数网格 + 单块 64MB」决定的分段函数，
    // 闭式解写不出来。二分保证拿到的是**这段函数上的最大值**：
    //   同一页 1080×122103 → 290×32768（4 块），清晰度是公式法的 4 倍。
    //
    // 60 次二分足够精确到 float 的分辨率，成本可忽略（纯算术，无分配）。
    var lo = 0.0
    var hi = 1.0
    repeat(60) {
        val mid = (lo + hi) / 2
        val sw = (w * mid).roundToInt().coerceAtLeast(1)
        val sh = (h * mid).roundToInt().coerceAtLeast(1)
        if (fits(sw, sh)) lo = mid else hi = mid
    }

    // ③ 浮点二分边界可能差一个像素导致不合法，向下取整后再校验
    var s = lo
    var sw = (w * s).roundToInt().coerceAtLeast(1)
    var sh = (h * s).roundToInt().coerceAtLeast(1)
    while (!fits(sw, sh) && s > 1e-4) {
        s *= 0.98
        sw = (w * s).roundToInt().coerceAtLeast(1)
        sh = (h * s).roundToInt().coerceAtLeast(1)
    }

    return TilePlan(w, h, s.toFloat(), degraded = true, rects = gridTiles(sw, sh))
}

/** 某尺寸能否在预算内切成合法块 */
private fun fits(w: Int, h: Int): Boolean {
    if (w <= 0 || h <= 0) return false
    if (w.toLong() * h.toLong() > TileLimits.MAX_PAGE_PIXELS) return false
    return gridTiles(w, h).size <= TileLimits.MAX_TILES
}

/** 给定（可能已缩放的）整页尺寸，按 8192 单边与 64MB 单块预算切网格 */
private fun gridTiles(w: Int, h: Int): List<TileRect> {
    val cols = ceilDiv(w, TileLimits.MAX_EDGE)
    val colW = ceilDiv(w, cols)
    val rowHByPixels = (TileLimits.MAX_TILE_PIXELS / colW.toLong()).toInt().coerceAtLeast(1)
    val rowH = min(TileLimits.MAX_EDGE, rowHByPixels)
    val rows = ceilDiv(h, rowH)
    val out = ArrayList<TileRect>(cols * rows)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val left = c * colW
            val top = r * rowH
            out += TileRect(
                left = left,
                top = top,
                width = min(colW, w - left).coerceAtLeast(1),
                height = min(rowH, h - top).coerceAtLeast(1)
            )
        }
    }
    return out
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
