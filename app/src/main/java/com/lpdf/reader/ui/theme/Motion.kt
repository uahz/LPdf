package com.lpdf.reader.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 弹簧动效令牌（视觉规范 §7.1）。所有位移/缩放必须使用这些参数，禁止 tween。
 */
object Motion {

    /** 通用状态切换 */
    val standard = spring<Float>(dampingRatio = 0.80f, stiffness = 400f)

    /** Dock 唤起 */
    val dock = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

    /** 拖拽回弹归位 */
    val dragReturn = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

    /** 浮框弹出 */
    val popup = spring<Float>(dampingRatio = 0.70f, stiffness = 280f)

    /** 浮框收起 */
    val dismiss = spring<Float>(dampingRatio = 0.90f, stiffness = 420f)

    /** 形变恢复 */
    val deform = spring<Float>(dampingRatio = 0.60f, stiffness = 500f)

    /** 高亮色过渡 */
    val color = spring<Float>(dampingRatio = 0.90f, stiffness = 120f)

    /** 工具岛展开 */
    val toolbar = spring<Float>(dampingRatio = 0.74f, stiffness = 340f)

    /** 开关拨杆（带轻微过冲） */
    val toggle = spring<Float>(dampingRatio = 0.68f, stiffness = 380f)

    /** 分段指示块 */
    val segmented = spring<Float>(dampingRatio = 0.78f, stiffness = 400f)

    /** 面板 / 抽屉 */
    val panel = spring<Float>(dampingRatio = 0.80f, stiffness = 300f)

    /** 偏移量版本 */
    val dockOffset = spring<androidx.compose.ui.unit.DpOffset>(
        dampingRatio = 0.72f, stiffness = 320f
    )

    /** 拖拽越界阻尼上限 */
    val overscrollLimit: Dp = 48.dp

    /** 弹性跟随：rubber band（iOS 风格） */
    fun rubberBand(delta: Float, limit: Float = 48f): Float {
        if (limit <= 0f) return delta
        val sign = if (delta < 0) -1f else 1f
        val x = kotlin.math.abs(delta)
        // 渐近函数：越接近上限，阻力越大
        return sign * (1f - (1f / ((x / limit) + 1f))) * limit
    }

    const val SPRING_STIFFNESS_DEFAULT = Spring.StiffnessMediumLow
}
