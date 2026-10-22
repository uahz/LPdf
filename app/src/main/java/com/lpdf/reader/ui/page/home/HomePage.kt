package com.lpdf.reader.ui.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lpdf.reader.data.local.ReadingHit
import com.lpdf.reader.ui.glass.GlassContentCard
import com.lpdf.reader.ui.glass.GlassListRow
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.core.util.Formatters

/**
 * 首页（v1.6.0）。
 *
 * 和「文档」页的分工：
 * - **首页**回答「我接着看什么」——最近在读、最近导入
 * - **文档**回答「我的文件在哪」——完整列表、搜索、排序、筛选
 *
 * 所以首页不放搜索框、不放排序：那些都是「找文件」的动作，
 * 属于文档页。首页只做减法。
 */
@Composable
fun HomePage(
    recent: List<ReadingHit>,
    recentImports: List<com.lpdf.reader.core.model.PdfItem>,
    totalDocs: Int,
    totalPages: Int,
    /**
     * 收整个 [ReadingHit] / PdfItem 而不是拆成 (uri, name, modified, page)：
     * 拆开后阅读页要的四个字段在调用点就得全列一遍，改一次签名要改四处；
     * 传对象则「谁需要什么自己取」，也不会漏掉 modified（漏了就丢标注）。
     */
    onContinue: (ReadingHit) -> Unit,
    onOpen: (com.lpdf.reader.core.model.PdfItem) -> Unit,
    onImport: () -> Unit,
    onSeeAll: () -> Unit
) {
    val colors = LocalColors.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topInset + 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // -------- 标题 --------
        item {
            Column(Modifier.padding(horizontal = LSpace.pageHorizontal)) {
                Text("LPdf", style = LType.TitleL, color = colors.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (totalDocs == 0) "本地优先，不上传任何文件"
                    else "$totalDocs 个文档 · $totalPages 页",
                    style = LType.Caption,
                    color = colors.secondary
                )
            }
        }

        // -------- 继续阅读 --------
        if (recent.isNotEmpty()) {
            item {
                SectionHeader("继续阅读", action = "全部" to onSeeAll)
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = LSpace.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recent, key = { it.doc.id }) { hit ->
                        ContinueCard(hit) { onContinue(hit) }
                    }
                }
            }
        }

        // -------- 空态 --------
        if (recent.isEmpty() && recentImports.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = LSpace.pageHorizontal)) {
                    GlassContentCard(shape = Shapes2.XL) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("还没有文档", style = LType.BodyStrong, color = colors.text)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "导入一个 PDF 就能开始阅读。\n文件始终留在这台设备上。",
                                style = LType.Caption,
                                color = colors.secondary
                            )
                            Spacer(Modifier.height(16.dp))
                            GlassPillButton(
                                text = "导入 PDF",
                                onClick = onImport,
                                leading = LIcon.FilePlus,
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }
        }

        // -------- 最近导入 --------
        if (recentImports.isNotEmpty()) {
            item { SectionHeader("最近导入") }
            item {
                Column(
                    Modifier.padding(horizontal = LSpace.pageHorizontal),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    recentImports.forEach { item ->
                        GlassListRow(
                            title = item.displayName,
                            subtitle = buildString {
                                append(Formatters.fileSize(item.sizeBytes))
                                item.pageCount?.let { append(" · $it 页") }
                            },
                            thumbPath = null,
                            onClick = { onOpen(item) },
                            onLongClick = { onOpen(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: Pair<String, () -> Unit>? = null) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LSpace.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = LType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action.first,
                style = LType.Caption,
                color = colors.accent,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { action.second() }
            )
        }
    }
}

/**
 * 继续阅读卡片。
 *
 * 这里是**唯一**不做玻璃的地方 —— 一张横滑卡片如果每张都折射，
 * 滑动时会有一排玻璃互相采样，既贵又闪。用纯色 + 进度条反而更稳。
 */
@Composable
private fun ContinueCard(hit: ReadingHit, onClick: () -> Unit) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ratio = if (hit.totalPages > 0) {
        (hit.page + 1).toFloat() / hit.totalPages
    } else 0f

    Column(
        Modifier
            .width(168.dp)
            .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
            .clip(RoundedCornerShape(16.dp))
            .background(colors.glassFrost)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(14.dp)
    ) {
        // 纸张占位：没有缩略图时用一块浅色矩形暗示「这里是一页纸」
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.text.copy(alpha = 0.06f))
        )
        Spacer(Modifier.height(10.dp))
        Text(
            hit.doc.displayName,
            style = LType.BodyStrong,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // 两行固定高度，避免长短文件名让整排卡片高低不齐
            modifier = Modifier.height(44.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hit.totalPages > 0) "第 ${hit.page + 1} / ${hit.totalPages} 页" else "第 ${hit.page + 1} 页",
            style = LType.Micro,
            color = colors.secondary
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.text.copy(alpha = 0.10f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .background(colors.accent)
            )
        }
    }
}
