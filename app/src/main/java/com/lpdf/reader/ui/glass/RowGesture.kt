package com.lpdf.reader.ui.glass

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * 列表条目组合手势：单击 + 长按（300ms 由系统默认 longPressTimeout 控制，
 * detectTapGestures 默认 longPressTimeoutMillis ≈ 500ms，这里显式改为 300ms）。
 */
@Composable
fun rememberRowGestureHandlers(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    return Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = { onLongClick() }
        )
    }
}
