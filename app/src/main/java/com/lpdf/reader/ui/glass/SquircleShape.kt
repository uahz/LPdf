package com.lpdf.reader.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 连续曲率圆角（squircle / Apple 风格）。
 *
 * 用超椭圆指数 n≈5 的隐式曲线近似 G2 连续曲率：
 * `|x/a|^n + |y/b|^n = 1`
 *
 * 视觉规范要求「28dp 超大超椭圆、连续曲率」，普通 `RoundedCornerShape` 是四分之一圆弧，
 * 会造成圆角与直边交界处出现可见切点。
 *
 * ## 为什么必须继承 CornerBasedShape
 *
 * 液态玻璃库的 `lens()` 折射效果内部做的是：
 * ```
 * if (shape is CornerBasedShape) 取四角半径喂给 AGSL
 * else throw UnsupportedOperationException("Only CornerBasedShape is supported")
 * ```
 * 若只实现 [Shape] 并返回 `Outline.Generic`，**每个玻璃组件在首帧就会崩**，
 * 表现为「点开应用立刻消失」。本项目踩过这个坑，此处用类型继承从根上锁死。
 */
class SquircleShape(
    corner: CornerSize,
    private val exponent: Float = 5.0f
) : CornerBasedShape(
    topStart = corner,
    topEnd = corner,
    bottomEnd = corner,
    bottomStart = corner
) {

    constructor(cornerRadiusDp: Float, exponent: Float = 5.0f) :
        this(CornerSize(cornerRadiusDp.dp), exponent)

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): CornerBasedShape = SquircleShape(corner = topStart, exponent = exponent)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val limit = min(size.width, size.height) / 2f
        // 四角取平均：超椭圆本身要求四角对称，差异极大的场景可以忽略
        val r = ((topStart + topEnd + bottomEnd + bottomStart) / 4f).coerceIn(0f, limit)

        if (r <= 0.5f) {
            val rect = Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            }
            return Outline.Generic(rect)
        }

        val path = Path()
        val n = exponent
        val steps = (r / 1.6f).toInt().coerceIn(8, 48)

        path.moveTo(0f, r)
        appendCorner(path, r, r, r, n, 180f, 270f, steps)                          // 左上
        appendCorner(path, size.width - r, r, r, n, 270f, 360f, steps)             // 右上
        appendCorner(path, size.width - r, size.height - r, r, n, 0f, 90f, steps)  // 右下
        appendCorner(path, r, size.height - r, r, n, 90f, 180f, steps)             // 左下
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
        steps: Int
    ) {
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val rad = Math.toRadians((startDeg + (endDeg - startDeg) * t).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val scale = superellipseScale(c, s, n)
            path.lineTo(cx + c * r * scale, cy + s * r * scale)
        }
    }

    /** 给定方向 (cos,sin)，返回超椭圆在该方向的半径（相对圆弧半径 1） */
    private fun superellipseScale(c: Float, s: Float, n: Float): Float {
        val a = abs(c)
        val b = abs(s)
        val v = a.toDouble().pow(n.toDouble()) + b.toDouble().pow(n.toDouble())
        if (v <= 1e-6) return 1f
        val k = v.pow(-1.0 / n.toDouble()).toFloat()
        // 轻微外扩补偿超椭圆相对圆弧的内缩，保持视觉尺寸一致
        return (k * 1.02f).coerceIn(0.9f, 1.12f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return topStart == other.topStart && exponent == other.exponent
    }

    override fun hashCode(): Int = 31 * topStart.hashCode() + exponent.hashCode()

    override fun toString(): String = "SquircleShape(corner=$topStart, exponent=$exponent)"
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
 *
 * 返回类型是 [Shape]，但实际类型为 [CornerBasedShape]，
 * 保证 `lens()` 折射效果可用。
 */
fun pillShape(heightDp: Float): Shape = SquircleShape(heightDp / 2f, exponent = 4.0f)

/** 普通圆角（内容层用，天然是 CornerBasedShape） */
fun roundedShape(radiusDp: Float): Shape = RoundedCornerShape(radiusDp.dp)
