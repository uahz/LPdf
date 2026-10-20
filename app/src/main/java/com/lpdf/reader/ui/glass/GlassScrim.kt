package com.lpdf.reader.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 柔和蒙版层（L3）：位于内容之上、玻璃之下。
 * 透明度用弹簧驱动，避免线性硬切。
 */
@Composable
fun GlassScrim(
    visible: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.35f
) {
    val colors = LocalColors.current
    val target = if (visible) alpha else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 240),
        label = "scrim"
    )
    if (animated > 0.001f) {
        Box(
            modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = animated * (colors.scrim.alpha / 0.35f))) }
        )
    }
}
