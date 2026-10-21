package com.lpdf.reader.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Velocity.Companion.Zero
import androidx.compose.ui.platform.LocalDensity
import com.lpdf.reader.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 可拖拽的玻璃浮卡（v1.4.0）。
 *
 * 复刻 iOS 长按 Haptic Touch 之后的「浮起 + 跟手」手感：
 *
 * 1. **浮起**：弹出瞬间从 0.88 放大到 1，并带一点点负倾斜，欠阻尼弹簧收尾，
 *    收尾会轻微过冲 —— 看起来像被"弹"到空中而不是淡入。
 * 2. **跟手形变**：卡片按水平拖动速度做剪切（skew），向右拖右倾、向左拖左倾。
 *    松手后剪切归零，归零比出现慢一档，像真有惯性。
 * 3. **抛掷动量**：松手时读 VelocityTracker 的真实速度，先按速度滑一小段，
 *    再用 [Motion.dragReturn] 弹回原位。越界时用 [Motion.rubberBand] 加阻尼。
 *
 * 关键：**位移只作用在卡片这一层**，蒙版完全不动 —— 否则整屏跟着晃，
 * 看起来像在摇手机。
 */
@Composable
fun BoxScope.GlassFloatingCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 允许拖出的距离（px），超出后阻尼递增 */
    maxOffset: Float = 600f,
    /** 拖拽位移回调，用于让玻璃高光跟着手走 */
    onDrag: (Offset) -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    // 剪切角（deg）
    val skew = remember { Animatable(0f) }
    // 浮起进度：0 = 未出现，1 = 完全展开
    val lift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        lift.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    Box(
        modifier
            .align(Alignment.Center)
            .graphicsLayer {
                translationX = offsetX.value
                translationY = offsetY.value
                val l = lift.value
                // 浮起：0.88 → 1，同时倾斜从 -1.5° 回到 0
                scaleX = 0.88f + 0.12f * l
                scaleY = 0.88f + 0.12f * l
                rotationZ = (1f - l) * -1.5f + skew.value
                transformOrigin = TransformOrigin(0.5f, 0.35f)
                alpha = if (l <= 0.01f) 1f else l.coerceIn(0f, 1f)
            }
            .pointerInput(maxOffset) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    flingJob?.cancel()
                    tracker.resetTracking()
                    tracker.addPointerInputChange(down)

                    val ok = drag(down.id) { change ->
                        change.consume()
                        tracker.addPointerInputChange(change)
                        val d = change.positionChange()
                        val nx = offsetX.value + d.x
                        val ny = offsetY.value + d.y
                        // 越界阻尼：出了安全区越拖越沉，但不硬停
                        val dx = if (abs(nx) > maxOffset) {
                            offsetX.value + Motion.rubberBand(nx - offsetX.value, maxOffset * 0.3f)
                        } else nx
                        val dy = if (abs(ny) > maxOffset) {
                            offsetY.value + Motion.rubberBand(ny - offsetY.value, maxOffset * 0.3f)
                        } else ny
                        scope.launch {
                            offsetX.snapTo(dx)
                            offsetY.snapTo(dy)
                        }
                        onDrag(Offset(dx, dy))
                        // 剪切：按水平位移换算倾角，上限 ±4°
                        val targetSkew = (d.x / density.density * 0.9f).coerceIn(-4f, 4f)
                        scope.launch {
                            skew.animateTo(targetSkew, spring(dampingRatio = 0.75f, stiffness = 1400f))
                        }
                    }

                    dragging = false
                    if (!ok) {
                        // 被取消（如父级拦截）：直接归位
                        flingJob = scope.launch {
                            offsetX.animateTo(0f, Motion.dragReturn)
                            offsetY.animateTo(0f, Motion.dragReturn)
                            skew.animateTo(0f, Motion.deform)
                        }
                        return@awaitEachGesture
                    }

                    // ---- 松手：读真实速度，先滑行再弹回 ----
                    val v: Velocity = runCatching {
                        tracker.calculateVelocity()
                    }.getOrNull() ?: Velocity.Zero
                    flingJob = scope.launch {
                        // 剪切先回正（慢一档，带惯性感）
                        launch { skew.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 140f)) }
                        // 残速 → 附加位移
                        val tx = (offsetX.value + v.x * 0.05f).coerceIn(-maxOffset, maxOffset)
                        val ty = (offsetY.value + v.y * 0.05f).coerceIn(-maxOffset, maxOffset)
                        launch {
                            offsetX.animateTo(tx, spring(dampingRatio = 0.9f, stiffness = 600f))
                            offsetX.animateTo(0f, Motion.dragReturn)
                        }
                        launch {
                            offsetY.animateTo(ty, spring(dampingRatio = 0.9f, stiffness = 600f))
                            offsetY.animateTo(0f, Motion.dragReturn)
                        }
                    }
                }
            },
        content = content
    )
}
