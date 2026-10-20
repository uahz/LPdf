package com.lpdf.reader.ui.page.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lpdf.reader.data.local.SignatureAsset
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 签名板（v1.3）。
 *
 * 两个用途：
 * 1. 新手写 → 存进签名库
 * 2. 从签名库挑一张 → 贴到当前页
 *
 * 画笔用二次贝塞尔平滑（[Path.quadraticBezierTo]），否则手写线条呈明显折线。
 */
@Composable
fun SignaturePad(
    signatures: List<SignatureAsset>,
    onSave: (Bitmap, String) -> Unit,
    onPick: (SignatureAsset) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalColors.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var mode by remember { mutableStateOf(PadMode.LIBRARY) }

    LiquidGlass(Modifier.fillMaxWidth().height(320.dp), shape = Shapes2.XL) {
        Column(Modifier.padding(16.dp)) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("签名", style = LType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(Shapes2.S)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            mode = if (mode == PadMode.LIBRARY) PadMode.DRAW else PadMode.LIBRARY
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (mode == PadMode.LIBRARY) "手写新建" else "从签名库选",
                        style = LType.Micro,
                        color = colors.accent
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    LIconView(LIcon.Xmark, size = 13.dp, color = colors.secondary)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (mode == PadMode.DRAW) {
                // ---------- 手写区 ----------
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(Shapes2.M)
                        .background(colors.paper)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current += it },
                                onDragEnd = {
                                    if (current.size > 1) strokes += current.toList()
                                    current.clear()
                                },
                                onDragCancel = { current.clear() }
                            ) { change, _ ->
                                change.consume()
                                current += change.position
                            }
                        }
                ) {
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "在此手写签名",
                                style = LType.Caption,
                                color = colors.secondary.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                        val stroke = Stroke(
                            width = 3.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                        fun drawPoints(points: List<Offset>) {
                            if (points.size < 2) return
                            val path = Path()
                            path.moveTo(points[0].x, points[0].y)
                            // 二次贝塞尔平滑：用相邻点中点作为终点
                            for (i in 1 until points.size - 1) {
                                val mid = Offset(
                                    (points[i].x + points[i + 1].x) / 2f,
                                    (points[i].y + points[i + 1].y) / 2f
                                )
                                path.quadraticBezierTo(points[i].x, points[i].y, mid.x, mid.y)
                            }
                            val last = points.last()
                            path.lineTo(last.x, last.y)
                            drawPath(path, colors.text, style = stroke)
                        }
                        strokes.forEach { drawPoints(it) }
                        drawPoints(current.toList())
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton(
                        text = "清除",
                        onClick = { strokes.clear(); current.clear() },
                        leading = LIcon.Trash,
                        enabled = strokes.isNotEmpty()
                    )
                    GlassPillButton(
                        text = "保存并贴到本页",
                        onClick = {
                            if (strokes.isEmpty()) return@GlassPillButton
                            val bmp = rasterizeStrokes(strokes.map { it.toList() }, colors.text)
                            if (bmp != null) {
                                onSave(bmp, "签名 ${System.currentTimeMillis() % 100000}")
                                strokes.clear()
                            }
                        },
                        leading = LIcon.Check,
                        enabled = strokes.isNotEmpty()
                    )
                }
            } else {
                // ---------- 签名库 ----------
                if (signatures.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(170.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LIconView(LIcon.Signature, size = 26.dp, color = colors.secondary.copy(alpha = 0.45f))
                            Spacer(Modifier.height(8.dp))
                            Text("还没有保存的签名", style = LType.Caption, color = colors.secondary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点右上角「手写新建」创建",
                                style = LType.Micro,
                                color = colors.secondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyRow(
                        Modifier.fillMaxWidth().height(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(signatures, key = { it.id }) { s ->
                            Column(
                                Modifier.width(190.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .width(190.dp)
                                        .height(110.dp)
                                        .clip(Shapes2.M)
                                        .background(colors.paper)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { onPick(s) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmp = remember(s.path) {
                                        runCatching {
                                            android.graphics.BitmapFactory.decodeFile(s.path)
                                        }.getOrNull()
                                    }
                                    if (bmp != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = s.name,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    } else {
                                        Text("图片缺失", style = LType.Micro, color = colors.secondary)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.name,
                                        style = LType.Micro,
                                        color = colors.secondary,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "删除",
                                        style = LType.Micro,
                                        color = colors.danger,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { onDelete(s.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PadMode { LIBRARY, DRAW }

/**
 * 把手写笔迹光栅化成位图（透明背景）。
 *
 * 与屏幕显示的平滑算法保持一致，否则存下来的签名和看到的形状不一样。
 */
fun rasterizeStrokes(strokes: List<List<Offset>>, color: Color): Bitmap? {
    if (strokes.isEmpty()) return null
    val all = strokes.flatten()
    if (all.isEmpty()) return null

    // 按笔迹外包 + 边距决定画布尺寸，避免留大片空白
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    all.forEach {
        if (it.x < minX) minX = it.x
        if (it.x > maxX) maxX = it.x
        if (it.y < minY) minY = it.y
        if (it.y > maxY) maxY = it.y
    }
    val pad = 24f
    val w = (maxX - minX + pad * 2).toInt().coerceIn(32, 4000)
    val h = (maxY - minY + pad * 2).toInt().coerceIn(32, 4000)

    return runCatching {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        strokes.forEach { pts ->
            if (pts.size < 2) return@forEach
            path.reset()
            path.moveTo(pts[0].x - minX + pad, pts[0].y - minY + pad)
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i].x + pts[i + 1].x) / 2f - minX + pad
                val my = (pts[i].y + pts[i + 1].y) / 2f - minY + pad
                path.quadTo(pts[i].x - minX + pad, pts[i].y - minY + pad, mx, my)
            }
            path.lineTo(pts.last().x - minX + pad, pts.last().y - minY + pad)
            canvas.drawPath(path, paint)
        }
        bmp
    }.getOrNull()
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255)
)
