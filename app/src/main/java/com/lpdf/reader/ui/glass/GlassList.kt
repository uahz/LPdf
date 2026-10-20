package com.lpdf.reader.ui.glass

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors

/**
 * 本地缩略图。只读本地文件，绝不走网络。
 */
@Composable
fun LocalThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp
) {
    val colors = LocalColors.current
    val shape = RoundedCornerShape(corner)
    val bitmap: ImageBitmap? = remember(path) {
        if (path == null) null
        else runCatching {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier
            .clip(shape)
            .background(if (colors.isDark) Color(0xFF2C2C2E) else Color.White.copy(alpha = 0.9f))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            // 占位：纸张灰条
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                repeat(5) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (it == 4) 0.55f else 1f)
                            .height(2.5.dp)
                            .background(colors.text.copy(alpha = 0.10f))
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 列表条目（内容层，非玻璃）。仅按压时出现玻璃高亮。
 */
@Composable
fun GlassListRow(
    title: String,
    subtitle: String,
    thumbPath: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    compact: Boolean = false
) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Shapes2.L

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .longPress(onLongClick)
    ) {
        if (pressed) {
            LiquidGlass(Modifier.matchParentSize(), shape = shape, showHighlight = true) {}
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                LocalThumbnail(
                    thumbPath,
                    Modifier
                        .size(
                            width = if (compact) 36.dp else 44.dp,
                            height = if (compact) 46.dp else 56.dp
                        )
                )
                if (badge != null) {
                    Box(Modifier.align(Alignment.TopEnd)) { badge() }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = LType.Body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = LType.Caption,
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** 长按手势（独立 pointerInput，不与 clickable 抢事件；系统 longPress 阈值 500ms） */
private fun Modifier.longPress(onLongClick: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = { onLongClick() })
    }
)

/**
 * 列表大标题栏：标题 + 右侧玻璃圆按钮组。
 */
@Composable
fun GlassHeader(
    title: String,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = LType.TitleL, color = colors.text, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * 玻璃下拉浮层：锚点下方的选项列表（排序 / 筛选）。
 */
@Composable
fun GlassPopover(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalColors.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.001f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
        )
        LiquidGlass(
            modifier
                .width(200.dp)
                .padding(top = 4.dp),
            shape = Shapes2.XL
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSelect(i) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            style = LType.Body,
                            color = colors.text,
                            modifier = Modifier.weight(1f)
                        )
                        if (i == selectedIndex) {
                            LIconView(LIcon.Check, size = 16.dp, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}
