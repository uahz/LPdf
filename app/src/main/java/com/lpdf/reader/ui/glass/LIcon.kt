package com.lpdf.reader.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标（SF Symbols 风格，1.8dp 线宽）。
 * 不使用 Material 填充图标，以保证视觉一致。
 */
enum class LIcon {
    ChevronUp, ChevronDown, ChevronLeft, ChevronRight,
    Xmark, Folder, Gear, List, Pencil, Ellipsis,
    Link, Search, Lock, Shield, Broken, Download, Eye, Sliders,
    HighlightPen, ShapeRect, ShapeOval, TextT, Note, ShapeArrow, Eraser,
    Undo, Redo, Trash, Check, Plus, Rotate, ArrowsH, Copy, Scissors,
    Image, Merge, Split, Compress, Signature, Watermark, FilePlus, PageGrid,
    Cloud, Sun, Moon, Info, ChevronUpDown
}

@Composable
fun LIconView(
    icon: LIcon,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color.Black,
    strokeWidth: Dp = 1.8.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = this.size.minDimension
        val sw = strokeWidth.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val pad = sw * 0.9f
        fun p(x: Float, y: Float) = Offset(pad + x * (s - 2 * pad), pad + y * (s - 2 * pad))

        when (icon) {
            LIcon.ChevronUp -> {
                val path = Path().apply {
                    moveTo(p(0.20f, 0.66f).x, p(0.20f, 0.66f).y)
                    lineTo(p(0.50f, 0.34f).x, p(0.50f, 0.34f).y)
                    lineTo(p(0.80f, 0.66f).x, p(0.80f, 0.66f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.ChevronDown -> {
                val path = Path().apply {
                    moveTo(p(0.20f, 0.34f).x, p(0.20f, 0.34f).y)
                    lineTo(p(0.50f, 0.66f).x, p(0.50f, 0.66f).y)
                    lineTo(p(0.80f, 0.34f).x, p(0.80f, 0.34f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.ChevronLeft -> {
                val path = Path().apply {
                    moveTo(p(0.62f, 0.18f).x, p(0.62f, 0.18f).y)
                    lineTo(p(0.34f, 0.50f).x, p(0.34f, 0.50f).y)
                    lineTo(p(0.62f, 0.82f).x, p(0.62f, 0.82f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.ChevronRight -> {
                val path = Path().apply {
                    moveTo(p(0.38f, 0.18f).x, p(0.38f, 0.18f).y)
                    lineTo(p(0.66f, 0.50f).x, p(0.66f, 0.50f).y)
                    lineTo(p(0.38f, 0.82f).x, p(0.38f, 0.82f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Xmark -> {
                drawLine(color, p(0.25f, 0.25f), p(0.75f, 0.75f), sw, StrokeCap.Round)
                drawLine(color, p(0.75f, 0.25f), p(0.25f, 0.75f), sw, StrokeCap.Round)
            }

            LIcon.Folder -> {
                val path = Path().apply {
                    moveTo(p(0.10f, 0.30f).x, p(0.10f, 0.30f).y)
                    lineTo(p(0.40f, 0.30f).x, p(0.40f, 0.30f).y)
                    lineTo(p(0.48f, 0.40f).x, p(0.48f, 0.40f).y)
                    lineTo(p(0.90f, 0.40f).x, p(0.90f, 0.40f).y)
                    lineTo(p(0.90f, 0.80f).x, p(0.90f, 0.80f).y)
                    lineTo(p(0.10f, 0.80f).x, p(0.10f, 0.80f).y)
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Gear -> {
                drawCircle(color, radius = s * 0.14f, center = center, style = stroke)
                // 8 根齿
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val inner = s * 0.26f
                    val outer = s * 0.34f
                    val c = center
                    drawLine(
                        color,
                        Offset(c.x + (kotlin.math.cos(a) * inner).toFloat(), c.y + (kotlin.math.sin(a) * inner).toFloat()),
                        Offset(c.x + (kotlin.math.cos(a) * outer).toFloat(), c.y + (kotlin.math.sin(a) * outer).toFloat()),
                        sw, StrokeCap.Round
                    )
                }
            }

            LIcon.List -> {
                for (i in 0..2) {
                    val y = 0.28f + i * 0.22f
                    drawLine(color, p(0.14f, y), p(0.30f, y), sw, StrokeCap.Round)
                    drawLine(color, p(0.38f, y), p(0.86f, y), sw, StrokeCap.Round)
                }
            }

            LIcon.Pencil -> {
                val path = Path().apply {
                    moveTo(p(0.18f, 0.82f).x, p(0.18f, 0.82f).y)
                    lineTo(p(0.26f, 0.60f).x, p(0.26f, 0.60f).y)
                    lineTo(p(0.66f, 0.20f).x, p(0.66f, 0.20f).y)
                    lineTo(p(0.82f, 0.36f).x, p(0.82f, 0.36f).y)
                    lineTo(p(0.42f, 0.76f).x, p(0.42f, 0.76f).y)
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Ellipsis -> {
                for (i in 0..2) {
                    drawCircle(color, radius = sw * 0.75f, center = p(0.25f + i * 0.25f, 0.5f))
                }
            }

            LIcon.Link -> {
                drawArc(
                    color, startAngle = 40f, sweepAngle = 200f, useCenter = false,
                    topLeft = Offset(s * 0.08f, s * 0.30f), size = Size(s * 0.44f, s * 0.40f),
                    style = stroke
                )
                drawArc(
                    color, startAngle = 220f, sweepAngle = 200f, useCenter = false,
                    topLeft = Offset(s * 0.48f, s * 0.30f), size = Size(s * 0.44f, s * 0.40f),
                    style = stroke
                )
                drawLine(color, p(0.36f, 0.50f), p(0.64f, 0.50f), sw, StrokeCap.Round)
            }

            LIcon.Search -> {
                drawCircle(color, radius = s * 0.26f, center = Offset(s * 0.44f, s * 0.44f), style = stroke)
                drawLine(color, p(0.64f, 0.64f), p(0.86f, 0.86f), sw, StrokeCap.Round)
            }

            LIcon.Lock -> {
                drawRoundRect(
                    color, topLeft = p(0.22f, 0.46f), size = Size(s * 0.56f, s * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = stroke
                )
                drawArc(
                    color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(s * 0.32f, s * 0.16f), size = Size(s * 0.36f, s * 0.44f), style = stroke
                )
            }

            LIcon.Shield -> {
                val path = Path().apply {
                    moveTo(p(0.5f, 0.12f).x, p(0.5f, 0.12f).y)
                    lineTo(p(0.84f, 0.26f).x, p(0.84f, 0.26f).y)
                    lineTo(p(0.78f, 0.62f).x, p(0.78f, 0.62f).y)
                    lineTo(p(0.5f, 0.88f).x, p(0.5f, 0.88f).y)
                    lineTo(p(0.22f, 0.62f).x, p(0.22f, 0.62f).y)
                    lineTo(p(0.16f, 0.26f).x, p(0.16f, 0.26f).y)
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Broken -> {
                drawRect(
                    color, topLeft = p(0.16f, 0.16f), size = Size(s * 0.68f, s * 0.68f),
                    style = stroke
                )
                val z = Path().apply {
                    moveTo(p(0.52f, 0.10f).x, p(0.52f, 0.10f).y)
                    lineTo(p(0.42f, 0.40f).x, p(0.42f, 0.40f).y)
                    lineTo(p(0.60f, 0.42f).x, p(0.60f, 0.42f).y)
                    lineTo(p(0.44f, 0.76f).x, p(0.44f, 0.76f).y)
                    lineTo(p(0.60f, 0.90f).x, p(0.60f, 0.90f).y)
                }
                drawPath(z, color, style = Stroke(width = sw * 1.1f, cap = StrokeCap.Round))
            }

            LIcon.Download -> {
                drawLine(color, p(0.5f, 0.14f), p(0.5f, 0.66f), sw, StrokeCap.Round)
                val a = Path().apply {
                    moveTo(p(0.30f, 0.48f).x, p(0.30f, 0.48f).y)
                    lineTo(p(0.50f, 0.68f).x, p(0.50f, 0.68f).y)
                    lineTo(p(0.70f, 0.48f).x, p(0.70f, 0.48f).y)
                }
                drawPath(a, color, style = stroke)
                drawLine(color, p(0.18f, 0.84f), p(0.82f, 0.84f), sw, StrokeCap.Round)
            }

            LIcon.Eye -> {
                val path = Path().apply {
                    moveTo(p(0.08f, 0.50f).x, p(0.08f, 0.50f).y)
                    quadraticTo(p(0.50f, 0.08f).x, p(0.50f, 0.08f).y, p(0.92f, 0.50f).x, p(0.92f, 0.50f).y)
                    quadraticTo(p(0.50f, 0.92f).x, p(0.50f, 0.92f).y, p(0.08f, 0.50f).x, p(0.08f, 0.50f).y)
                }
                drawPath(path, color, style = stroke)
                drawCircle(color, radius = s * 0.12f, center = center, style = stroke)
            }

            LIcon.Sliders -> {
                for (i in 0..2) {
                    val y = 0.24f + i * 0.26f
                    drawLine(color, p(0.12f, y), p(0.88f, y), sw, StrokeCap.Round)
                    val x = when (i) { 0 -> 0.34f; 1 -> 0.66f; else -> 0.46f }
                    drawCircle(color, radius = sw * 1.6f, center = p(x, y))
                }
            }

            LIcon.HighlightPen -> {
                val path = Path().apply {
                    moveTo(p(0.20f, 0.62f).x, p(0.20f, 0.62f).y)
                    lineTo(p(0.62f, 0.16f).x, p(0.62f, 0.16f).y)
                    lineTo(p(0.86f, 0.38f).x, p(0.86f, 0.38f).y)
                    lineTo(p(0.44f, 0.84f).x, p(0.44f, 0.84f).y)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, p(0.12f, 0.90f), p(0.44f, 0.90f), sw * 1.6f, StrokeCap.Round)
            }

            LIcon.ShapeRect -> {
                drawRect(
                    color, topLeft = p(0.16f, 0.24f), size = Size(s * 0.68f, s * 0.52f),
                    style = stroke
                )
            }

            LIcon.ShapeOval -> {
                drawCircle(color, radius = s * 0.30f, center = center, style = stroke)
            }

            LIcon.ShapeArrow -> {
                drawLine(color, p(0.16f, 0.82f), p(0.76f, 0.22f), sw, StrokeCap.Round)
                val a = Path().apply {
                    moveTo(p(0.46f, 0.18f).x, p(0.46f, 0.18f).y)
                    lineTo(p(0.80f, 0.18f).x, p(0.80f, 0.18f).y)
                    lineTo(p(0.80f, 0.52f).x, p(0.80f, 0.52f).y)
                }
                drawPath(a, color, style = stroke)
            }

            LIcon.TextT -> {
                drawLine(color, p(0.16f, 0.20f), p(0.84f, 0.20f), sw, StrokeCap.Round)
                drawLine(color, p(0.50f, 0.20f), p(0.50f, 0.84f), sw, StrokeCap.Round)
            }

            LIcon.Note -> {
                drawRoundRect(
                    color, topLeft = p(0.18f, 0.18f), size = Size(s * 0.64f, s * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f), style = stroke
                )
                val corner = Path().apply {
                    moveTo(p(0.62f, 0.82f).x, p(0.62f, 0.82f).y)
                    lineTo(p(0.82f, 0.62f).x, p(0.82f, 0.62f).y)
                    lineTo(p(0.62f, 0.62f).x, p(0.62f, 0.62f).y)
                    close()
                }
                drawPath(corner, color, style = stroke)
            }

            LIcon.Eraser -> {
                val path = Path().apply {
                    moveTo(p(0.16f, 0.66f).x, p(0.16f, 0.66f).y)
                    lineTo(p(0.50f, 0.24f).x, p(0.50f, 0.24f).y)
                    lineTo(p(0.86f, 0.52f).x, p(0.86f, 0.52f).y)
                    lineTo(p(0.54f, 0.86f).x, p(0.54f, 0.86f).y)
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Undo, LIcon.Redo -> {
                val flip = icon == LIcon.Redo
                drawArc(
                    color, startAngle = if (flip) 200f else 160f,
                    sweepAngle = if (flip) -270f else 270f, useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.26f), size = Size(s * 0.56f, s * 0.52f),
                    style = stroke
                )
                val x = if (flip) 0.72f else 0.28f
                val arrow = Path().apply {
                    moveTo(p(x - 0.10f, 0.18f).x, p(x - 0.10f, 0.18f).y)
                    lineTo(p(x, 0.28f).x, p(x, 0.28f).y)
                    lineTo(p(x - 0.10f, 0.38f).x, p(x - 0.10f, 0.38f).y)
                }
                drawPath(arrow, color, style = stroke)
            }

            LIcon.Trash -> {
                drawLine(color, p(0.16f, 0.26f), p(0.84f, 0.26f), sw, StrokeCap.Round)
                drawLine(color, p(0.40f, 0.26f), p(0.40f, 0.18f), sw, StrokeCap.Round)
                drawLine(color, p(0.60f, 0.26f), p(0.60f, 0.18f), sw, StrokeCap.Round)
                drawLine(color, p(0.40f, 0.18f), p(0.60f, 0.18f), sw, StrokeCap.Round)
                val body = Path().apply {
                    moveTo(p(0.24f, 0.30f).x, p(0.24f, 0.30f).y)
                    lineTo(p(0.30f, 0.86f).x, p(0.30f, 0.86f).y)
                    lineTo(p(0.70f, 0.86f).x, p(0.70f, 0.86f).y)
                    lineTo(p(0.76f, 0.30f).x, p(0.76f, 0.30f).y)
                }
                drawPath(body, color, style = stroke)
            }

            LIcon.Check -> {
                val path = Path().apply {
                    moveTo(p(0.20f, 0.54f).x, p(0.20f, 0.54f).y)
                    lineTo(p(0.42f, 0.76f).x, p(0.42f, 0.76f).y)
                    lineTo(p(0.82f, 0.28f).x, p(0.82f, 0.28f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Plus -> {
                drawLine(color, p(0.5f, 0.18f), p(0.5f, 0.82f), sw, StrokeCap.Round)
                drawLine(color, p(0.18f, 0.5f), p(0.82f, 0.5f), sw, StrokeCap.Round)
            }

            LIcon.Rotate -> {
                drawArc(
                    color, startAngle = -30f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(s * 0.14f, s * 0.14f), size = Size(s * 0.72f, s * 0.72f),
                    style = stroke
                )
                val a = Path().apply {
                    moveTo(p(0.66f, 0.10f).x, p(0.66f, 0.10f).y)
                    lineTo(p(0.86f, 0.30f).x, p(0.86f, 0.30f).y)
                    lineTo(p(0.64f, 0.34f).x, p(0.64f, 0.34f).y)
                }
                drawPath(a, color, style = stroke)
            }

            LIcon.ArrowsH -> {
                drawLine(color, p(0.14f, 0.42f), p(0.86f, 0.42f), sw, StrokeCap.Round)
                val l = Path().apply {
                    moveTo(p(0.26f, 0.28f).x, p(0.26f, 0.28f).y)
                    lineTo(p(0.12f, 0.42f).x, p(0.12f, 0.42f).y)
                    lineTo(p(0.26f, 0.56f).x, p(0.26f, 0.56f).y)
                }
                val r = Path().apply {
                    moveTo(p(0.74f, 0.28f).x, p(0.74f, 0.28f).y)
                    lineTo(p(0.88f, 0.42f).x, p(0.88f, 0.42f).y)
                    lineTo(p(0.74f, 0.56f).x, p(0.74f, 0.56f).y)
                }
                drawPath(l, color, style = stroke)
                drawPath(r, color, style = stroke)
            }

            LIcon.Copy -> {
                drawRoundRect(
                    color, topLeft = p(0.14f, 0.14f), size = Size(s * 0.46f, s * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = stroke
                )
                drawRoundRect(
                    color, topLeft = p(0.40f, 0.40f), size = Size(s * 0.46f, s * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = stroke
                )
            }

            LIcon.Scissors -> {
                drawLine(color, p(0.22f, 0.18f), p(0.80f, 0.72f), sw, StrokeCap.Round)
                drawLine(color, p(0.80f, 0.18f), p(0.22f, 0.72f), sw, StrokeCap.Round)
                drawCircle(color, radius = s * 0.10f, center = p(0.18f, 0.82f), style = stroke)
                drawCircle(color, radius = s * 0.10f, center = p(0.84f, 0.82f), style = stroke)
            }

            LIcon.Image -> {
                drawRoundRect(
                    color, topLeft = p(0.14f, 0.20f), size = Size(s * 0.72f, s * 0.60f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f), style = stroke
                )
                drawCircle(color, radius = s * 0.08f, center = p(0.34f, 0.40f), style = stroke)
                val m = Path().apply {
                    moveTo(p(0.20f, 0.72f).x, p(0.20f, 0.72f).y)
                    lineTo(p(0.44f, 0.50f).x, p(0.44f, 0.50f).y)
                    lineTo(p(0.60f, 0.66f).x, p(0.60f, 0.66f).y)
                    lineTo(p(0.72f, 0.54f).x, p(0.72f, 0.54f).y)
                }
                drawPath(m, color, style = stroke)
            }

            LIcon.Merge -> {
                drawLine(color, p(0.28f, 0.14f), p(0.28f, 0.50f), sw, StrokeCap.Round)
                drawLine(color, p(0.72f, 0.14f), p(0.72f, 0.50f), sw, StrokeCap.Round)
                val m = Path().apply {
                    moveTo(p(0.28f, 0.50f).x, p(0.28f, 0.50f).y)
                    quadraticTo(p(0.5f, 0.64f).x, p(0.5f, 0.64f).y, p(0.5f, 0.86f).x, p(0.5f, 0.86f).y)
                }
                val m2 = Path().apply {
                    moveTo(p(0.72f, 0.50f).x, p(0.72f, 0.50f).y)
                    quadraticTo(p(0.5f, 0.64f).x, p(0.5f, 0.64f).y, p(0.5f, 0.86f).x, p(0.5f, 0.86f).y)
                }
                drawPath(m, color, style = stroke)
                drawPath(m2, color, style = stroke)
            }

            LIcon.Split -> {
                drawLine(color, p(0.5f, 0.14f), p(0.5f, 0.46f), sw, StrokeCap.Round)
                val m = Path().apply {
                    moveTo(p(0.5f, 0.46f).x, p(0.5f, 0.46f).y)
                    quadraticTo(p(0.28f, 0.60f).x, p(0.28f, 0.60f).y, p(0.28f, 0.86f).x, p(0.28f, 0.86f).y)
                }
                val m2 = Path().apply {
                    moveTo(p(0.5f, 0.46f).x, p(0.5f, 0.46f).y)
                    quadraticTo(p(0.72f, 0.60f).x, p(0.72f, 0.60f).y, p(0.72f, 0.86f).x, p(0.72f, 0.86f).y)
                }
                drawPath(m, color, style = stroke)
                drawPath(m2, color, style = stroke)
            }

            LIcon.Compress -> {
                drawRect(
                    color, topLeft = p(0.18f, 0.18f), size = Size(s * 0.64f, s * 0.64f),
                    style = stroke
                )
                drawLine(color, p(0.36f, 0.50f), p(0.64f, 0.50f), sw, StrokeCap.Round)
                drawLine(color, p(0.50f, 0.34f), p(0.50f, 0.66f), sw, StrokeCap.Round)
            }

            LIcon.Signature -> {
                val path = Path().apply {
                    moveTo(p(0.12f, 0.68f).x, p(0.12f, 0.68f).y)
                    cubicTo(
                        p(0.30f, 0.20f).x, p(0.30f, 0.20f).y,
                        p(0.40f, 0.92f).x, p(0.40f, 0.92f).y,
                        p(0.56f, 0.44f).x, p(0.56f, 0.44f).y
                    )
                    cubicTo(
                        p(0.66f, 0.20f).x, p(0.66f, 0.20f).y,
                        p(0.74f, 0.60f).x, p(0.74f, 0.60f).y,
                        p(0.90f, 0.36f).x, p(0.90f, 0.36f).y
                    )
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Watermark -> {
                drawCircle(color, radius = s * 0.32f, center = center, style = stroke)
                drawLine(color, p(0.30f, 0.66f), p(0.70f, 0.34f), sw, StrokeCap.Round)
            }

            LIcon.FilePlus -> {
                val doc = Path().apply {
                    moveTo(p(0.22f, 0.10f).x, p(0.22f, 0.10f).y)
                    lineTo(p(0.60f, 0.10f).x, p(0.60f, 0.10f).y)
                    lineTo(p(0.80f, 0.30f).x, p(0.80f, 0.30f).y)
                    lineTo(p(0.80f, 0.90f).x, p(0.80f, 0.90f).y)
                    lineTo(p(0.22f, 0.90f).x, p(0.22f, 0.90f).y)
                    close()
                }
                drawPath(doc, color, style = stroke)
                drawLine(color, p(0.44f, 0.56f), p(0.68f, 0.56f), sw * 0.9f, StrokeCap.Round)
                drawLine(color, p(0.56f, 0.44f), p(0.56f, 0.68f), sw * 0.9f, StrokeCap.Round)
            }

            LIcon.PageGrid -> {
                for (r in 0..1) for (c in 0..1) {
                    drawRoundRect(
                        color,
                        topLeft = p(0.14f + c * 0.38f, 0.14f + r * 0.38f),
                        size = Size(s * 0.30f, s * 0.30f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f),
                        style = stroke
                    )
                }
            }

            LIcon.Cloud -> {
                val path = Path().apply {
                    moveTo(p(0.32f, 0.74f).x, p(0.32f, 0.74f).y)
                    cubicTo(
                        p(0.08f, 0.74f).x, p(0.08f, 0.74f).y,
                        p(0.08f, 0.44f).x, p(0.08f, 0.44f).y,
                        p(0.30f, 0.42f).x, p(0.30f, 0.42f).y
                    )
                    cubicTo(
                        p(0.34f, 0.22f).x, p(0.34f, 0.22f).y,
                        p(0.66f, 0.22f).x, p(0.66f, 0.22f).y,
                        p(0.70f, 0.44f).x, p(0.70f, 0.44f).y
                    )
                    cubicTo(
                        p(0.92f, 0.44f).x, p(0.92f, 0.44f).y,
                        p(0.92f, 0.74f).x, p(0.92f, 0.74f).y,
                        p(0.70f, 0.74f).x, p(0.70f, 0.74f).y
                    )
                    close()
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Sun -> {
                drawCircle(color, radius = s * 0.18f, center = center, style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val inner = s * 0.28f
                    val outer = s * 0.38f
                    drawLine(
                        color,
                        Offset(center.x + (kotlin.math.cos(a) * inner).toFloat(), center.y + (kotlin.math.sin(a) * inner).toFloat()),
                        Offset(center.x + (kotlin.math.cos(a) * outer).toFloat(), center.y + (kotlin.math.sin(a) * outer).toFloat()),
                        sw * 0.9f, StrokeCap.Round
                    )
                }
            }

            LIcon.Moon -> {
                val path = Path().apply {
                    moveTo(p(0.72f, 0.20f).x, p(0.72f, 0.20f).y)
                    quadraticTo(p(0.26f, 0.30f).x, p(0.26f, 0.30f).y, p(0.30f, 0.66f).x, p(0.30f, 0.66f).y)
                    quadraticTo(p(0.50f, 0.90f).x, p(0.50f, 0.90f).y, p(0.78f, 0.72f).x, p(0.78f, 0.72f).y)
                    quadraticTo(p(0.40f, 0.58f).x, p(0.40f, 0.58f).y, p(0.72f, 0.20f).x, p(0.72f, 0.20f).y)
                }
                drawPath(path, color, style = stroke)
            }

            LIcon.Info -> {
                drawCircle(color, radius = s * 0.34f, center = center, style = stroke)
                drawCircle(color, radius = sw * 0.8f, center = p(0.5f, 0.32f))
                drawLine(color, p(0.5f, 0.44f), p(0.5f, 0.70f), sw, StrokeCap.Round)
            }

            LIcon.ChevronUpDown -> {
                val up = Path().apply {
                    moveTo(p(0.28f, 0.44f).x, p(0.28f, 0.44f).y)
                    lineTo(p(0.50f, 0.22f).x, p(0.50f, 0.22f).y)
                    lineTo(p(0.72f, 0.44f).x, p(0.72f, 0.44f).y)
                }
                val down = Path().apply {
                    moveTo(p(0.28f, 0.56f).x, p(0.28f, 0.56f).y)
                    lineTo(p(0.50f, 0.78f).x, p(0.50f, 0.78f).y)
                    lineTo(p(0.72f, 0.56f).x, p(0.72f, 0.56f).y)
                }
                drawPath(up, color, style = stroke)
                drawPath(down, color, style = stroke)
            }
        }
    }
}
