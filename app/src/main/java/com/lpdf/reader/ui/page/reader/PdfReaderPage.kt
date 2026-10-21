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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.util.Formatters
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotRect
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.data.local.ReadingMode
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.GlassProgressRing
import com.lpdf.reader.ui.glass.GlassSnackbarHost
import com.lpdf.reader.ui.glass.GlassStateCard
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
    onClose: () -> Unit
) {
    val vm = rememberReaderViewModel(container)
    val state by vm.state.collectAsState()
    val colors = LocalColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
    }

    // 绑定文档（uri + mtime 由导航参数给出，缺失时用 0 仍可读）
    LaunchedEffect(uri) {
        vm.bind(uri, displayName, 0L, null)
        vm.setZoomLimit(settings.zoomLimit.toFloat())
    }

    // 标注扁平化导出：用 SAF 让用户选保存位置，避免申请额外存储权限
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri ->
        if (targetUri != null) vm.exportFlattened(targetUri)
    }

    // 阅读时常亮
    val activity = LocalContext.current as? android.app.Activity
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
            Box(Modifier.fillMaxSize()) {
            if (state.error != null) {
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
                            vm.flush()
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
                            widthPx = screenWidthPx,
                            maxZoom = settings.zoomLimit.toFloat(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
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
                                widthPx = screenWidthPx,
                                settings = settings,
                                highlightColor = state.highlightColor,
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
                    onBack = {
                        vm.flush()
                        onClose()
                    },
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
                    annotationCount = state.annotationCount,
                    onColor = vm::setHighlightColor,
                    onUndo = {
                        if (!vm.undo(state.currentPage)) vm.toast("没有可撤销的标注")
                    },
                    onClear = {
                        vm.clearPage(state.currentPage)
                        vm.toast("已清空本页标注")
                    },
                    onExport = { exportLauncher.launch(suggestExportName(state.displayName)) },
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
    widthPx: Int,
    settings: AppSettings,
    highlightColor: Long,
    tool: EditorTool
) {
    val bounds = with(LocalDensity.current) {
        PageBounds(
            padH = 16.dp.toPx(),
            screenW = widthPx.toFloat()
        )
    }
    val areaWidthPx = (widthPx - bounds.padH * 2).toInt().coerceAtLeast(240)
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(vm.bitmapFor(page)) }
    // 渲染失败时记下原因，避免「一直转圈却不说是错」
    var failure by remember(page) { mutableStateOf<String?>(null) }

    LaunchedEffect(page, areaWidthPx) {
        var attempts = 0
        // 会话可能还在打开中（ensureRendered 里 session 为 null 会直接返回）。
        // 轮询等它就绪，但**必须有上限** —— 原来没有上限，一旦渲染永远失败，
        // 这个圆环就永远转下去，用户看到的就是「打开 PDF 一直转圈」。
        while (bitmap == null && attempts < MAX_RENDER_RETRY) {
            attempts++
            vm.ensureRendered(page, areaWidthPx)
            bitmap = vm.bitmapFor(page)
            if (bitmap != null) break
            val err = vm.lastRenderError(page)
            if (err != null) {
                failure = err
                break
            }
            kotlinx.coroutines.delay(if (attempts < 3) 120L else 400L)
        }
        if (bitmap == null && failure == null) {
            failure = "页面渲染超时，可尝试切换页面或重新打开文档"
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(Shapes2.M),
    ) {
        if (bitmap == null) {
            // 纸张占位（避免白屏跳动）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((areaWidthPx * 1.414f).dp / 1f / LocalDensity.current.density)
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
            AnnotationLayer(
                bitmap = bitmap!!,
                annotations = vm.annotationsOf(page),
                tool = tool,
                highlightColor = highlightColor,
                searchRects = vm.hitRectsOnPage(page),
                activeSearchRects = vm.activeHitRectsOnPage(page),
                onAdd = { vm.addAnnotation(page, it) }
            )
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

private data class PageBounds(val padH: Float, val screenW: Float)

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
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(vm.bitmapFor(page)) }

    LaunchedEffect(page) {
        vm.ensureRendered(page, widthPx)
        bitmap = vm.bitmapFor(page)
    }
    val z by animateFloatAsState(zoom, Motion.standard, label = "zoom")

    Box(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                    offset = if (zoom <= 1.02f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            GlassProgressRing()
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${page + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        scaleX = z
                        scaleY = z
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

/**
 * 标注层：位图 + 归一化坐标标注 + 手指绘制。
 * 归一化坐标 0~1，与缩放无关。
 */
@Composable
private fun AnnotationLayer(
    bitmap: Bitmap,
    annotations: List<Annotation>,
    tool: EditorTool,
    highlightColor: Long,
    searchRects: List<com.lpdf.reader.data.pdf.PdfiumEngine.AnnotRectLike> = emptyList(),
    activeSearchRects: List<com.lpdf.reader.data.pdf.PdfiumEngine.AnnotRectLike> = emptyList(),
    onAdd: (Annotation) -> Unit
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val ink = remember { mutableListOf<Offset>() }

    // 签名位图缓存：路径 -> ImageBitmap。签名图不大且复用频繁，按路径记忆即可
    val signatureBitmaps = remember(bitmap, annotations) {
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

    Box(
        Modifier
            .fillMaxWidth()
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
                                        width = 2f / w,
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
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

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
    onBack: () -> Unit,
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
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = LSpace.pageHorizontal,
                end = LSpace.pageHorizontal,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
            ),
        horizontalArrangement = Arrangement.Center
    ) {
        LiquidGlass(Modifier.height(LSpace.dockHeight), shape = Shapes2.XL) {
            Row(
                Modifier
                    .height(LSpace.dockHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                DockBtn(LIcon.ChevronLeft, "上一页", false, haptic) { onPrev() }
                DockBtn(LIcon.List, "目录", tocOpen, haptic) { onToc() }
                DockBtn(LIcon.Bookmark, "书签", bookmarked, haptic) { onBookmarks() }
                DockBtn(LIcon.Search, "搜索", searchActive, haptic) { onSearch() }
                DockBtn(LIcon.HighlightPen, "高亮", tool == EditorTool.HIGHLIGHT, haptic) {
                    onTool(EditorTool.HIGHLIGHT)
                }
                DockBtn(LIcon.Pencil, "墨迹", tool == EditorTool.INK, haptic) { onTool(EditorTool.INK) }
                DockBtn(LIcon.Note, "批注", tool == EditorTool.NOTE, haptic) { onTool(EditorTool.NOTE) }
                DockBtn(LIcon.ChevronRight, "下一页", false, haptic) { onNext() }
            }
        }
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
    annotationCount: Int,
    onColor: (Long) -> Unit,
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
