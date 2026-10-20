package com.lpdf.reader.ui.page.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpdf.reader.data.local.Bookmark
import com.lpdf.reader.data.pdf.PdfiumEngine
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors

/**
 * v1.3 阅读增强面板：书签 / 全文搜索。
 *
 * 与目录抽屉保持同一套玻璃侧板形态（左侧留 56dp 点击关闭区），
 * 保证三个面板的呼出手感一致。
 */

private const val PANEL_WIDTH = 300

// ---------------------------------------------------------------------------
// 书签面板
// ---------------------------------------------------------------------------

@Composable
fun BookmarkPanel(
    bookmarks: List<Bookmark>,
    current: Int,
    onPick: (Int) -> Unit,
    onAddHere: () -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalColors.current
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(56.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }
        )
        LiquidGlass(
            Modifier
                .fillMaxHeight()
                .width(PANEL_WIDTH.dp)
                .padding(vertical = 40.dp),
            shape = Shapes2.XL
        ) {
            Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "书签",
                        style = LType.TitleM,
                        color = colors.text,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${bookmarks.size}", style = LType.Micro, color = colors.secondary)
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(LIcon.Xmark, "关闭", onClose, size = 32.dp, iconSize = 14.dp, showBg = false)
                }

                // 当前页加书签
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassPillButton(text = "为第 ${current + 1} 页加书签", onClick = onAddHere)
                }

                Spacer(Modifier.height(8.dp))

                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LIconView(LIcon.Bookmark, size = 28.dp, color = colors.secondary.copy(alpha = 0.5f))
                            Spacer(Modifier.height(10.dp))
                            Text("还没有书签", style = LType.Caption, color = colors.secondary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点击上方按钮添加",
                                style = LType.Micro,
                                color = colors.secondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(bookmarks, key = { it.id }) { b ->
                            val active = b.page == current
                            val isRenaming = renaming == b.id

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        if (!isRenaming) onPick(b.page)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(if (active) colors.accent else colors.secondary.copy(alpha = 0.4f))
                                    )
                                    Spacer(Modifier.width(10.dp))

                                    if (isRenaming) {
                                        BasicTextField(
                                            value = renameText,
                                            onValueChange = { renameText = it },
                                            singleLine = true,
                                            textStyle = LType.Body.copy(color = colors.text),
                                            cursorBrush = SolidColor(colors.accent),
                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                                onDone = {
                                                    onRename(b.id, renameText)
                                                    renaming = null
                                                }
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Text(
                                            b.label,
                                            style = LType.Body,
                                            color = if (active) colors.accent else colors.text,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Spacer(Modifier.width(6.dp))
                                    Text("${b.page + 1}", style = LType.Micro, color = colors.secondary)
                                }

                                // 行内操作：重命名 / 删除
                                Row(
                                    Modifier.padding(start = 14.dp, top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        "重命名",
                                        style = LType.Micro,
                                        color = colors.secondary,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            renaming = b.id
                                            renameText = b.label
                                        }
                                    )
                                    Text(
                                        "删除",
                                        style = LType.Micro,
                                        color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { onRemove(b.id) }
                                    )
                                }

                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.separator.copy(alpha = 0.35f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索面板
// ---------------------------------------------------------------------------

@Composable
fun SearchPanel(
    query: String,
    hits: List<PdfiumEngine.SearchHit>,
    searching: Boolean,
    progress: Pair<Int, Int>?,
    activeHit: Int,
    matchCase: Boolean,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onToggleMatchCase: () -> Unit,
    onJump: (Int) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalColors.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 面板展开后自动聚焦，省一次点击
        runCatching { focus.requestFocus() }
    }

    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(56.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }
        )
        LiquidGlass(
            Modifier
                .fillMaxHeight()
                .width(PANEL_WIDTH.dp)
                .padding(vertical = 40.dp),
            shape = Shapes2.XL
        ) {
            Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("搜索", style = LType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
                    GlassIconButton(LIcon.Xmark, "关闭", onClose, size = 32.dp, iconSize = 14.dp, showBg = false)
                }

                // 输入框
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(Shapes2.M)
                        .background(colors.glassBase)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LIconView(LIcon.Search, size = 14.dp, color = colors.secondary)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "输入关键词，回车搜索",
                                style = LType.Caption,
                                color = colors.secondary.copy(alpha = 0.6f)
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = LType.Body.copy(color = colors.text),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { onSubmit(query) }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                        )
                    }
                }

                // 选项行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(Shapes2.S)
                            .background(
                                if (matchCase) colors.accent.copy(alpha = 0.16f)
                                else colors.glassBase
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onToggleMatchCase() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "Aa 区分大小写",
                            style = LType.Micro,
                            color = if (matchCase) colors.accent else colors.secondary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            searching && progress != null -> "${progress.first}/${progress.second}"
                            hits.isNotEmpty() -> "${hits.size} 处"
                            query.isNotBlank() -> "无结果"
                            else -> ""
                        },
                        style = LType.Micro,
                        color = colors.secondary
                    )
                }

                if (searching) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("正在检索…", style = LType.Caption, color = colors.secondary)
                    }
                } else if (hits.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "输入关键词开始搜索" else "没有匹配内容",
                            style = LType.Caption,
                            color = colors.secondary
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(hits) { i, h ->
                            val active = i == activeHit
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (active) colors.accent.copy(alpha = 0.12f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onJump(i) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    "${h.page + 1}",
                                    style = LType.number(11.sp, FontWeight.SemiBold),
                                    color = if (active) colors.accent else colors.secondary,
                                    modifier = Modifier.width(34.dp)
                                )
                                Text(
                                    h.snippet.ifBlank { "（空白内容）" },
                                    style = LType.Caption,
                                    color = colors.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索结果的页面叠加高亮
// ---------------------------------------------------------------------------

/** 该页所有命中的淡色底 */
@Composable
fun SearchHitLayer(rects: List<PdfiumEngine.AnnotRectLike>, strong: Boolean = false) {
    if (rects.isEmpty()) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        rects.forEach { r ->
            drawRect(
                color = if (strong)
                    androidx.compose.ui.graphics.Color(0xFFFF9F0A).copy(alpha = 0.55f)
                else
                    androidx.compose.ui.graphics.Color(0xFFFFD60A).copy(alpha = 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(r.left * w, r.top * h),
                size = androidx.compose.ui.geometry.Size(
                    ((r.right - r.left) * w).coerceAtLeast(1f),
                    ((r.bottom - r.top) * h).coerceAtLeast(1f)
                )
            )
        }
    }
}
