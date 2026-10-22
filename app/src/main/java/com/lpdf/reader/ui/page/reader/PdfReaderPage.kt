package com.lpdf.reader.ui.page.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.util.Formatters
import android.net.Uri
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotRect
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.data.local.ReadingMode
import com.lpdf.reader.data.local.SaveMode
import com.lpdf.reader.data.pdf.PageRender
import com.lpdf.reader.data.pdf.TextSelection
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.GlassProgressRing
import com.lpdf.reader.ui.glass.GlassSnackbarHost
import com.lpdf.reader.ui.glass.GlassSlider
import com.lpdf.reader.ui.glass.GlassStateCard
import com.lpdf.reader.ui.glass.GlassTab
import com.lpdf.reader.ui.glass.GlassTabBar
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlass
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.glass.pillShape
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import com.lpdf.reader.ui.theme.Motion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * P2 阅读页。
 *
 * - 竖向滚动连续阅读（也支持单页模式）
 * - 双指缩放 1x~5x（图形层缩放，不重渲染位图）
 * - 轻点页面中部显隐玻璃 UI
 * - 底部玻璃 Dock / 页码胶囊 / 目录抽屉 / 编辑工具岛
 */
@Composable
fun PdfReaderPage(
    container: AppContainer,
    settings: AppSettings,
    uri: String,
    displayName: String,
    /** 指定起始页（0 基）。-1 = 按上次阅读进度（v1.6.0，供书签跳转） */
    startPage: Int = -1,
    /**
     * 文件修改时间（毫秒）。存储键 = hash(uri|mtime)，标注/书签/进度全按此键存盘。
     * 传 0 会算出另一套键，表现为「标注画了重开就没了」——所以必须由导航参数带过来。
     */
    lastModified: Long = 0L,
    onClose: () -> Unit
) {
    val vm = rememberReaderViewModel(container, uri)
    val state by vm.state.collectAsState()
    val colors = LocalColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 设置项要先于 bind 落地：open() 里的「是否恢复上次进度」直接读
    // rememberPosition，晚一拍就会用默认值 true 走一遍错误的分支。
    // key 用具体字段而不是整个 settings，避免无关设置变更（比如玻璃模糊）
    // 把 highlightTouched 之类的会话状态牵连进重算。
    LaunchedEffect(
        settings.rememberPosition,
        settings.defaultHighlightColor,
        settings.undoLimit,
        settings.defaultInkWidth,
        settings.autoSaveAnnotations
    ) {
        vm.applySettings(
            rememberPosition = settings.rememberPosition,
            defaultHighlightColor = settings.defaultHighlightColor,
            undoLimit = settings.undoLimit,
            defaultInkWidth = settings.defaultInkWidth,
            autoSaveAnnotations = settings.autoSaveAnnotations
        )
    }

    // 绑定文档（uri + mtime 由导航参数给出，缺失时用 0 仍可读）
    LaunchedEffect(uri, lastModified, startPage) {
        vm.bind(uri, displayName, lastModified, null, startPage)
        vm.setZoomLimit(settings.zoomLimit.toFloat())
    }

    // 标注扁平化导出：用 SAF 让用户选保存位置，避免申请额外存储权限
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri ->
        if (targetUri != null) vm.exportFlattened(targetUri)
    }

    // 阅读时常亮。
    // 用 LocalActivity 而不是 `LocalContext.current as? Activity`：
    // Compose 会往 LocalContext 里塞 ContextWrapper（如主题包装），强转成 Activity 会拿到 null，
    // 表现为「常亮开关点了没反应」。LocalActivity 是 activity-compose 提供的正规通路。
    val activity = androidx.activity.compose.LocalActivity.current
    LaunchedEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 自动隐藏工具栏：操作后 3 秒淡出（仅在开关开启且未在绘制时）
    LaunchedEffect(settings.autoHideToolbar, state.chromeVisible, state.tool) {
        if (settings.autoHideToolbar && state.chromeVisible && state.tool == EditorTool.NONE) {
            kotlinx.coroutines.delay(3000)
            vm.setChrome(false)
        }
    }

    // 自动跟随当前页
    LaunchedEffect(state.currentPage, state.pageCount) {
        if (state.pageCount > 0 && listState.firstVisibleItemIndex != state.currentPage) {
            runCatching { listState.scrollToItem(state.currentPage) }
        }
    }

    // 滚动时更新页码
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { vm.onPageSettled(it) }
    }

    LiquidGlassScope(
        modifier = Modifier.background(colors.pageBg),

        // -------- L2：被玻璃采样的内容层（页面位图） --------
        content = {
            // 渲染宽度 = 内容区实测宽度（物理像素）。
            //
            // 这里**不能**用 `LocalConfiguration.screenWidthDp.dp.toPx()`：v1.5.1
            // 定位到的根因就是它。在桌面/自由窗口模式（Android 12L+、折叠屏展开态、
            // 分屏）下 `screenWidthDp` 是「窗口」的 dp 宽，而 Compose 布局又会被父容器
            // 再限一次宽，两者对不上；再叠上 density 的真实换算，渲染宽度就会被放大到
            // 屏幕物理宽的数倍 —— 高度按同比例放大，最终就是用户截图里的 1168×13657
            // （反推宽度 9657px，正好是 1168 的 8.3 倍）。
            //
            // 实测约束拿到的是**物理像素**，之后不再有任何 dp/density 换算，
            // 从源头消灭「宽度被放大」这条路径（sanitizePageSize 只作兜底）。
            var contentWidthPx by remember { mutableIntStateOf(0) }
            // 同一份宽度的 dp 值，供分档留白判断用。
            // 直接由 px ÷ density 得到，不使用 Configuration.screenWidthDp ——
            // 后者在分屏 / 折叠屏下与实测宽度不一致（见上方注释）。
            val contentWidthDp = with(LocalDensity.current) { contentWidthPx.toDp() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { if (it.width > 0) contentWidthPx = it.width }
            ) {
            if (contentWidthPx == 0) {
                // 第一帧拿不到尺寸：先不渲染，避免用错误宽度先渲一遍再重渲
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassProgressRing(label = "正在打开…")
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassStateCard(
                        icon = if (state.error is com.lpdf.reader.core.model.PdfError.PasswordRequired)
                            LIcon.Lock else LIcon.Broken,
                        title = when (state.error) {
                            is com.lpdf.reader.core.model.PdfError.PasswordRequired -> "需要密码"
                            is com.lpdf.reader.core.model.PdfError.PermissionDenied -> "没有访问权限"
                            else -> "无法打开文档"
                        },
                        message = state.error?.message ?: "文件可能已损坏或已被移动。",
                        primary = "返回列表",
                        onPrimary = {
                            vm.flushAsync()
                            onClose()
                        }
                    )
                }
            } else if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassProgressRing(label = "正在打开…")
                }
            } else {
                val mode = settings.readingMode
                if (mode == ReadingMode.SINGLE_PAGE) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { vm.toggleChrome() }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (drag.x < -40f) {
                                        vm.onPageSettled((state.currentPage + 1).coerceAtMost(state.pageCount - 1))
                                    } else if (drag.x > 40f) {
                                        vm.onPageSettled((state.currentPage - 1).coerceAtLeast(0))
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        PageCanvas(
                            vm = vm,
                            page = state.currentPage,
                            widthPx = contentWidthPx,
                            maxZoom = settings.zoomLimit.toFloat(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // v1.7.2：页面宽度改为「实测可用宽度」自适应。
                    //
                    // 旧版固定 padding(horizontal = 16.dp)，在 400dp 宽的手机上
                    // 页面只剩 368dp —— 看起来就是「只占屏幕中间一小条」。
                    // 真机截图里页面左右各空出一大块，正是这个固定值造成的
                    // （小屏上尤其明显，因为 16dp 对窄屏的相对占比更高）。
                    //
                    // 现在按可用宽度分档：
                    //   可用宽 < 360dp（小屏 / 分屏）→ 6dp，尽量吃满
                    //   其余                          → 10dp，留出视觉呼吸
                    // 换算成 px 后传给渲染层，渲染宽度与显示宽度严格一致，
                    // 不会出现「按 1080 渲完塞进 920 的框里」被二次缩小的糊边。
                    val hPad = if (contentWidthDp < 360.dp) 6.dp else 10.dp
                    val hPadPx = with(LocalDensity.current) { hPad.toPx() }
                    val pageWidthPx = (contentWidthPx - hPadPx * 2).toInt().coerceAtLeast(240)
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { vm.toggleChrome() }
                            }
                    ) {
                        itemsIndexed(
                            (0 until state.pageCount).toList(),
                            key = { _, p -> p }
                        ) { index, page ->
                            PageItem(
                                vm = vm,
                                page = page,
                                widthPx = pageWidthPx,
                                horizontalPadding = hPad,
                                settings = settings,
                                highlightColor = state.highlightColor,
                                inkWidth = vm.inkWidth,
                                tool = if (state.chromeVisible) state.tool else EditorTool.NONE
                            )
                        }
                    }
                }
            }

            }
        },

        // -------- L4：玻璃覆盖层 --------
        overlay = {
            Box(Modifier.fillMaxSize()) {

            // 顶部导航胶囊
            AnimatedVisibility(
                visible = state.chromeVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    title = Formatters.middleEllipsis(state.displayName.ifBlank { "阅读中" }, 22),
                    subtitle = if (state.pageCount > 0) {
                        "${state.currentPage + 1} / ${state.pageCount}"
                    } else "",
                    hasToc = state.toc.isNotEmpty(),
                    night = state.pageInvert,
                    onBack = {
                        vm.flushAsync()
                        onClose()
                    },
                    onNight = { vm.setPageInvert(!state.pageInvert) },
                    onToc = vm::toggleToc
                )
            }

            // 页码胶囊（滚动中显示）
            AnimatedVisibility(
                visible = state.chromeVisible && state.pageCount > 0,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 92.dp)
            ) {
                PagePill(page = state.currentPage + 1, total = state.pageCount)
            }

            // 编辑工具岛（选中工具后展开）
            AnimatedVisibility(
                visible = state.chromeVisible && state.tool != EditorTool.NONE,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 150.dp)
            ) {
                ToolIsland(
                    tool = state.tool,
                    color = state.highlightColor,
                    inkWidth = vm.inkWidth,
                    annotationCount = state.annotationCount,
                    onColor = vm::setHighlightColor,
                    onWidth = vm::setInkWidth,
                    onUndo = {
                        if (!vm.undo(state.currentPage)) vm.toast("没有可撤销的标注")
                    },
                    onClear = {
                        vm.clearPage(state.currentPage)
                        vm.toast("已清空本页标注")
                    },
                    onExport = {
                        // 「默认保存方式 = 覆盖原文件」时跳过选择器，直接写回。
                        // 但能不能写回取决于这份 uri 是否可写：
                        // MediaStore 文档、SAF 只读授权都写不进去，
                        // 这时必须回落到「另存为」，否则用户点了没反应还以为坏了。
                        val canOverwrite = settings.saveMode == SaveMode.OVERWRITE &&
                            vm.canOverwriteSource()
                        if (canOverwrite) {
                            vm.exportFlattened(Uri.parse(uri))
                        } else {
                            if (settings.saveMode == SaveMode.OVERWRITE) {
                                vm.toast("这份文件不能直接覆盖，改用另存为")
                            }
                            exportLauncher.launch(suggestExportName(state.displayName))
                        }
                    },
                    onSignature = vm::toggleSignaturePanel,
                    onClose = { vm.setTool(EditorTool.NONE) }
                )
            }

            // 底部玻璃 Dock
            AnimatedVisibility(
                visible = state.chromeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ReaderDock(
                    haptic = settings.hapticFeedback,
                    tool = state.tool,
                    tocOpen = state.tocOpen,
                    bookmarkCount = state.bookmarks.size,
                    bookmarked = state.currentPageBookmarked,
                    searchActive = state.searchOpen || state.searchHits.isNotEmpty(),
                    onTool = { t ->
                        vm.setTool(if (state.tool == t) EditorTool.NONE else t)
                        if (t == EditorTool.NONE) vm.closeToolIsland()
                    },
                    onToc = vm::toggleToc,
                    onBookmarks = vm::toggleBookmarksPanel,
                    onSearch = vm::openSearch,
                    onBookmarkToggle = { vm.toggleBookmarkOnCurrentPage() },
                    onPrev = { vm.onPageSettled((state.currentPage - 1).coerceAtLeast(0)) },
                    onNext = { vm.onPageSettled((state.currentPage + 1).coerceAtMost(state.pageCount - 1)) }
                )
            }

            // 目录抽屉
            AnimatedVisibility(
                visible = state.tocOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                TocPanel(
                    entries = state.toc,
                    current = state.currentPage,
                    onPick = { page ->
                        vm.onPageSettled(page)
                        scope.launch { runCatching { listState.scrollToItem(page) } }
                        vm.closeToc()
                    },
                    onClose = vm::closeToc
                )
            }

            // 书签面板
            AnimatedVisibility(
                visible = state.bookmarksOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                BookmarkPanel(
                    bookmarks = state.bookmarks,
                    current = state.currentPage,
                    onPick = { page ->
                        vm.onPageSettled(page)
                        scope.launch { runCatching { listState.scrollToItem(page) } }
                        vm.closeBookmarks()
                    },
                    onAddHere = {
                        vm.toggleBookmarkOnCurrentPage()
                        vm.closeBookmarks()
                    },
                    onRename = vm::renameBookmark,
                    onRemove = vm::removeBookmark,
                    onClose = vm::closeBookmarks
                )
            }

            // 搜索面板
            AnimatedVisibility(
                visible = state.searchOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                SearchPanel(
                    query = state.searchQuery,
                    hits = state.searchHits,
                    searching = state.searching,
                    progress = state.searchProgress,
                    activeHit = state.activeHit,
                    matchCase = state.matchCase,
                    onQuery = vm::setSearchQuery,
                    onSubmit = vm::runSearch,
                    onToggleMatchCase = vm::toggleMatchCase,
                    onJump = { i ->
                        vm.jumpToHit(i)?.let { page ->
                            vm.onPageSettled(page)
                            scope.launch { runCatching { listState.scrollToItem(page) } }
                        }
                    },
                    onClose = vm::closeSearch
                )
            }

            // 搜索结果在页面上的叠加高亮（不写入文件，纯显示层）
            if (state.searchHits.isNotEmpty() && !state.searchOpen) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
                        )
                ) {
                    LiquidGlass(Modifier.height(40.dp), shape = pillShape(40f)) {
                        Row(
                            Modifier
                                .height(40.dp)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DockBtn(LIcon.ChevronUp, "上一处", false, false, 32.dp) {
                                vm.stepHit(false)?.let { page ->
                                    vm.onPageSettled(page)
                                    scope.launch { runCatching { listState.scrollToItem(page) } }
                                }
                            }
                            Text(
                                "${state.activeHit + 1} / ${state.searchHits.size}",
                                style = LType.Micro,
                                color = colors.text,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            DockBtn(LIcon.ChevronDown, "下一处", false, false, 32.dp) {
                                vm.stepHit(true)?.let { page ->
                                    vm.onPageSettled(page)
                                    scope.launch { runCatching { listState.scrollToItem(page) } }
                                }
                            }
                            Box(Modifier.width(4.dp))
                            DockBtn(LIcon.Xmark, "关闭搜索", false, false, 32.dp) { vm.closeSearch() }
                        }
                    }
                }
            }

            // 签名面板
            AnimatedVisibility(
                visible = state.signatureOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp
                    )
            ) {
                val sigs by vm.signatures.collectAsState()
                Box(Modifier.padding(horizontal = LSpace.pageHorizontal)) {
                    SignaturePad(
                        signatures = sigs,
                        onSave = { bmp, name -> vm.saveAndPlaceSignature(bmp, name) },
                        onPick = { asset ->
                            vm.pickSignature(asset)
                            vm.closeSignaturePanel()
                        },
                        onDelete = vm::deleteSignature,
                        onClose = vm::closeSignaturePanel
                    )
                }
            }

            GlassSnackbarHost(
                toast = state.toast,
                onDismiss = vm::dismissToast,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp)
            )

            // 标注导出进度（逐页渲染 + 叠加，页数多时耗时明显）
            val exporting by vm.exportProgress.collectAsState()
            exporting?.let { p ->
                com.lpdf.reader.ui.glass.GlassLoadingOverlay(
                    text = "正在导出标注…",
                    progress = p
                )
            }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// 单页渲染
// ---------------------------------------------------------------------------

/**
 * 生成导出文件的默认名：`原名-已标注.pdf`。
 * SAF 的 CreateDocument 只接受文件名，不接受路径。
 */
private fun suggestExportName(displayName: String): String {
    val base = displayName.substringBeforeLast('.', displayName).ifBlank { "文档" }
    // 去掉文件名里对 SAF 不友好的字符
    val safe = base.replace(Regex("""[\\/:*?"<>|]"""), "_").take(60)
    return "$safe-已标注.pdf"
}

@Composable
private fun PageItem(
    vm: ReaderViewModel,
    page: Int,
    /** 已扣掉左右留白的可用宽度（px）。渲染宽度与显示宽度严格一致 */
    widthPx: Int,
    /** 左右留白（dp），与调用方扣掉的值一致 */
    horizontalPadding: Dp,
    settings: AppSettings,
    highlightColor: Long,
    /** 墨迹粗细（pt）。来自设置里的默认值，用户可在工具面板里改 */
    inkWidth: Float,
    tool: EditorTool
) {
    val areaWidthPx = widthPx.coerceAtLeast(240)
    var render by remember(page) { mutableStateOf(vm.renderFor(page)) }
    // 渲染失败时记下原因，避免「一直转圈却不说是错」
    var failure by remember(page) { mutableStateOf<String?>(null) }

    LaunchedEffect(page, areaWidthPx) {
        var attempts = 0
        // 会话可能还在打开中（ensureRendered 里 session 为 null 会直接返回）。
        // 轮询等它就绪，但**必须有上限** —— 原来没有上限，一旦渲染永远失败，
        // 这个圆环就永远转下去，用户看到的就是「打开 PDF 一直转圈」。
        while (render?.tiles.isNullOrEmpty() && attempts < MAX_RENDER_RETRY) {
            attempts++
            vm.ensureRendered(page, areaWidthPx)
            render = vm.renderFor(page)
            if (!render?.tiles.isNullOrEmpty()) break
            val err = vm.lastRenderError(page)
            if (err != null) {
                failure = err
                break
            }
            kotlinx.coroutines.delay(if (attempts < 3) 120L else 400L)
        }
        if (render?.tiles.isNullOrEmpty() && failure == null) {
            failure = "页面渲染超时，可尝试切换页面或重新打开文档"
        }
    }

    // 占位高度：v1.5.0 起用**真实宽高比**。
    // 旧版写死 A4 的 1.414f，超长页会先占一个矮框，渲染完突然撑高 ——
    // 列表整段跳动，用户还会以为点错了位置。
    val aspect = render?.aspect ?: 1.414f
    val pageHeightDp = with(LocalDensity.current) {
        (areaWidthPx * aspect / density).dp
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(Shapes2.M),
    ) {
        val current = render
        if (current == null || current.tiles.isEmpty()) {
            // 纸张占位（避免白屏跳动）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(pageHeightDp)
                    .background(Color.White.copy(alpha = 0.98f)),
                contentAlignment = Alignment.Center
            ) {
                if (failure != null) {
                    // 失败态：不再转圈，明确告诉用户发生了什么
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LIconView(
                            LIcon.Broken,
                            size = 28.dp,
                            color = LocalColors.current.secondary,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            failure!!,
                            style = LType.Micro,
                            color = LocalColors.current.secondary
                        )
                    }
                } else {
                    GlassProgressRing()
                }
            }
        } else {
            Box {
                AnnotationLayer(
                    render = current,
                    annotations = vm.annotationsOf(page),
                    tool = tool,
                    highlightColor = highlightColor,
                    inkWidth = inkWidth,
                    searchRects = vm.hitRectsOnPage(page),
                    activeSearchRects = vm.activeHitRectsOnPage(page),
                    onAdd = { vm.addAnnotation(page, it) }
                )
                // 文本选择层压在最上面，只在「浏览」状态下生效。
                // 编辑模式下长按应该是画标注，而不是选字 —— 两层同时抢手势
                // 会出现「想画一笔却弹出了复制」。
                if (tool == EditorTool.NONE) {
                    var sel by remember(page) { mutableStateOf<TextSelection?>(null) }
                    TextSelectionLayer(
                        page = page,
                        aspect = current.aspect,
                        accent = LocalColors.current.accent,
                        selection = sel,
                        resolve = { p, sx, sy, ex, ey -> vm.selectionBetween(p, sx, sy, ex, ey) },
                        prepare = { p -> vm.textLayer(p) },
                        onSelect = { sel = it },
                        onCopy = {
                            val n = sel?.let { vm.copySelection(it) } ?: 0
                            if (n == 0) vm.toast("这一页没有可选中的文字，可能是扫描件")
                            sel = null
                        },
                        onLongPressEmpty = {
                            vm.toast("这一页没有文字层，可能是扫描件")
                        }
                    )
                }
            }
        }
    }
}

/**
 * 单页渲染的最大重试次数。
 *
 * 3 次快速重试（等会话打开）+ 多次慢重试，总计约 20 秒。
 * 超过就认为这页真的渲染不出来，把转圈换成可读的错误提示 ——
 * 无限转圈是最糟的失败方式，用户既不知道发生了什么，也不知道该做什么。
 */
private const val MAX_RENDER_RETRY = 8

/**
 * 把缩放后的平移量收进可视范围（v1.7.0）。
 *
 * 放大 s 倍后，内容边缘相对视图多出来 `(s-1)/2 × 尺寸` 的余量，
 * 平移量超过这个值就只看到空白。这里按轴分别夹一下，
 * 视图尺寸还没测出来（第一帧）时原样返回，避免把偏移清零。
 */
internal fun clampOffset(offset: Offset, zoom: Float, viewSize: IntSize): Offset {
    if (viewSize.width <= 0 || viewSize.height <= 0) return offset
    val s = zoom.coerceAtLeast(1f)
    val maxX = viewSize.width * (s - 1f) / 2f
    val maxY = viewSize.height * (s - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/** 单页模式用：整屏一页，居中 */
@Composable
private fun PageCanvas(
    vm: ReaderViewModel,
    page: Int,
    widthPx: Int,
    maxZoom: Float,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var render by remember(page) { mutableStateOf(vm.renderFor(page)) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 手指在屏幕上的位置（相对内容区），缩放时用来当锚点
    var focus by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(page) {
        vm.ensureRendered(page, widthPx)
        render = vm.renderFor(page)
    }
    val z by animateFloatAsState(zoom, Motion.standard, label = "zoom")

    Box(
        modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    val oldZoom = zoom
                    val newZoom = (oldZoom * zoomChange).coerceIn(1f, maxZoom)
                    // 以手指为中心缩放（v1.7.0）。
                    //
                    // 旧写法是 `offset += pan`，缩放时画面以左上角为原点整体放大：
                    // 你捏合想看清的那个字会从手指下面滑走，越放越偏。
                    // 正确做法是把「手指所指的内容点」钉在原地：
                    //   内容坐标 c = (p - t) / s，要求缩放后 c 仍落在 p 处
                    // ⇒ 新平移 t' = p - c·s' = p - (p - t)·s'/s
                    // 再叠上这一帧的 pan（双指平移）。
                    val c = centroid
                    val ratio = if (oldZoom > 0.01f) newZoom / oldZoom else 1f
                    offset = c - (c - offset) * ratio + pan
                    zoom = newZoom
                    // 回到 1x 时把平移清零，避免残留偏移让页面贴边
                    if (newZoom <= 1.001f) offset = Offset.Zero
                    // 边界：放大后最多往每个方向拖 (s-1)/2 × 视图尺寸。
                    // 旧版没有这层约束，可以一直把页面拖出屏幕外，只剩一片背景色，
                    // 而且没有任何回弹能把它找回来。
                    offset = clampOffset(offset, newZoom, viewSize)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val current = render
        if (current == null || current.tiles.isEmpty()) {
            GlassProgressRing()
        } else {
            // 单页模式：按真实宽高比自适应填充，用同一套 tile 绘制
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        scaleX = z
                        scaleY = z
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentAlignment = Alignment.Center
            ) {
                val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                // 等比缩放：宽高各让一步，装不下就缩
                val fitW = minOf(wPx, hPx / current.aspect.coerceAtLeast(0.01f))
                val drawW = with(LocalDensity.current) { fitW.toDp() }
                TileCanvas(
                    render = current,
                    contentDescription = "第 ${page + 1} 页",
                    modifier = Modifier
                        .width(drawW)
                        .aspectRatio(1f / current.aspect.coerceAtLeast(0.01f))
                )
            }
        }
    }
}

/**
 * 一页的 tile 绘制（v1.5.0）。
 *
 * ## 为什么用 Canvas 而不是 N 个 Image
 *
 * 每个 `Image` 都是独立图层，高 DPI 下相邻两张之间会漏出 1px 白线，
 * 长页上就是一条条横纹。放在同一个 Canvas 里按整页坐标连续绘制，
 * 边缘天然重合，零缝隙。
 *
 * ## 坐标换算
 *
 * tile 的 left/top/width/height 都是「整页理想像素坐标」（[PageRender.widthPx] ×
 * [PageRender.heightPx]）。这里按控件实测尺寸等比映射，
 * 因此缩放、密度变化都不会让标注和底图错位。
 */
@Composable
private fun TileCanvas(
    render: PageRender,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    overlay: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {}
) {
    val images = remember(render.tiles) {
        render.tiles.map { it to it.bitmap.asImageBitmap() }
    }
    Canvas(
        modifier.semantics { this.contentDescription = contentDescription ?: "" }
    ) {
        val sx = size.width / render.widthPx.toFloat().coerceAtLeast(1f)
        val sy = size.height / render.heightPx.toFloat().coerceAtLeast(1f)
        images.forEach { (tile, img) ->
            drawImage(
                image = img,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (tile.left * sx).roundToInt(),
                    (tile.top * sy).roundToInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    (tile.width * sx).roundToInt().coerceAtLeast(1),
                    (tile.height * sy).roundToInt().coerceAtLeast(1)
                ),
                // 分块边界天然重合，FilterQuality.Low 足够且更省带宽；
                // 用 Medium 反而会在接缝处采样到邻居产生细线。
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
            )
        }
        overlay()
    }
}

/**
 * 标注层：底图（一组 tile）+ 归一化坐标标注 + 手指绘制。
 * 归一化坐标 0~1，与缩放无关，所以底图分几块、怎么降级都不影响标注位置。
 */
@Composable
private fun AnnotationLayer(
    render: PageRender,
    annotations: List<Annotation>,
    tool: EditorTool,
    highlightColor: Long,
    inkWidth: Float,
    searchRects: List<com.lpdf.reader.data.pdf.PdfiumEngine.AnnotRectLike> = emptyList(),
    activeSearchRects: List<com.lpdf.reader.data.pdf.PdfiumEngine.AnnotRectLike> = emptyList(),
    onAdd: (Annotation) -> Unit
) {
    val ink = remember { mutableListOf<Offset>() }

    // 签名位图缓存：路径 -> ImageBitmap。签名图不大且复用频繁，按路径记忆即可
    val signatureBitmaps = remember(annotations) {
        buildMap {
            annotations.filterIsInstance<Annotation.Signature>().forEach { s ->
                if (!containsKey(s.bitmapPath)) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(s.bitmapPath)
                    }.getOrNull()?.let { put(s.bitmapPath, it.asImageBitmap()) }
                }
            }
        }
    }

    val aspect = render.aspect.coerceAtLeast(0.01f)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
            .pointerInput(tool) {
                when (tool) {
                    EditorTool.INK -> detectDragGestures(
                        onDragStart = { ink.clear(); ink += it },
                        onDragEnd = {
                            if (ink.size > 1) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                onAdd(
                                    Annotation.Ink(
                                        id = ReaderViewModel.newId(),
                                        points = ink.map { p -> (p.x / w) to (p.y / h) },
                                        // 原来是硬编码 2f —— 「墨迹粗细」这个设置形同虚设
                                        width = inkWidth / w,
                                        color = highlightColor
                                    )
                                )
                            }
                            ink.clear()
                        }
                    ) { change, _ ->
                        change.consume()
                        ink += change.position
                    }

                    EditorTool.HIGHLIGHT, EditorTool.SHAPE, EditorTool.NOTE -> detectTapGestures { off ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val nx = off.x / w
                        val ny = off.y / h
                        when (tool) {
                            EditorTool.HIGHLIGHT -> onAdd(
                                Annotation.Highlight(
                                    id = ReaderViewModel.newId(),
                                    rects = listOf(
                                        AnnotRect(nx - 0.30f, ny - 0.012f, nx + 0.30f, ny + 0.012f)
                                    ),
                                    color = highlightColor
                                )
                            )

                            EditorTool.SHAPE -> onAdd(
                                Annotation.Shape(
                                    id = ReaderViewModel.newId(),
                                    kind = "RECT",
                                    bounds = AnnotRect(nx - 0.12f, ny - 0.06f, nx + 0.12f, ny + 0.06f),
                                    stroke = 1.6f / 600f,
                                    color = highlightColor
                                )
                            )

                            EditorTool.NOTE -> onAdd(
                                Annotation.Note(ReaderViewModel.newId(), nx, ny, "想法")
                            )

                            else -> Unit
                        }
                    }

                    else -> null
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // ① 底图：按整页坐标拼 tile，零缝隙
            val sx = w / render.widthPx.toFloat().coerceAtLeast(1f)
            val sy = h / render.heightPx.toFloat().coerceAtLeast(1f)
            render.tiles.forEach { tile ->
                drawImage(
                    image = tile.bitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (tile.left * sx).roundToInt(),
                        (tile.top * sy).roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        (tile.width * sx).roundToInt().coerceAtLeast(1),
                        (tile.height * sy).roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                )
            }

            // 搜索结果高亮：先铺淡黄底，再把当前聚焦的命中覆上橙色
            searchRects.forEach { r ->
                drawRect(
                    color = Color(0xFFFFD60A).copy(alpha = 0.30f),
                    topLeft = Offset(r.left * w, r.top * h),
                    size = Size(
                        ((r.right - r.left) * w).coerceAtLeast(1f),
                        ((r.bottom - r.top) * h).coerceAtLeast(1f)
                    )
                )
            }
            activeSearchRects.forEach { r ->
                drawRect(
                    color = Color(0xFFFF9F0A).copy(alpha = 0.52f),
                    topLeft = Offset(r.left * w, r.top * h),
                    size = Size(
                        ((r.right - r.left) * w).coerceAtLeast(1f),
                        ((r.bottom - r.top) * h).coerceAtLeast(1f)
                    )
                )
            }

            annotations.forEach { a ->
                when (a) {
                    is Annotation.Highlight -> a.rects.forEach { r ->
                        drawRect(
                            color = Color(a.color).copy(alpha = 0.42f),
                            topLeft = Offset(r.left * w, r.top * h),
                            size = Size((r.right - r.left) * w, (r.bottom - r.top) * h)
                        )
                    }

                    is Annotation.Ink -> {
                        if (a.points.size > 1) {
                            val path = Path()
                            val first = a.points.first()
                            path.moveTo(first.first * w, first.second * h)
                            a.points.drop(1).forEach { p -> path.lineTo(p.first * w, p.second * h) }
                            drawPath(
                                path, Color(a.color),
                                style = Stroke((a.width * w).coerceAtLeast(1.2f))
                            )
                        }
                    }

                    is Annotation.Shape -> drawRect(
                        color = Color(a.color),
                        topLeft = Offset(a.bounds.left * w, a.bounds.top * h),
                        size = Size(
                            (a.bounds.right - a.bounds.left) * w,
                            (a.bounds.bottom - a.bounds.top) * h
                        ),
                        style = Stroke((a.stroke * w).coerceAtLeast(1.2f))
                    )

                    is Annotation.Note -> {
                        val cx = a.x * w
                        val cy = a.y * h
                        // 批注点位统一用当前高亮色绘制
                        drawCircle(Color(highlightColor), radius = 10f, center = Offset(cx, cy))
                        drawCircle(Color.White, radius = 4f, center = Offset(cx, cy))
                    }

                    is Annotation.Signature -> {
                        // 签名图片按归一化边界铺满；文件缺失时画虚线框提示，不静默丢弃
                        val bmp = signatureBitmaps[a.bitmapPath]
                        val dst = androidx.compose.ui.geometry.Rect(
                            a.bounds.left * w,
                            a.bounds.top * h,
                            a.bounds.right * w,
                            a.bounds.bottom * h
                        )
                        if (bmp != null) {
                            drawImage(
                                image = bmp,
                                dstOffset = androidx.compose.ui.unit.IntOffset(
                                    dst.left.toInt(), dst.top.toInt()
                                ),
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    (dst.width).toInt().coerceAtLeast(1),
                                    (dst.height).toInt().coerceAtLeast(1)
                                ),
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
                            )
                        } else {
                            drawRect(
                                color = Color(0xFFFF3B30).copy(alpha = 0.55f),
                                topLeft = Offset(dst.left, dst.top),
                                size = Size(dst.width, dst.height),
                                style = Stroke(1.5f)
                            )
                        }
                    }

                    else -> Unit
                }
            }
            // 正在绘制的墨迹
            if (ink.size > 1) {
                val path = Path().apply {
                    moveTo(ink.first().x, ink.first().y)
                    ink.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, Color(highlightColor), style = Stroke(3f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    hasToc: Boolean,
    night: Boolean,
    onBack: () -> Unit,
    onNight: () -> Unit,
    onToc: () -> Unit
) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = LSpace.pageHorizontal,
                end = LSpace.pageHorizontal,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(LIcon.ChevronLeft, "返回", onBack, size = 40.dp)
        Spacer(Modifier.weight(1f))
        LiquidGlass(Modifier.height(38.dp), shape = pillShape(38f)) {
            Row(
                Modifier
                    .height(38.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        style = LType.Caption,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = LType.Micro, color = colors.secondary)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // 夜间模式：图标随状态切换（亮时显示月亮=「点了会变暗」）
        GlassIconButton(
            if (night) LIcon.Sun else LIcon.Moon,
            if (night) "切换到日间" else "夜间模式",
            onNight,
            size = 40.dp
        )
        Spacer(Modifier.weight(1f))
        if (hasToc) {
            GlassIconButton(LIcon.List, "目录", onToc, size = 40.dp)
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

/** 页码胶囊：12 / 214 */
@Composable
private fun PagePill(page: Int, total: Int) {
    val colors = LocalColors.current
    LiquidGlass(Modifier.height(30.dp), shape = pillShape(30f), showHighlight = false) {
        Row(
            Modifier
                .height(30.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$page", style = LType.number(12f.sp(), androidx.compose.ui.text.font.FontWeight.SemiBold), color = colors.text)
            Text(" / $total", style = LType.Micro, color = colors.secondary)
        }
    }
}

// 小工具：避免额外 import
private fun Float.sp() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)

// ---------------------------------------------------------------------------
// 底部 Dock
// ---------------------------------------------------------------------------

@Composable
private fun ReaderDock(
    haptic: Boolean,
    tool: EditorTool,
    tocOpen: Boolean,
    bookmarkCount: Int,
    bookmarked: Boolean,
    searchActive: Boolean,
    onTool: (EditorTool) -> Unit,
    onToc: () -> Unit,
    onBookmarks: () -> Unit,
    onSearch: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // v1.4.0：换成与首页一致的 iOS 玻璃药丸滑块。
    //
    // 旧版是「一排 DockBtn 装进一个玻璃盒」，选中态只是给按钮加一层淡底色，
    // 没有"浮起来"的层次。新版让选中的那一项外面包一片独立玻璃药丸，
    // 它会随工具切换做弹簧滑动，跟上一次选中项之间留一点惯性。
    //
    // 「上一页 / 下一页」是瞬动按钮，不参与选中逻辑，所以整条 dock 用
    // followSelection = false 关闭滑块的自动跟随 —— 但药丸仍会被拖动。
    val tabs = remember {
        listOf(
            GlassTab(LIcon.ChevronLeft, "上一页"),
            GlassTab(LIcon.List, "目录"),
            GlassTab(LIcon.Bookmark, "书签"),
            GlassTab(LIcon.Search, "搜索"),
            GlassTab(LIcon.HighlightPen, "高亮"),
            GlassTab(LIcon.Pencil, "墨迹"),
            GlassTab(LIcon.Note, "批注"),
            GlassTab(LIcon.ChevronRight, "下一页")
        )
    }

    // 哪一项算「当前选中」：面板打开优先，其次编辑工具
    val selectedIndex = when {
        tocOpen -> 1
        bookmarked -> 2
        searchActive -> 3
        tool == EditorTool.HIGHLIGHT -> 4
        tool == EditorTool.INK -> 5
        tool == EditorTool.NOTE -> 6
        else -> -1
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                start = LSpace.pageHorizontal,
                end = LSpace.pageHorizontal,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassTabBar(
            items = tabs,
            selectedIndex = selectedIndex.coerceAtLeast(0),
            compact = true,
            followSelection = selectedIndex >= 0,
            onSelect = { i ->
                if (haptic) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                when (i) {
                    0 -> onPrev()
                    1 -> onToc()
                    2 -> onBookmarks()
                    3 -> onSearch()
                    4 -> onTool(EditorTool.HIGHLIGHT)
                    5 -> onTool(EditorTool.INK)
                    6 -> onTool(EditorTool.NOTE)
                    7 -> onNext()
                }
            },
            modifier = Modifier.width(LSpace.dockHeight * 8 + 16.dp),
            height = LSpace.dockHeight
        )
    }
}

@Composable
private fun DockBtn(
    icon: LIcon,
    label: String,
    active: Boolean,
    haptic: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    onClick: () -> Unit
) {
    val colors = LocalColors.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg by animateFloatAsState(if (active) 1f else 0f, Motion.segmented, label = "dockActive")
    Box(
        Modifier
            .size(size)
            .clip(Shapes2.M)
            .background(colors.accent.copy(alpha = 0.18f * bg))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                if (haptic) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        LIconView(
            icon,
            size = 19.dp,
            color = if (active) colors.accent else colors.text,
            strokeWidth = 1.7.dp
        )
    }
}

// ---------------------------------------------------------------------------
// 编辑工具岛
// ---------------------------------------------------------------------------

@Composable
private fun ToolIsland(
    tool: EditorTool,
    color: Long,
    inkWidth: Float,
    annotationCount: Int,
    onColor: (Long) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSignature: () -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalColors.current
    val palette = remember { listOf(0xFFFFD60A, 0xFF34C759, 0xFF0A84FF, 0xFFFF9F0A, 0xFFFF375F, 0xFFAF52DE) }

    LiquidGlass(Modifier.height(52.dp), shape = pillShape(52f)) {
        Row(
            Modifier
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when (tool) {
                    EditorTool.HIGHLIGHT -> "高亮"
                    EditorTool.INK -> "墨迹"
                    EditorTool.SHAPE -> "形状"
                    EditorTool.NOTE -> "批注"
                    EditorTool.TEXT -> "文本"
                    EditorTool.ERASER -> "橡皮"
                    else -> "编辑"
                },
                style = LType.Caption,
                color = colors.secondary
            )
            Spacer(Modifier.width(4.dp))
            palette.forEach { c ->
                val selected = c == color
                Box(
                    Modifier
                        .size(if (selected) 24.dp else 20.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onColor(c) }
                ) {
                    if (selected) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f))
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // 只有用笔的时候才显示粗细：高亮/形状改了也没意义，白白占位置
            if (tool == EditorTool.INK) {
                Row(
                    Modifier
                        .width(96.dp)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassSlider(
                        value = inkWidth,
                        onValueChange = onWidth,
                        valueRange = 0.5f..12f,
                        contentDescription = "墨迹粗细"
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            DockBtn(LIcon.Signature, "签名", false, false, 36.dp, onSignature)
            DockBtn(LIcon.Undo, "撤销", false, false, 36.dp, onUndo)
            DockBtn(LIcon.Trash, "清空", false, false, 36.dp, onClear)
            DockBtn(LIcon.Download, "导出标注", false, false, 36.dp, onExport)
            DockBtn(LIcon.Xmark, "关闭", false, false, 36.dp, onClose)
        }
    }
}

// ---------------------------------------------------------------------------
// 目录抽屉
// ---------------------------------------------------------------------------

@Composable
private fun TocPanel(
    entries: List<com.lpdf.reader.data.pdf.PdfiumEngine.TocEntry>,
    current: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalColors.current
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
                .width(300.dp)
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
                    Text("目录", style = LType.TitleM, color = colors.text, modifier = Modifier.weight(1f))
                    GlassIconButton(LIcon.Xmark, "关闭", onClose, size = 32.dp, iconSize = 14.dp, showBg = false)
                }
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该文档没有目录", style = LType.Caption, color = colors.secondary)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(entries) { _, e ->
                            val active = e.pageIndex == current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onPick(e.pageIndex) }
                                    .padding(
                                        start = 20.dp + (e.depth * 14).dp,
                                        end = 16.dp,
                                        top = 10.dp,
                                        bottom = 10.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    e.title,
                                    style = if (e.depth == 0) LType.Body else LType.Caption,
                                    color = if (active) colors.accent else colors.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${e.pageIndex + 1}", style = LType.Micro, color = colors.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
