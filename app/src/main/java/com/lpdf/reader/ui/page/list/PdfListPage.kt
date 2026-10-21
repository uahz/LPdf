package com.lpdf.reader.ui.page.list

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.core.util.Formatters
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.data.local.ListFilter
import com.lpdf.reader.data.local.SortOrder
import com.lpdf.reader.ui.glass.GlassDivider
import com.lpdf.reader.ui.glass.GlassHeader
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassListRow
import com.lpdf.reader.ui.glass.GlassLoadingOverlay
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.GlassPopover
import com.lpdf.reader.ui.glass.GlassSnackbarHost
import com.lpdf.reader.ui.glass.GlassStateCard
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.glass.LocalThumbnail
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.glass.pillShape
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * P1 文件列表首页。
 *
 * 层级：
 * - L1 背景（暖灰 / 近黑）
 * - L2 内容（大标题、筛选胶囊、列表）→ 由 [LiquidGlassScope] 录进 GraphicsLayer
 * - L4 玻璃：顶部按钮、底部浮框、提示条
 */
/**
 * P1 文件列表首页。
 *
 * 层级（《视觉规范 §2.1》）：
 * - L1 背景（暖灰 / 近黑）
 * - L2 内容（大标题、筛选胶囊、列表）→ 被 [LiquidGlassScope] 录进 GraphicsLayer
 * - L4 玻璃（顶部按钮、底部 Dock、浮框、提示条）→ 位于覆盖层，采样 L1+L2
 */
@Composable
fun PdfListPage(
    container: AppContainer,
    settings: AppSettings,
    onOpenReader: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: (path: String, name: String, pages: Int) -> Unit = { _, _, _ -> }
) {
    val vm = rememberListViewModel(container)
    val state by vm.state.collectAsState()
    val colors = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showSort by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf<PdfItem?>(null) }

    val filterLabels = remember { listOf("全部", "最近", "已标注", "加密", "目录") }
    val filters = remember {
        listOf(ListFilter.ALL, ListFilter.RECENT, ListFilter.ANNOTATED, ListFilter.ENCRYPTED, ListFilter.SAF)
    }
    val sortLabels = remember { listOf("修改时间（新→旧）", "文件名 A→Z", "文件大小（大→小）") }
    val sorts = remember { listOf(SortOrder.MODIFIED_DESC, SortOrder.NAME_ASC, SortOrder.SIZE_DESC) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 持久化失败的提示交给 ListViewModel.importFromUri 统一处理，
            // 那里知道 uri 是否真的拿到了长期授权，可以写进 PdfItem。
            vm.importFromUri(uri) { item, _ -> onOpenReader(item.uri, item.displayName) }
        }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addTree(uri)
    }

    // 条目授权失效时，玻璃提示条上的「重新选择」把用户送回系统选择器
    LaunchedEffect(state.reselectRequest) {
        if (state.reselectRequest) {
            vm.consumeReselect()
            runCatching { pickFile.launch(arrayOf("application/pdf")) }
        }
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, "android.permission.READ_MEDIA_DOCUMENTS"
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) vm.toast("需要读取文档权限才能自动列出本地 PDF，也可以手动选择文件")
        vm.scan()
    }

    // 上次若崩溃过，首屏把堆栈摆出来：这是目前唯一能把「打开闪退」变成可排查信息的通道
    var crashReport by remember {
        mutableStateOf(
            runCatching { com.lpdf.reader.core.util.CrashReporter.read(context) }.getOrNull()
        )
    }

    fun openItem(item: PdfItem) = vm.requestOpen(item) { it2, _ ->
        onOpenReader(it2.uri, it2.displayName)
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiquidGlassScope(
        modifier = Modifier.background(colors.pageBg),

        // ---------------- L1 + L2：被玻璃采样的内容层 ----------------
        content = {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(topInset + 12.dp))

                GlassHeader(
                    title = "LPdf",
                    actions = {
                        GlassIconButton(
                            icon = LIcon.Search,
                            contentDescription = "搜索",
                            onClick = { vm.setSearchActive(!state.searchActive) },
                            size = 40.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(
                            icon = LIcon.Plus,
                            contentDescription = "导入 PDF",
                            onClick = { showImport = true },
                            size = 40.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(
                            icon = LIcon.Gear,
                            contentDescription = "设置",
                            onClick = onOpenSettings,
                            size = 40.dp
                        )
                    }
                )

                Spacer(Modifier.height(14.dp))

                AnimatedVisibility(
                    visible = state.searchActive,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 3 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 })
                ) {
                    SearchField(
                        value = state.query,
                        onValueChange = vm::query,
                        onClose = { vm.setSearchActive(false) }
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LSpace.pageHorizontal, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEachIndexed { i, f ->
                        FilterChip(
                            label = filterLabels[i],
                            selected = state.listFilter == f,
                            onClick = { vm.setFilter(f) }
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LSpace.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${state.visibleItems.size} 个文档",
                        style = LType.Caption,
                        color = colors.secondary
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier
                            .clip(pillShape(28f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { showSort = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            sortLabels[sorts.indexOf(state.sortOrder).coerceAtLeast(0)],
                            style = LType.Micro,
                            color = colors.secondary
                        )
                        Spacer(Modifier.width(4.dp))
                        LIconView(LIcon.ChevronUpDown, size = 12.dp, color = colors.secondary, strokeWidth = 1.4.dp)
                    }
                }

                Spacer(Modifier.height(6.dp))

                Box(Modifier.fillMaxSize()) {
                    val items = state.visibleItems
                    if (items.isEmpty() && !state.loading) {
                        EmptyState(
                            permissionDenied = state.permissionDenied,
                            hasQuery = state.query.isNotBlank(),
                            onGrant = { pickTree.launch(null) },
                            onPickFile = { pickFile.launch(arrayOf("application/pdf")) },
                            onRescan = { vm.scan() }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                start = LSpace.pageHorizontal,
                                end = LSpace.pageHorizontal,
                                bottom = bottomInset + 150.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                                PdfRow(
                                    item = item,
                                    thumbPath = state.thumbs[item.id],
                                    compact = settings.compactList,
                                    index = index,
                                    onClick = { openItem(item) },
                                    onLongClick = { longPressed = item }
                                )
                            }
                        }
                    }
                }
            }
        },

        // ---------------- L4：玻璃覆盖层 ----------------
        overlay = {
            // 排序浮层
            if (showSort) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { showSort = false } }
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = topInset + 150.dp, end = LSpace.pageHorizontal)
                    ) {
                        GlassPopover(
                            options = sortLabels,
                            selectedIndex = sorts.indexOf(state.sortOrder).coerceAtLeast(0),
                            onSelect = { idx ->
                                vm.setSort(sorts[idx]); showSort = false
                            },
                            onDismiss = { showSort = false }
                        )
                    }
                }
            }

            // 底部玻璃 Dock
            //
            // 必须用 Box + align 贴底，不能只靠 padding(bottom)。
            // 覆盖层的根节点实际是一个「按内容包裹」的坐标空间：
            // 直接放一个 Row 进来，它会被排在 **顶部**，padding(bottom) 只是
            // 在它下面撑出一段空白，看上去就是「底栏飞到了状态栏下面」
            // （v1.3.3 真机截图）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                BottomDock(
                    bottomInset = bottomInset,
                    onPickFile = { pickFile.launch(arrayOf("application/pdf")) },
                    onPickTree = { pickTree.launch(null) },
                    onRescan = { vm.scan() },
                    count = state.treeCount
                )
            }

            // 长按浮框
            longPressed?.let { item ->
                ItemActionSheet(
                    item = item,
                    thumbnail = state.thumbs[item.id],
                    onOpen = {
                        longPressed = null
                        openItem(item)
                    },
                    onForget = { vm.forget(item); longPressed = null },
                    onTools = {
                        val src = item.uri
                        longPressed = null
                        onOpenTools(src, item.displayName, item.pageCount ?: 0)
                    },
                    onDismiss = { longPressed = null }
                )
            }

            // 导入面板
            if (showImport) {
                ImportSheet(
                    bottomInset = bottomInset,
                    onDismiss = { showImport = false },
                    onPickFile = { showImport = false; pickFile.launch(arrayOf("application/pdf")) },
                    onPickTree = { showImport = false; pickTree.launch(null) },
                    onImportLink = { url ->
                        showImport = false
                        vm.importLink(url) { scope.launch { runCatching { listState.animateScrollToItem(0) } } }
                    }
                )
            }

            // 加载遮罩
            state.progressText?.let { GlassLoadingOverlay(text = it) }

            // 密码面板
            state.passwordRequest?.let { item ->
                PasswordSheet(
                    fileName = item.displayName,
                    error = state.passwordError,
                    onSubmit = { pwd ->
                        vm.submitPassword(pwd) { it2, _ -> onOpenReader(it2.uri, it2.displayName) }
                    },
                    onDismiss = vm::dismissPassword
                )
            }

            // 上次崩溃报告
            crashReport?.let { report ->
                com.lpdf.reader.ui.glass.CrashReportSheet(
                    report = report,
                    onCopy = {
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("LPdf 崩溃日志", report))
                        }
                        vm.toast("已复制，粘贴发出来即可")
                    },
                    onDismiss = {
                        com.lpdf.reader.core.util.CrashReporter.clear(context)
                        crashReport = null
                    }
                )
            }

            // 提示条
            //
            // 位置必须在标题栏**下方**。原来放在 topInset + 8dp，正好压住
            // 「LPdf」标题和右侧三个图标 —— 启动时那句「需要读取文档权限…」
            // 直接盖在标题上，两行字叠成一坨（v1.3.4 真机截图）。
            // 68dp = 标题行高度（图标 40dp + 上下留白），给足呼吸空间。
            GlassSnackbarHost(
                toast = state.toast,
                onDismiss = vm::dismissToast,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset + 68.dp)
            )
        }
    )
}

// ---------------------------------------------------------------------------
// 列表行
// ---------------------------------------------------------------------------

@Composable
private fun PdfRow(
    item: PdfItem,
    thumbPath: String?,
    compact: Boolean,
    index: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = LocalColors.current
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(item.id) {
        enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 380f))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = enter.value
            translationY = (1f - enter.value) * 18.dp.toPx()
        }
    ) {
        GlassListRow(
            title = item.displayName,
            subtitle = buildString {
                append(Formatters.smartTime(item.lastModified))
                append(" · ")
                append(Formatters.fileSize(item.sizeBytes))
                if (item.pageCount != null && item.pageCount > 0) {
                    append(" · ")
                    append(item.pageCount)
                    append(" 页")
                }
            },
            thumbPath = thumbPath,
            onClick = onClick,
            onLongClick = onLongClick,
            compact = compact,
            badge = when {
                item.isBroken -> ({
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(colors.danger),
                        contentAlignment = Alignment.Center
                    ) { LIconView(LIcon.Broken, size = 8.dp, color = Color.White, strokeWidth = 1.6.dp) }
                })
                item.isEncrypted -> ({
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(colors.secondary),
                        contentAlignment = Alignment.Center
                    ) { LIconView(LIcon.Lock, size = 8.dp, color = Color.White, strokeWidth = 1.6.dp) }
                })
                else -> null
            },
            trailing = {
                LIconView(
                    LIcon.ChevronRight,
                    size = 14.dp,
                    color = colors.secondary.copy(alpha = 0.6f),
                    strokeWidth = 1.6.dp
                )
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 筛选胶囊（内容层，选中态用高亮色，不是 Material Chip）
// ---------------------------------------------------------------------------

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalColors.current
    val alpha by animateFloatAsState(if (selected) 1f else 0f, Motion.segmented, label = "chip")
    Box(
        Modifier
            .height(32.dp)
            .clip(pillShape(32f))
            .background(
                if (colors.isDark) Color.White.copy(alpha = 0.06f + 0.10f * alpha)
                else Color.Black.copy(alpha = 0.04f + 0.02f * alpha)
            )
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = LType.Caption,
            color = if (selected) colors.text else colors.secondary
        )
    }
}

// ---------------------------------------------------------------------------
// 搜索框（内容层，圆角胶囊，无 Material TextField）
// ---------------------------------------------------------------------------

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit) {
    val colors = LocalColors.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LSpace.pageHorizontal)
            .height(44.dp)
            .clip(pillShape(44f))
            .background(if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LIconView(LIcon.Search, size = 16.dp, color = colors.secondary, strokeWidth = 1.6.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索文件名", style = LType.Body, color = colors.secondary.copy(alpha = 0.7f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LType.Body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    if (value.isEmpty()) {
                        onClose(); keyboard?.hide()
                    } else onValueChange("")
                },
            contentAlignment = Alignment.Center
        ) {
            LIconView(
                if (value.isEmpty()) LIcon.Xmark else LIcon.Xmark,
                size = 12.dp, color = colors.secondary, strokeWidth = 1.8.dp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 底部玻璃 Dock
// ---------------------------------------------------------------------------

@Composable
private fun BottomDock(
    bottomInset: Dp,
    onPickFile: () -> Unit,
    onPickTree: () -> Unit,
    onRescan: () -> Unit,
    count: Int
) {
    val navBottom = bottomInset
    // Dock 是浮在列表上方的，列表滚到底时最后几行会从玻璃底下透出来 ——
    // 玻璃再厚也挡不住「内容压着内容」的脏乱感。这里在 Dock 后面补一层
    // 从透明到底色的竖向渐变，让内容自然淡出、Dock 独立成形。
    val colors = LocalColors.current
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(LSpace.dockHeight + navBottom + 40.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.pageBg.copy(alpha = 0f),
                            colors.pageBg.copy(alpha = 0.85f),
                            colors.pageBg
                        )
                    )
                )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = LSpace.pageHorizontal,
                    end = LSpace.pageHorizontal,
                    bottom = navBottom + 20.dp
                )
                .height(LSpace.dockHeight)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.Center
        ) {
            LiquidGlass(Modifier.height(LSpace.dockHeight), shape = Shapes2.XL) {
                Row(
                    Modifier
                        .height(LSpace.dockHeight)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DockItem(LIcon.FilePlus, "导入", onPickFile)
                    DockDivider()
                    DockItem(LIcon.Folder, if (count > 0) "目录 ($count)" else "目录", onPickTree)
                    DockDivider()
                    DockItem(LIcon.Rotate, "扫描", onRescan)
                }
            }
        }
    }
}

@Composable
private fun DockItem(icon: LIcon, label: String, onClick: () -> Unit) {
    val colors = LocalColors.current
    Column(
        Modifier
            .clip(Shapes2.M)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LIconView(icon, size = 19.dp, color = colors.text, strokeWidth = 1.7.dp)
        Spacer(Modifier.height(3.dp))
        Text(label, style = LType.Micro, color = colors.secondary)
    }
}

@Composable
private fun DockDivider() {
    val colors = LocalColors.current
    Box(
        Modifier
            .width(0.5.dp)
            .height(26.dp)
            .background(colors.separator)
    )
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(
    permissionDenied: Boolean,
    hasQuery: Boolean,
    onGrant: () -> Unit,
    onPickFile: () -> Unit,
    onRescan: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Spacer(Modifier.height(40.dp))
        when {
            permissionDenied -> GlassStateCard(
                icon = LIcon.Shield,
                title = "需要读取文档权限",
                message = "LPdf 只读取你授权的 PDF 文件，不会上传任何内容。",
                primary = "去授权",
                onPrimary = onGrant,
                secondary = "先手动选择文件",
                onSecondary = onPickFile,
                modifier = Modifier.padding(top = 40.dp)
            )

            hasQuery -> GlassStateCard(
                icon = LIcon.Search,
                title = "没有匹配的文档",
                message = "换个关键词试试，或清空筛选条件。",
                modifier = Modifier.padding(top = 40.dp)
            )

            else -> GlassStateCard(
                icon = LIcon.FilePlus,
                title = "还没有 PDF",
                message = "手动导入一个文件，或授权一个目录让 LPdf 自动发现。",
                primary = "选择文件",
                onPrimary = onPickFile,
                secondary = "授权目录",
                onSecondary = onGrant,
                modifier = Modifier.padding(top = 40.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 长按操作浮框
// ---------------------------------------------------------------------------

@Composable
private fun ItemActionSheet(
    item: PdfItem,
    thumbnail: String?,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    onTools: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalColors.current
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        // 被长按的卡片预览：跟随手指轻微拖拽 + 实时扭曲
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpen() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { offset = Offset.Zero }
                    ) { change, drag ->
                        change.consume()
                        offset += drag * 0.85f
                    }
                }
        ) {
            LiquidGlass(
                modifier = Modifier.width(232.dp),
                shape = Shapes2.XL,
                highlightColor = item.highlightColor?.let { Color(it) },
                showHighlight = true,
                highlightOffset = offset
            ) {
                Column(Modifier.padding(14.dp)) {
                    LocalThumbnail(
                        thumbnail,
                        Modifier
                            .fillMaxWidth()
                            .height(190.dp),
                        corner = 20.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        item.displayName,
                        style = LType.BodyStrong,
                        color = colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Formatters.smartTime(item.lastModified)} · ${Formatters.fileSize(item.sizeBytes)}",
                        style = LType.Micro,
                        color = colors.secondary
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton("打开", onOpen, Modifier.weight(1f), height = 40.dp)
                        GlassPillButton("工具箱", onTools, Modifier.weight(1f), height = 40.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton(
                            "从列表隐藏",
                            onForget,
                            Modifier.weight(1f),
                            height = 38.dp,
                            contentColor = colors.danger
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 导入面板
// ---------------------------------------------------------------------------

@Composable
private fun ImportSheet(
    bottomInset: Dp,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPickTree: () -> Unit,
    onImportLink: (String) -> Unit
) {
    val colors = LocalColors.current
    var url by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = LSpace.pageHorizontal,
                    end = LSpace.pageHorizontal,
                    bottom = bottomInset + 16.dp
                )
        ) {
            LiquidGlass(Modifier.fillMaxWidth(), shape = Shapes2.XL) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("导入 PDF", style = LType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
                        GlassIconButton(LIcon.Xmark, "关闭", onDismiss, size = 32.dp, iconSize = 14.dp, showBg = false)
                    }
                    Spacer(Modifier.height(16.dp))
                    SheetRow(LIcon.FilePlus, "从文件管理器选择", "支持单文件", onPickFile)
                    GlassDivider(startIndent = 44.dp)
                    SheetRow(LIcon.Folder, "授权目录", "自动发现该目录下全部 PDF", onPickTree)
                    GlassDivider(startIndent = 44.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("在线链接（仅 HTTPS，下载后可离线阅读）", style = LType.Micro, color = colors.secondary)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(pillShape(42f))
                            .background(if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (url.isEmpty()) {
                                Text("https://…/doc.pdf", style = LType.Caption, color = colors.secondary.copy(alpha = 0.7f))
                            }
                            BasicTextField(
                                value = url,
                                onValueChange = { url = it },
                                singleLine = true,
                                textStyle = LType.Caption.copy(color = colors.text),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "导入",
                            style = LType.Caption,
                            color = if (url.startsWith("https://", true)) colors.accent else colors.secondary.copy(alpha = 0.5f),
                            modifier = Modifier.clickable(
                                enabled = url.startsWith("https://", true),
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onImportLink(url) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: LIcon, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Shapes2.L)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(Shapes2.S)
                .background(if (colors.isDark) Color.White.copy(alpha = 0.10f) else colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { LIconView(icon, size = 16.dp, color = colors.accent, strokeWidth = 1.7.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = LType.Body, color = colors.text)
            Text(subtitle, style = LType.Micro, color = colors.secondary)
        }
    }
}

// ---------------------------------------------------------------------------
// 密码面板
// ---------------------------------------------------------------------------

@Composable
private fun PasswordSheet(
    fileName: String,
    error: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalColors.current
    var pwd by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        LiquidGlass(Modifier.width(320.dp), shape = Shapes2.XL) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { LIconView(LIcon.Lock, size = 22.dp, color = colors.accent) }
                Spacer(Modifier.height(14.dp))
                Text("该文档已加密", style = LType.TitleM, color = colors.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    Formatters.middleEllipsis(fileName, 26),
                    style = LType.Micro,
                    color = colors.secondary,
                    maxLines = 1
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(pillShape(44f))
                        .background(if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (pwd.isEmpty()) {
                        Text("输入密码", style = LType.Body, color = colors.secondary.copy(alpha = 0.7f))
                    }
                    BasicTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        singleLine = true,
                        textStyle = LType.Body.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (pwd.isNotEmpty()) onSubmit(pwd) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text("密码不正确", style = LType.Micro, color = colors.danger)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton("取消", onDismiss, Modifier.weight(1f), height = 42.dp)
                    GlassPillButton(
                        "解锁",
                        { if (pwd.isNotEmpty()) onSubmit(pwd) },
                        Modifier.weight(1f),
                        height = 42.dp,
                        enabled = pwd.isNotEmpty()
                    )
                }
            }
        }
    }
}
