package com.lpdf.reader.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 连续曲率圆角（squircle / Apple 风格）。
 *
 * 用超椭圆指数 n≈5 的隐式曲线近似 G2 连续曲率：
 * |x/a|^n + |y/b|^n = 1
 *
 * 视觉规范要求"28dp 超大超椭圆、连续曲率"，普通 `RoundedCornerShape` 是四分之一圆弧，
 * 会导致圆角与直边交界处出现明显切点。此处按像素步进采样生成 Path。
 */
class SquircleShape(
    private val cornerRadiusDp: Float,
    private val exponent: Float = 5.0f
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadiusDp.dp.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val path = Path()
        if (r <= 0.5f) {
            path.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            return Outline.Generic(path)
        }
        val n = exponent
        // 每个角的采样点数量，圆角越大采样越多
        val steps = (r / 1.6f).toInt().coerceIn(8, 48)

        // 从左上角顺时针构造四条直边 + 四个超椭圆角
        // 1. 左上角：中心 (r, r)，角度 180°→270°
        path.moveTo(0f, r)
        appendCorner(path, cx = r, cy = r, r = r, n = n, startDeg = 180f, endDeg = 270f, steps = steps, first = false)
        // 2. 右上角：中心 (w - r, r)，270°→360°
        appendCorner(path, cx = size.width - r, cy = r, r = r, n = n, startDeg = 270f, endDeg = 360f, steps = steps, first = false)
        // 3. 右下角：中心 (w - r, h - r)，0°→90°
        appendCorner(path, cx = size.width - r, cy = size.height - r, r = r, n = n, startDeg = 0f, endDeg = 90f, steps = steps, first = false)
        // 4. 左下角：中心 (r, h - r)，90°→180°
        appendCorner(path, cx = r, cy = size.height - r, r = r, n = n, startDeg = 90f, endDeg = 180f, steps = steps, first = false)
        path.close()

        return Outline.Generic(path)
    }

    private fun appendCorner(
        path: Path,
        cx: Float,
        cy: Float,
        r: Float,
        n: Float,
        startDeg: Float,
        endDeg: Float,
        steps: Int,
        first: Boolean
    ) {
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val deg = startDeg + (endDeg - startDeg) * t
            val rad = Math.toRadians(deg.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            // 超椭圆半径缩放：使该方向上的模为 1
            val scale = superellipseScale(cos, sin, n)
            val x = cx + cos * r * scale
            val y = cy + sin * r * scale
            if (first && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    /** 给定方向 (cos,sin)，返回超椭圆 |x|^n+|y|^n=1 在该方向的半径（相对于圆弧半径 1） */
    private fun superellipseScale(cos: Float, sin: Float, n: Float): Float {
        val a = abs(cos)
        val b = abs(sin)
        val v = Math.pow(a.toDouble(), n.toDouble()) + Math.pow(b.toDouble(), n.toDouble())
        if (v <= 1e-6) return 1f
        // 归一化：让 45° 方向的点与圆弧内切，整体更接近 iOS 观感
        val k = Math.pow(v.toDouble(), -1.0 / n.toDouble()).toFloat()
        // 轻微外扩补偿超椭圆相对圆弧的内缩，保持视觉尺寸一致
        return (k * 1.02f).coerceIn(0.9f, 1.12f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return cornerRadiusDp == other.cornerRadiusDp && exponent == other.exponent
    }

    override fun hashCode(): Int = 31 * cornerRadiusDp.hashCode() + exponent.hashCode()
}

/** 常用尺寸的 squircle 糖 */
object Shapes2 {
    val XL = SquircleShape(28f)
    val L = SquircleShape(20f)
    val M = SquircleShape(14f)
    val S = SquircleShape(12f)
    fun pill(height: Float) = SquircleShape(height / 2f, exponent = 4f)
}

/**
 * 胶囊形状（Pill）：高度一半为半径，超椭圆指数略低以贴合胶囊观感。
 */
fun pillShape(heightDp: Float): Shape = SquircleShape(heightDp / 2f, exponent = 4.0f)

/** 普通圆角（用于内容层，视觉上无差别但更省） */
fun roundedShape(radiusDp: Float): Shape = RoundedCornerShape(radiusDp.dp)
