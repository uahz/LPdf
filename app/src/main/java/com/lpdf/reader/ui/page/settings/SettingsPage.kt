package com.lpdf.reader.ui.page.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lpdf.reader.AppContainer
import com.lpdf.reader.BuildConfig
import com.lpdf.reader.core.util.Formatters
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.data.local.MotionLevel
import com.lpdf.reader.data.local.ReadingMode
import com.lpdf.reader.data.local.SaveMode
import com.lpdf.reader.data.local.ThemeMode
import com.lpdf.reader.ui.glass.GlassDivider
import com.lpdf.reader.ui.glass.GlassLevel
import com.lpdf.reader.ui.glass.GlassLog
import com.lpdf.reader.ui.glass.GlassGroup
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.GlassSegmented
import com.lpdf.reader.ui.glass.GlassSlider
import com.lpdf.reader.ui.glass.GlassSwitch
import com.lpdf.reader.ui.glass.GlassSnackbarHost
import com.lpdf.reader.ui.glass.GlassToast
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.glass.LocalGlassCapability
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import kotlinx.coroutines.launch

/**
 * P3 设置页。6 组：阅读体验 / 外观与玻璃 / 编辑 / 存储 / 隐私与网络 / 通用。
 *
 * 所有分组均为液态玻璃卡片（XL 圆角），行内控件全部自绘，不使用 Material 组件。
 */
@Composable
fun SettingsPage(
    container: AppContainer,
    settings: AppSettings,
    /** 独立打开时显示返回按钮；作为「我的」Tab 内嵌时为 null */
    onBack: (() -> Unit)? = null
) {
    val colors = LocalColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var toast by remember { mutableStateOf<GlassToast?>(null) }
    var cacheText by remember { mutableStateOf("计算中…") }
    var showAbout by remember { mutableStateOf(false) }

    fun say(text: String) {
        val t = GlassToast(text = text)
        toast = t
        scope.launch {
            kotlinx.coroutines.delay(2400)
            if (toast?.id == t.id) toast = null
        }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { container.prefs.update(block) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val bytes = container.thumbnails.totalCacheBytes() +
            container.annotations.totalBytes()
        cacheText = Formatters.fileSize(bytes)
    }

    LiquidGlassScope(
        modifier = Modifier.background(colors.pageBg),

        // 内容层：标题 + 分组列表（被玻璃采样）
        content = {
        Column(Modifier.fillMaxSize()) {
            // v1.6.0：作为「我的」Tab 内嵌时不该出现返回按钮 —— Tab 页没有「上一页」。
            // 但状态栏留白两种形态都要加：Tab 宿主只负责底栏，不替内容处理状态栏
            Spacer(
                Modifier.height(
                    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                        if (onBack == null) 8.dp else 12.dp
                )
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LSpace.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    GlassIconButton(LIcon.ChevronLeft, "返回", onBack, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(if (onBack == null) "我的" else "设置", style = LType.TitleL, color = colors.text)
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                contentPadding = PaddingValues(
                    start = LSpace.pageHorizontal,
                    end = LSpace.pageHorizontal,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // ---------- 阅读体验 ----------
                item {
                    GlassGroup(title = "阅读体验") {
                        SegRow(
                            title = "翻页方式",
                            subtitle = "连续滚动可跨页浏览，单页模式一次一页",
                            options = listOf("连续", "单页"),
                            selected = if (settings.readingMode == ReadingMode.CONTINUOUS) 0 else 1,
                            onSelect = { i ->
                                update {
                                    it.copy(
                                        readingMode = if (i == 0) ReadingMode.CONTINUOUS
                                        else ReadingMode.SINGLE_PAGE
                                    )
                                }
                            }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "记忆阅读位置",
                            subtitle = "下次打开自动回到上次所在页",
                            checked = settings.rememberPosition,
                            onChecked = { v -> update { it.copy(rememberPosition = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "自动隐藏工具栏",
                            subtitle = "轻点页面后 3 秒淡出",
                            checked = settings.autoHideToolbar,
                            onChecked = { v -> update { it.copy(autoHideToolbar = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "阅读时常亮",
                            subtitle = "阅读期间不锁屏",
                            checked = settings.keepScreenOn,
                            onChecked = { v -> update { it.copy(keepScreenOn = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "触感反馈",
                            subtitle = "按钮与开关的轻震动",
                            checked = settings.hapticFeedback,
                            onChecked = { v -> update { it.copy(hapticFeedback = v) } }
                        )
                    }
                }

                // ---------- 外观与玻璃 ----------
                item {
                    GlassGroup(title = "外观与玻璃") {
                        SegRow(
                            title = "主题",
                            options = listOf("跟随系统", "浅色", "深色"),
                            selected = when (settings.themeMode) {
                                ThemeMode.SYSTEM -> 0
                                ThemeMode.LIGHT -> 1
                                ThemeMode.DARK -> 2
                            },
                            onSelect = { i ->
                                update {
                                    it.copy(
                                        themeMode = when (i) {
                                            0 -> ThemeMode.SYSTEM
                                            1 -> ThemeMode.LIGHT
                                            else -> ThemeMode.DARK
                                        }
                                    )
                                }
                            }
                        )
                        GlassDivider()
                        SliderRow(
                            title = "玻璃模糊",
                            valueText = "${settings.glassBlurDp.toInt()} dp",
                            value = settings.glassBlurDp,
                            range = 0f..24f,
                            onValue = { v -> update { it.copy(glassBlurDp = v) } }
                        )
                        GlassDivider()
                        SliderRow(
                            title = "折射强度",
                            valueText = String.format(java.util.Locale.US, "%.2f", settings.glassRefraction),
                            value = settings.glassRefraction,
                            range = 0f..0.3f,
                            onValue = { v -> update { it.copy(glassRefraction = v) } }
                        )
                        GlassDivider()
                        SegRow(
                            title = "动效等级",
                            subtitle = "减弱后弹簧幅度降低，不影响可用性",
                            options = listOf("完整", "减弱", "关闭"),
                            selected = when (settings.motionLevel) {
                                MotionLevel.FULL -> 0
                                MotionLevel.REDUCED -> 1
                                MotionLevel.OFF -> 2
                            },
                            onSelect = { i ->
                                update {
                                    it.copy(
                                        motionLevel = when (i) {
                                            0 -> MotionLevel.FULL
                                            1 -> MotionLevel.REDUCED
                                            else -> MotionLevel.OFF
                                        }
                                    )
                                }
                            }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "高亮色取自文档",
                            subtitle = "用封面主色为玻璃染色",
                            checked = settings.accentFromContent,
                            onChecked = { v -> update { it.copy(accentFromContent = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "紧凑列表",
                            subtitle = "减小行高，一屏看更多文件",
                            checked = settings.compactList,
                            onChecked = { v -> update { it.copy(compactList = v) } }
                        )
                    }
                }

                // ---------- 编辑 ----------
                item {
                    GlassGroup(title = "编辑") {
                        SegRow(
                            title = "默认保存方式",
                            subtitle = "覆盖会直接写回原文件（不可撤销）",
                            options = listOf("另存为", "覆盖原文件"),
                            selected = if (settings.saveMode == SaveMode.SAVE_AS) 0 else 1,
                            onSelect = { i ->
                                update {
                                    it.copy(
                                        saveMode = if (i == 0) SaveMode.SAVE_AS else SaveMode.OVERWRITE
                                    )
                                }
                            }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "自动保存标注",
                            subtitle = "停止绘制 0.6 秒后写入本地",
                            checked = settings.autoSaveAnnotations,
                            onChecked = { v -> update { it.copy(autoSaveAnnotations = v) } }
                        )
                        GlassDivider()
                        ColorRow(
                            title = "默认高亮色",
                            current = settings.defaultHighlightColor,
                            onPick = { c -> update { it.copy(defaultHighlightColor = c) } }
                        )
                        GlassDivider()
                        SliderRow(
                            title = "墨迹粗细",
                            valueText = String.format(java.util.Locale.US, "%.1f", settings.defaultInkWidth),
                            value = settings.defaultInkWidth,
                            range = 1f..8f,
                            onValue = { v -> update { it.copy(defaultInkWidth = v) } }
                        )
                        GlassDivider()
                        SegRow(
                            title = "撤销步数",
                            options = listOf("20", "50", "100"),
                            selected = when {
                                settings.undoLimit <= 20 -> 0
                                settings.undoLimit <= 50 -> 1
                                else -> 2
                            },
                            onSelect = { i ->
                                update { it.copy(undoLimit = listOf(20, 50, 100)[i]) }
                            }
                        )
                    }
                }

                // ---------- 存储 ----------
                item {
                    GlassGroup(title = "存储") {
                        InfoRow("缩略图与标注缓存", cacheText, LIcon.Compress) {
                            scope.launch {
                                container.thumbnails.clearAll()
                                cacheText = Formatters.fileSize(container.annotations.totalBytes())
                                say("缓存已清理")
                            }
                        }
                        GlassDivider()
                        InfoRow("已授权目录", "在首页「目录」中管理", LIcon.Folder) {
                            say("回到首页点击底部「目录」即可新增或查看授权")
                        }
                        GlassDivider()
                        InfoRow("清空阅读记录", "含阅读进度与隐藏列表", LIcon.Trash) {
                            scope.launch {
                                container.prefs.clearLocalData()
                                say("本地记录已清空，PDF 文件未受影响")
                            }
                        }
                        GlassDivider()
                        InfoRow("清除全部标注", "不可恢复", LIcon.Eraser, danger = true) {
                            scope.launch {
                                container.annotations.clearAllStorage()
                                say("全部标注已清除")
                            }
                        }
                    }
                }

                // ---------- 隐私与网络 ----------
                item {
                    GlassGroup(title = "隐私与网络") {
                        SwitchRow(
                            title = "离线模式",
                            subtitle = "阻断全部网络请求（含在线链接导入）",
                            checked = settings.offlineMode,
                            onChecked = { v -> update { it.copy(offlineMode = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "仅 Wi-Fi 下载",
                            subtitle = "移动网络下不发起下载",
                            checked = settings.wifiOnly,
                            enabled = !settings.offlineMode,
                            onChecked = { v -> update { it.copy(wifiOnly = v) } }
                        )
                        GlassDivider()
                        SwitchRow(
                            title = "允许在线链接导入",
                            subtitle = "仅 HTTPS，下载后完全本地阅读",
                            checked = settings.allowLinkPreview,
                            enabled = !settings.offlineMode,
                            onChecked = { v -> update { it.copy(allowLinkPreview = v) } }
                        )
                        GlassDivider()
                        InfoRow("隐私政策", "不收集任何个人数据", LIcon.Shield) {
                            showAbout = !showAbout
                        }
                        AnimatedVisibility(visible = showAbout) {
                            Text(
                                "LPdf 不申请账号，不采集设备标识，不上传阅读内容与标注。\n" +
                                    "仅在你主动导入链接时访问该 URL；可在离线模式下一键阻断。\n" +
                                    "所有设置、进度、标注均存于应用私有目录。",
                                style = LType.Caption,
                                color = colors.secondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // ---------- 通用 ----------
                item {
                    GlassGroup(title = "通用") {
                        InfoRow("版本", "v${BuildConfig.VERSION_NAME}", LIcon.Info) {
                            say("LPdf ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                        }
                        GlassDivider()
                        InfoRow("开源许可", "PDFium / Liquid Glass", LIcon.FilePlus) {
                            say("PDFium（BSD）、Kyant0/AndroidLiquidGlass（Apache-2.0）")
                        }
                        GlassDivider()
                        // 玻璃渲染能力：设备不支持 AGSL 时会自动降级，
                        // 把状态摆出来，用户反馈「玻璃不对」时一眼能看到原因
                        val glass = LocalGlassCapability.current
                        InfoRow(
                            "玻璃渲染",
                            when (glass.level) {
                                GlassLevel.FULL -> "完整效果"
                                GlassLevel.BLUR_ONLY -> "模糊降级"
                                GlassLevel.PLAIN -> "基础降级"
                            },
                            LIcon.Sliders
                        ) {
                            say(GlassLog.diagnostic())
                        }
                        GlassDivider()
                        InfoRow("分享应用", "推荐给朋友", LIcon.Link) {
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "LPdf —— 一款纯粹的 Android PDF 阅读器")
                            }
                            runCatching { context.startActivity(Intent.createChooser(i, "分享")) }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        },

        // 玻璃覆盖层：提示条
        overlay = {
            GlassSnackbarHost(
                toast = toast,
                onDismiss = { toast = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            )
        }
    )
}

// ---------------------------------------------------------------------------
// 行组件
// ---------------------------------------------------------------------------

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = LType.Body,
                color = if (enabled) colors.text else colors.text.copy(alpha = 0.4f)
            )
            if (subtitle != null) {
                Text(subtitle, style = LType.Micro, color = colors.secondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun SegRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    subtitle: String? = null
) {
    val colors = LocalColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = LType.Body, color = colors.text)
                if (subtitle != null) Text(subtitle, style = LType.Micro, color = colors.secondary)
            }
            Spacer(Modifier.width(12.dp))
            GlassSegmented(
                options = options,
                selectedIndex = selected,
                onSelect = onSelect,
                modifier = Modifier.width(if (options.size > 2) 168.dp else 132.dp),
                height = 32.dp
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    val colors = LocalColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LType.Body, color = colors.text, modifier = Modifier.weight(1f))
            Text(valueText, style = LType.Micro, color = colors.secondary)
        }
        Spacer(Modifier.height(2.dp))
        GlassSlider(value = value, onValueChange = onValue, valueRange = range, contentDescription = title)
    }
}

@Composable
private fun ColorRow(title: String, current: Long, onPick: (Long) -> Unit) {
    val colors = LocalColors.current
    val palette = remember { listOf(0xFFFFD60A, 0xFF34C759, 0xFF0A84FF, 0xFFFF9F0A, 0xFFFF375F, 0xFFAF52DE) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = LType.Body, color = colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEach { c ->
                val active = c == current
                Box(
                    Modifier
                        .size(if (active) 22.dp else 18.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onPick(c) }
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
    icon: LIcon,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LIconView(
            icon,
            size = 17.dp,
            color = if (danger) colors.danger else colors.secondary,
            strokeWidth = 1.7.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = LType.Body,
            color = if (danger) colors.danger else colors.text,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = LType.Micro,
            color = colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        LIconView(LIcon.ChevronRight, size = 12.dp, color = colors.secondary.copy(alpha = 0.6f), strokeWidth = 1.6.dp)
    }
}
