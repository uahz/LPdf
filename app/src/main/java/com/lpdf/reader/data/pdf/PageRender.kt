package com.lpdf.reader.data.pdf

import android.graphics.Bitmap

/**
 * 页内一块已渲染的位图。
 *
 * 坐标系是「整页理想像素尺寸」下的整数矩形（`PageRender.widthPx × heightPx`），
 * 不是屏幕坐标。绘制时由 UI 按 Box 实测尺寸等比换算。
 */
data class Tile(
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap
) {
    val bytes: Long get() = bitmap.allocationByteCount.toLong()
}

/**
 * 一页的渲染结果（v1.5.0）。
 *
 * ## 为什么不是一张 Bitmap
 *
 * Android GPU 的单边纹理上限是 8192。PDF 里有大量异形页面（CAD 施工图、
 * 超长海报、地图导出），按屏幕宽度缩放到 1000+ px 宽后，高度动辄上万。
 * 「整页渲染成一张位图」在物理上就走不通 —— 即使 Bitmap 建得出来，
 * 交给 GPU 时 RecordingCanvas 会抛 `trying to draw too large`。
 *
 * 所以一页 = **一组 tile**，在同一个 Compose Canvas 里多次 drawImage 拼起来。
 *
 * ## 关键不变量
 *
 * [widthPx] / [heightPx] **始终等于 PDF 页面的真实宽高比**，
 * 即使因超预算而降级渲染也是如此。原因是：
 *
 * - 列表里每一页的高度靠这个比例算，比例错了页面就会跳动
 * - 标注用归一化 0~1 坐标存储，只要容器比例对，标注就自动对齐
 *
 * 换句话说：降级只影响清晰度，不影响几何。
 */
data class PageRender(
    val page: Int,
    /** 整页理想宽度（px），由屏幕可用宽决定 */
    val widthPx: Int,
    /** 整页理想高度（px），= widthPx × 页面真实宽高比 */
    val heightPx: Int,
    /** true = 因超出分块预算，整体等比缩小后渲染，清晰度下降 */
    val degraded: Boolean,
    /**
     * true = 底图已反色（夜间模式，v1.7.0）。
     *
     * 缓存按页号索引，不含渲染参数，所以这一位是「这份缓存还能不能用」的判据之一：
     * 用户切换夜间模式后，旧位图必须重新渲染而不是继续显示。
     */
    val inverted: Boolean = false,
    /** 已就绪的块，按 index 升序 */
    val tiles: List<Tile> = emptyList(),
    /** 计划总块数；降级页恒为 1 */
    val tileCount: Int = 0
) {
    val bytes: Long get() = tiles.sumOf { it.bytes }

    /** 所有块都已就绪 */
    val isComplete: Boolean get() = tileCount > 0 && tiles.size >= tileCount

    /** 页面宽高比 h/w，用于 UI 布局 */
    val aspect: Float get() = if (widthPx <= 0) 1.4142f else heightPx.toFloat() / widthPx
}

/**
 * 分块计划：几何信息，不含位图。
 *
 * 与 [PageRender] 分开的原因：plan 是纯计算结果（可测、可缓存），
 * render 是带位图的运行时状态。把两者混在一起会让「算几何」也必须持有位图。
 */
data class TilePlan(
    /** 整页理想宽（px）—— 用于 UI 布局，不代表实际位图宽 */
    val widthPx: Int,
    /** 整页理想高（px） */
    val heightPx: Int,
    /** 实际渲染时的缩放系数（降级时 < 1） */
    val scale: Float,
    val degraded: Boolean,
    val rects: List<TileRect>
) {
    val tileCount: Int get() = rects.size
}

/** 整页坐标系下的一块矩形 */
data class TileRect(val left: Int, val top: Int, val width: Int, val height: Int)
