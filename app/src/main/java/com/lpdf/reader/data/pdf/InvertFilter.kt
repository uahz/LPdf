package com.lpdf.reader.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 夜间模式的页面反色（v1.7.0）。
 *
 * ## 为什么不用一层滤色片
 *
 * 最常见的偷懒做法是在页面上面盖一层半透明黑（`alpha = 0.4` 的黑色矩形）。
 * 结果是白纸变灰纸、黑字仍+黑字，**对比度被一起压掉**，小字号直接糊。
 * 用户要的夜间模式是「像 dark mode 一样：纸变深、字变亮」。
 *
 * ## 真正的反色
 *
 * 逐像素 `255 - v`。白纸(255) → 黑纸(0)，黑字(0) → 白字(255)，
 * 对比度完整保留。但直接反转有两个副作用：
 *
 * 1. 反色后所有颜色都跑到互补色上，原本淡黄的高亮底色会变成诡异的蓝紫
 * 2. 灰色中间调反转后仍然是灰，看起来像蒙了一层脏东西
 *
 * 所以这里在反色之后补一点亮度和对比，让反色后的正文更接近纯白，
 * 而不是一片灰白。
 * 标注是独立的矢量层，不经过这里，所以高亮/墨迹的颜色不受影响 ——
 * 这是一个刻意的取舍：夜间下标注保持原色，用户仍然能一眼看出自己标了什么。
 */
internal object InvertFilter {

    /**
     * 反色矩阵。
     *
     * `R' = 255 - R` 展开成 4×5 矩阵就是 `[-1, 0, 0, 0, 255]` 一行。
     * 在此基础上叠一个对比度系数 c 与饱和度 s：
     * 对比度围绕 128 展开，饱和度用标准 luminance 加权。
     */
    private val matrix: ColorMatrix by lazy {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // 轻微的亮度提升，让反色后的文字更白、区分度更好
        val lift = ColorMatrix(
            floatArrayOf(
                1.04f, 0f, 0f, 0f, 6f,
                0f, 1.04f, 0f, 0f, 6f,
                0f, 0f, 1.04f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        invert.postConcat(lift)
        invert
    }

    private val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(matrix)
        isAntiAlias = false
        isFilterBitmap = false
    }

    /**
     * 就地反色 [src]。ARGB_8888 的位图直接进 GPU 受影响的通道；
     * 其他配置（少见）先转一张再画回去，失败就保持原样 —— 反色是锦上添花，
     * 绝不能因为它让整页渲染失败。
     */
    fun apply(src: Bitmap) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return
        runCatching {
            val target = if (src.config == Bitmap.Config.ARGB_8888) {
                src
            } else {
                // 画回原对象需要可写；硬件位图或非 ARGB 配置都走这条
                null
            }
            if (target != null) {
                Canvas(target).drawBitmap(src, 0f, 0f, paint)
            } else {
                val tmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(tmp).drawBitmap(src, 0f, 0f, paint)
                Canvas(src).drawBitmap(tmp, 0f, 0f, null)
                tmp.recycle()
            }
        }
    }
}
