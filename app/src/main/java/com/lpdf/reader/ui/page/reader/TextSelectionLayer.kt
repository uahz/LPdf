package com.lpdf.reader.ui.page.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.lpdf.reader.data.pdf.TextSelection
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.pillShape
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 文本选择层（v1.7.0）。
 *
 * ## 交互设计
 *
 * 长按 → 以手指所在的词为起点进入选择，然后**不松手直接拖**就能扩选；
 * 松手后选区保持，右上角浮出「复制」操作条；点任意空白处取消。
 *
 * 为什么不做 iOS 那种两个拖拽手柄：PDF 页面的字号普遍很小（10pt 正文缩到
 * 屏幕宽后每个字只有十几像素），手柄直径至少 40dp，两个手柄一放就把要看的字挡住了。
 * 「长按后不松手继续拖」既少一步操作，也不额外遮挡 —— 而且用户在微信里
 * 已经习惯了长按取词这个动作。
 *
 * ## 坐标
 *
 * 全部用归一化 0~1，和标注、搜索高亮一致。所以缩放、夜间模式、分块渲染
 * 都不会让选区错位。
 */
@Composable
fun TextSelectionLayer(
    page: Int,
    aspect: Float,
    accent: Color,
    /** 选区变化回调，用于让外层把「当前选区」拿到手（复制按钮要用） */
    selection: TextSelection?,
    /** 把「按下点 → 当前点」换算成选区。由页面注入，组件本身不依赖 ViewModel */
    resolve: (page: Int, sx: Float, sy: Float, ex: Float, ey: Float) -> TextSelection?,
    /** 长按命中时确保文字层已就绪（懒加载，内部会进 native，所以是可挂起函数） */
    prepare: suspend (page: Int) -> Unit,
    onSelect: (TextSelection?) -> Unit,
    onCopy: () -> Unit,
    onLongPressEmpty: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var end by remember { mutableStateOf(Offset.Zero) }

    // 外部把选区清空时（比如翻页），本地两个点也要复位，
    // 否则下次长按时会从上次的位置连过来，选出一大段莫名其妙的文字
    LaunchedEffect(selection) {
        if (selection == null) dragging = false
    }

    val colors = LocalColors.current
    val scope = rememberCoroutineScope()

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / aspect.coerceAtLeast(0.01f))
                .pointerInput(page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            start = Offset(off.x / w, off.y / h)
                            end = start
                            dragging = true
                            // 文字层在这里才去取（懒加载）。长按之前一页文字都没解析过，
                            // 所以第一次长按会有一次短暂的等待；但换来的是翻页不受影响 ——
                            // 否则每翻一页都要解析一次文字层，快速滑动时会把渲染队列堵住。
                            scope.launch {
                                prepare(page)
                                onSelect(resolve(page, start.x, start.y, end.x, end.y))
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            end = Offset(
                                (change.position.x / w).coerceIn(0f, 1f),
                                (change.position.y / h).coerceIn(0f, 1f)
                            )
                            onSelect(
                                resolve(page, start.x, start.y, end.x, end.y)
                            )
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    )
                }
                .pointerInput(page) {
                    // 单击空白处取消选区。放在第二个 pointerInput 里，
                    // 长按手势不会和它抢事件（长按有自己的 recognizer）
                    detectTapGestures {
                        if (selection != null) onSelect(null) else onLongPressEmpty()
                    }
                }
        ) {
            // 选区高亮：按行合并的长条，比逐字画干净
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f / aspect.coerceAtLeast(0.01f))) {
                val sel = selection ?: return@Canvas
                sel.rects.forEach { r ->
                    drawRect(
                        color = accent.copy(alpha = 0.28f),
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size(
                            (r.right - r.left) * size.width,
                            (r.bottom - r.top) * size.height
                        )
                    )
                }
                // 拖动过程中的两个端点：给手指一个落点反馈，
                // 否则大段文字里看不出到底从哪开始选的
                if (dragging) {
                    val rad = with(density) { 3.dp.toPx() }
                    drawCircle(accent, rad, Offset(start.x * size.width, start.y * size.height))
                }
            }
        }

        if (selection?.isEmpty == false) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlass(Modifier.height(36.dp), shape = pillShape(36f)) {
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(pillShape(36f))
                            .background(Color.Transparent)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CopyChip(onCopy)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${selection.text.length} 字",
                            style = LType.Micro,
                            color = colors.secondary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyChip(onCopy: () -> Unit) {
    val colors = LocalColors.current
    Row(
        Modifier
            .clip(pillShape(28f))
            .background(colors.accent.copy(alpha = 0.16f))
            .pointerInput(Unit) { detectTapGestures { onCopy() } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LIconView(LIcon.Copy, Modifier.size(14.dp), color = colors.accent)
        Spacer(Modifier.width(4.dp))
        Text("复制", style = LType.Micro, color = colors.accent)
    }
}

/** 小圆点（手柄视觉预留，当前用于拖动端点） */
@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
