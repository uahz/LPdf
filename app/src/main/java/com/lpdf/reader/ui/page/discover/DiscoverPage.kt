package com.lpdf.reader.ui.page.discover

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.GlassContentCard
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion

/**
 * 「发现」Tab 的一个功能入口。
 *
 * 用数据描述而不是「一个 Composable 一个格子、每格手写一遍」：
 * 加功能只加一条数据，布局、按压反馈、间距全部自动一致。
 */
data class DiscoverEntry(
    val icon: LIcon,
    val title: String,
    val subtitle: String,
    val key: Key
) {
    /** 语义标识：UI 只认这个，具体跳转由调用方决定 */
    enum class Key { IMPORT, MERGE, SPLIT, COMPRESS, WATERMARK, SIGNATURE, SCAN_DIR, UPDATE }
}

data class DiscoverSection(
    val title: String,
    val entries: List<DiscoverEntry>
)

/** 功能分组。分组依据是「用户想做什么」，不是「代码在哪」 */
val discoverSections: List<DiscoverSection> = listOf(
    DiscoverSection(
        title = "整理",
        entries = listOf(
            DiscoverEntry(LIcon.Merge, "PDF 合并", "把多个文件合成一份", DiscoverEntry.Key.MERGE),
            DiscoverEntry(LIcon.Split, "PDF 拆分", "按页范围导出为新文件", DiscoverEntry.Key.SPLIT),
            DiscoverEntry(LIcon.Compress, "PDF 压缩", "减小体积便于分享", DiscoverEntry.Key.COMPRESS)
        )
    ),
    DiscoverSection(
        title = "创作",
        entries = listOf(
            DiscoverEntry(LIcon.Watermark, "添加水印", "文字或图案覆盖全页", DiscoverEntry.Key.WATERMARK),
            DiscoverEntry(LIcon.Signature, "电子签名", "绘制签名后盖到任意页", DiscoverEntry.Key.SIGNATURE)
        )
    ),
    DiscoverSection(
        title = "来源",
        entries = listOf(
            DiscoverEntry(LIcon.FilePlus, "导入文件", "从系统选择器挑 PDF", DiscoverEntry.Key.IMPORT),
            DiscoverEntry(LIcon.Folder, "添加文件夹", "整目录扫描，自动跟踪新增", DiscoverEntry.Key.SCAN_DIR)
        )
    ),
    DiscoverSection(
        title = "关于",
        entries = listOf(
            DiscoverEntry(LIcon.Download, "检查更新", "看看有没有新版本", DiscoverEntry.Key.UPDATE)
        )
    )
)

/**
 * 发现页（v1.6.0）。
 *
 * 这一页没有「流」也没有推荐 —— 本地优先的阅读器不该有内容分发。
 * 它就是一个把散落在各处的工具集中起来的地方：以前这些入口在列表页的
 * 三点菜单里，藏得太深，很多人根本不知道 App 能合并/拆分/加水印。
 */
@Composable
fun DiscoverPage(
    onEntry: (DiscoverEntry.Key) -> Unit
) {
    val colors = LocalColors.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LSpace.pageHorizontal,
            end = LSpace.pageHorizontal,
            top = topInset + 20.dp,
            bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("发现", style = LType.TitleL, color = colors.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "全部功能都在这里，不必翻菜单",
                    style = LType.Caption,
                    color = colors.secondary
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        discoverSections.forEach { section ->
            item(key = "h-${section.title}") {
                Text(
                    section.title,
                    style = LType.Caption,
                    color = colors.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            item(key = "s-${section.title}") {
                // 一组装进一张玻璃卡，组内用分隔线 ——
                // 每格单独一张卡会让列表看起来碎
                GlassContentCard(shape = Shapes2.L) {
                    Column {
                        section.entries.forEachIndexed { i, entry ->
                            DiscoverRow(entry) { onEntry(entry.key) }
                            if (i != section.entries.lastIndex) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 60.dp)
                                        .height(0.5.dp)
                                        .background(colors.separator)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverRow(entry: DiscoverEntry, onClick: () -> Unit) {
    val colors = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (pressed) 0.6f else 1f }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            LIconView(entry.icon, size = 17.dp, color = colors.accent, strokeWidth = 1.9.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = LType.Body, color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text(entry.subtitle, style = LType.Micro, color = colors.secondary)
        }
        LIconView(LIcon.ChevronRight, size = 14.dp, color = colors.secondary, strokeWidth = 1.8.dp)
    }
}
