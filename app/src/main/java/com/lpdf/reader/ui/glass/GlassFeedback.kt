package com.lpdf.reader.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion

/** 玻璃提示条数据 */
data class GlassToast(
    val id: Long = System.nanoTime(),
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/**
 * 顶部玻璃提示条（替代 Toast / Snackbar）。
 * 4s 自动收起，点击可触发动作。
 */
@Composable
fun GlassSnackbarHost(
    toast: GlassToast?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalColors.current
    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically(
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 320f),
            initialOffsetY = { -it }
        ) + fadeIn(),
        exit = slideOutVertically(
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.90f, stiffness = 420f),
            targetOffsetY = { -it }
        ) + fadeOut(),
        modifier = modifier
    ) {
        val t = toast ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            LiquidGlass(
                Modifier.fillMaxWidth(),
                shape = Shapes2.XL,
                showHighlight = false
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t.text,
                        style = LType.Caption,
                        color = colors.text,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    if (t.actionLabel != null && t.onAction != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.actionLabel,
                            style = LType.Caption,
                            color = colors.accent,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                t.onAction.invoke()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 玻璃加载环：56dp，光带绕环旋转 */
@Composable
fun GlassProgressRing(
    modifier: Modifier = Modifier,
    progress: Float? = null,     // null = 不确定进度
    label: String? = null
) {
    val colors = LocalColors.current
    val ring = 56.dp
    val rotation = infiniteRotationValue()
    Box(modifier.size(ring), contentAlignment = Alignment.Center) {
        LiquidGlass(Modifier.matchParentSize(), shape = SquircleShape(28f), showHighlight = false) {}
        androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(8.dp)) {
            val sw = 3.dp.toPx()
            val inset = sw / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            // 底环
            drawArc(
                color = colors.text.copy(alpha = 0.12f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = tl, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw)
            )
            if (progress != null) {
                drawArc(
                    color = colors.text.copy(alpha = 0.75f),
                    startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                    topLeft = tl, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        sw, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            } else {
                // 高光弧段旋转
                rotate(rotation) {
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            colors = listOf(Color.Transparent, colors.text.copy(alpha = 0.85f))
                        ),
                        startAngle = 0f, sweepAngle = 120f, useCenter = false,
                        topLeft = tl, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            sw, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }
        // 环心玻璃球
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
        ) {
            LiquidGlass(Modifier.matchParentSize(), shape = SquircleShape(11f, 4f), showHighlight = true) {}
        }
    }
    if (label != null) {
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun infiniteRotationValue(): Float {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
        label = "angle"
    )
    return angle
}

/** 玻璃加载遮罩：居中环 + 文案 */
@Composable
fun GlassLoadingOverlay(text: String, progress: Float? = null) {
    val colors = LocalColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.28f))
        )
        LiquidGlass(
            Modifier.size(200.dp, 176.dp),
            shape = Shapes2.XL
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GlassProgressRing(progress = progress)
                Spacer(Modifier.height(16.dp))
                Text(text, style = LType.Caption, color = colors.text)
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = LType.Micro,
                        color = colors.secondary
                    )
                }
            }
        }
    }
}

/**
 * 玻璃状态卡片：损坏 / 权限 / 空态 / 密码。
 */
@Composable
fun GlassStateCard(
    icon: LIcon,
    title: String,
    message: String? = null,
    primary: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalColors.current
    LiquidGlass(modifier.width(300.dp), shape = Shapes2.XL) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .drawBehind { drawCircle(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.55f)) },
                contentAlignment = Alignment.Center
            ) {
                LIconView(icon, size = 28.dp, color = tint ?: colors.text, strokeWidth = 1.8.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = LType.TitleM, color = colors.text, textAlign = TextAlign.Center)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = LType.Caption,
                    color = colors.secondary,
                    textAlign = TextAlign.Center
                )
            }
            if (primary != null && onPrimary != null) {
                Spacer(Modifier.height(20.dp))
                GlassPillButton(primary, onPrimary, Modifier.fillMaxWidth(), height = 44.dp)
            }
            if (secondary != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    secondary,
                    style = LType.Caption,
                    color = colors.secondary,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
                    ) { onSecondary() }
                )
            }
        }
    }
}
