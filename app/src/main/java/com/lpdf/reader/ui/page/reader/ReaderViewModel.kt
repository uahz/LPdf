package com.lpdf.reader.ui.page.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotationDoc
import com.lpdf.reader.data.local.Bookmark
import com.lpdf.reader.data.local.SignatureAsset
import com.lpdf.reader.data.local.ReadingMode
import com.lpdf.reader.data.pdf.PageRender
import com.lpdf.reader.data.pdf.PdfFlattener
import com.lpdf.reader.data.pdf.PdfiumEngine
import com.lpdf.reader.data.pdf.Tile
import com.lpdf.reader.data.pdf.TilePlan
import com.lpdf.reader.ui.glass.GlassToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 编辑工具 */
enum class EditorTool { NONE, HIGHLIGHT, INK, NOTE, SHAPE, TEXT, ERASER, SIGNATURE }

/** 阅读页状态 */
data class ReaderUiState(
    val docKey: String = "",
    val displayName: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val loading: Boolean = true,
    val error: PdfError? = null,
    val toc: List<PdfiumEngine.TocEntry> = emptyList(),
    val tocOpen: Boolean = false,
    val chromeVisible: Boolean = true,
    val tool: EditorTool = EditorTool.NONE,
    val toolIslandOpen: Boolean = false,
    val annotationCount: Int = 0,
    val toast: GlassToast? = null,
    val zoom: Float = 1f,
    val highlightColor: Long = 0xFFFFD60A,
    val editing: Boolean = false,
    val dirty: Boolean = false,

    // ---- v1.3 书签 ----
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarksOpen: Boolean = false,
    /** 当前页是否已加书签 */
    val currentPageBookmarked: Boolean = false,

    // ---- v1.3 全文搜索 ----
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<PdfiumEngine.SearchHit> = emptyList(),
    val searching: Boolean = false,
    val searchProgress: Pair<Int, Int>? = null,
    /** 当前聚焦的命中下标，-1 表示无 */
    val activeHit: Int = -1,
    val matchCase: Boolean = false,

    // ---- v1.3 签名 ----
    val signatureOpen: Boolean = false
)

/**
 * 阅读页逻辑。
 *
 * 性能约定：
 * - 页面位图按页懒加载，LRU 上限 [CACHE_LIMIT] 页 **且** 总字节不超过 [CACHE_MAX_BYTES]
 * - 渲染请求串行（PDFium 非线程安全），只渲染当前可见 + 前后各 1 页
 * - 切页保存进度走 DataStore（防抖 800ms）
 */
class ReaderViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /**
     * 已渲染页缓存：pageIndex -> [PageRender]（v1.5.0 起是一组 tile）。
     *
     * ## 为什么从 Bitmap 换成 PageRender
     *
     * GPU 单边纹理上限 8192，PDF 里的超长页（CAD 图、海报、地图导出）
     * 按屏幕宽缩放后高度动辄上万，整页塞一张位图在物理上不可能。
     * 现在一页 = 若干块，每块独立满足纹理上限，绘制时在同一个 Canvas 里拼。
     *
     * ## 淘汰策略
     *
     * 双条件：**页数** 与 **字节数**，谁先超谁触发淘汰。
     *
     * 页数上限从 7 降到 3：长页每页最多 4 块，7 页 × 4 块 = 28 块，
     * 最坏情况 28 × 64MB 直接把 heap 打穿。3 页（当前 + 前后各 1）正好
     * 覆盖懒加载窗口，再多的页面也算不到收益。
     *
     * 字节上限取 heap 的 1/4：既给 Bitmap 留足空间，又不至于把
     * 应用逼到 GC 抖动的边缘。
     */
    private val pageCache = object : LinkedHashMap<Int, PageRender>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageRender>?): Boolean {
            if (size <= 1) return false
            val overCount = size > CACHE_LIMIT
            val overBytes = cachedBytes > CACHE_MAX_BYTES
            if (overCount || overBytes) {
                eldest?.value?.let { r ->
                    r.tiles.forEach { t -> runCatching { t.bitmap.recycle() } }
                    cachedBytes -= r.bytes
                }
                return true
            }
            return false
        }
    }

    /** pageCache 当前占用字节数（随插入/淘汰维护，避免每次遍历求和） */
    private var cachedBytes = 0L

    /** 渲染失败页 -> 原因。换文档时清空。 */
    private val renderErrors = mutableMapOf<Int, String>()

    /** 单次可用的位图缓存预算：堆的 1/4，下限 32MB 上限 192MB */
    private val CACHE_MAX_BYTES: Long = run {
        val heap = Runtime.getRuntime().maxMemory()
        (heap / 4).coerceIn(32L * 1024 * 1024, 192L * 1024 * 1024)
    }

    /** 插入/覆盖缓存并维护字节计数 */
    private fun putRender(r: PageRender) {
        synchronized(pageCache) {
            pageCache.remove(r.page)?.let { old ->
                old.tiles.forEach { t -> runCatching { t.bitmap.recycle() } }
                cachedBytes -= old.bytes
            }
            pageCache[r.page] = r
            cachedBytes += r.bytes
        }
    }

    /**
     * 合并一块新渲染的 tile 进缓存。
     *
     * 逐块回调的价值在这里：4 块的长页不必等 4 块全好，
     * 第一块一到就能显示，用户看到的是「页面从上往下长出来」，
     * 而不是一个转很久的圈。
     */
    private fun appendTile(page: Int, tile: Tile, plan: TilePlan) {
        synchronized(pageCache) {
            val cur = pageCache[page]
            val updated = if (cur == null) {
                PageRender(
                    page = page,
                    widthPx = plan.widthPx,
                    heightPx = plan.heightPx,
                    degraded = plan.degraded,
                    tiles = listOf(tile),
                    tileCount = plan.tileCount
                )
            } else {
                // 同 index 的旧块（重复渲染）先回收，避免泄漏
                val merged = cur.tiles.filterNot { it.index == tile.index } + tile
                cur.copy(
                    tiles = merged.sortedBy { it.index },
                    tileCount = plan.tileCount,
                    degraded = plan.degraded
                )
            }
            // 先扣掉旧页占用再覆盖，否则字节计数会重复累加
            cur?.let { cachedBytes -= it.bytes }
            pageCache[page] = updated
            cachedBytes += updated.bytes
        }
    }

    /** 清空缓存并回收全部位图（切换文档时调用） */
    private fun clearPageCache() {
        synchronized(pageCache) {
            pageCache.values.forEach { r -> r.tiles.forEach { t -> runCatching { t.bitmap.recycle() } } }
            pageCache.clear()
            cachedBytes = 0L
        }
        synchronized(renderErrors) { renderErrors.clear() }
    }

    private var uri: Uri = Uri.EMPTY
    private var password: String? = null
    private var docKey: String = ""
    private var annotations: AnnotationDoc? = null
    private var saveJob: Job? = null
    private var progressJob: Job? = null

    /**
     * 打开文档的协程。
     *
     * 原来 `open()` 直接 `viewModelScope.launch` 就没人管了：连续点两个文档时，
     * 两次打开并发跑，谁后返回谁写 state —— 后点开的那个反而可能被先点开的覆盖，
     * 出现「点了 A 却显示 B」。现在新一次 `bind()` 先取消上一次。
     */
    private var openJob: Job? = null

    /**
     * 当前文档的常驻渲染会话。
     *
     * 生命周期跟着 ViewModel：换文档时关旧的，`onCleared` 时关最后一个。
     * 这个字段是阅读页「翻页不再转圈」的关键 —— 文档只打开一次。
     */
    @Volatile
    private var sessionRef: PdfiumEngine.DocSession? = null

    fun bind(uriString: String, displayName: String, lastModified: Long, password: String?) {
        if (docKey.isNotEmpty() && this.uri.toString() == uriString) return
        // 上一个文档的打开流程还没结束时必须掐掉，否则它晚一步返回会把
        // state 覆盖成旧文档的页数/目录。
        openJob?.cancel()
        // 换了文档：上一个文档的位图必须全部释放。
        // 原来这里不清，缓存里留着旧页，既浪费内存又可能在新文档里
        // 命中旧页号而显示成别的文档内容。
        clearPageCache()
        this.uri = Uri.parse(uriString)
        this.password = password
        this.docKey = container.annotations.keyOf(uriString, lastModified)
        _state.value = _state.value.copy(
            docKey = docKey,
            displayName = displayName,
            loading = true,
            error = null,
            pageCount = 0,
            currentPage = 0,
            toc = emptyList(),
            bookmarks = emptyList(),
            currentPageBookmarked = false,
            annotationCount = 0
        )
        open(displayName)
    }

    private fun open(displayName: String) {
        openJob = viewModelScope.launch {
            // 先建常驻会话：打开一次、后续所有翻页共用同一个 native 文档。
            // 原来每渲染一页都 openFileDescriptor + newDocument，399 页的书
            // 首次滚动会卡出「一直转圈」。会话失败再回落到只读页数。
            val sessionRes = PdfiumEngine.openSession(container.resolver(), uri, password)
            when (sessionRes) {
                is PdfResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = sessionRes.error
                )

                is PdfResult.Success -> {
                    val session = sessionRes.value
                    sessionRef?.let { runCatching { it.close() } }
                    sessionRef = session
                    val count = session.pageCount
                    val saved = withContext(AppDispatchers.io) {
                        container.prefs.progressMapSnapshot(docKey)
                    }
                    // 目录也走会话，不再单独开一次文档
                    val toc = session.toc()
                    val doc = container.annotations.load(docKey)
                    annotations = doc
                    _state.value = _state.value.copy(
                        loading = false,
                        pageCount = count,
                        currentPage = saved.coerceIn(0, (count - 1).coerceAtLeast(0)),
                        toc = toc,
                        annotationCount = doc.count()
                    )
                    reloadBookmarks()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    /** 已渲染的页（可能只到了一部分 tile）；无则 null */
    fun renderFor(page: Int): PageRender? {
        synchronized(pageCache) { return pageCache[page] }
    }

    /**
     * 确保某页已完整渲染（v1.5.0 分块版）。
     *
     * 走常驻会话：先 `plan` 拿几何（顺带知道真实宽高比与要切几块），
     * 再 `renderTiles` 逐块回调。每块到达就写进缓存并触发一次重组，
     * 所以 UI 上看到的是逐块出现，而不是「大页永远转圈」。
     *
     * 会话还不可用（正在打开）时直接返回，调用方下一帧会重试。
     */
    suspend fun ensureRendered(page: Int, widthPx: Int) {
        if (page < 0 || page >= _state.value.pageCount) return
        // 目标宽度归一化：渲染宽上限之内的请求才算「同一张图」。
        // v1.5.1：渲染宽度改为实测内容区宽度后，它会随窗口尺寸/旋转/分屏变化。
        // 如果沿用「已完整就 return」，用户横屏或拉伸窗口后页面仍是旧的低分辨率
        // 版本 —— 看着糊，却再也刷不新。这里把已渲染宽度一并纳入判断。
        val wanted = widthPx.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH)
        synchronized(pageCache) {
            val cached = pageCache[page]
            // 宽度差 < 10% 视为等效，避免窗口微抖动时反复重渲
            if (cached?.isComplete == true && cached.widthPx >= (wanted * 0.9f).toInt()) return
        }
        // 已经失败过的页不要反复重试：PDFium 对同一页的失败是确定性的，
        // 重试只会白白占着渲染锁，让其它页排不上队。
        synchronized(renderErrors) { if (renderErrors.containsKey(page)) return }
        val session = sessionRef ?: return

        // ① 几何：拿到真实宽高比 + 分块方案
        val planRes = session.plan(page, widthPx)
        if (planRes is PdfResult.Failure) {
            synchronized(renderErrors) {
                renderErrors[page] = planRes.error.message ?: "页面尺寸读取失败"
            }
            return
        }
        val plan = planRes.getOrNull() ?: return

        // ② 先把「占位页」放进缓存：UI 立刻拿到真实宽高比，
        //    列表不会再从 A4 比例跳到真实比例（那种跳动比转圈更让人困惑）
        synchronized(pageCache) {
            val cur = pageCache[page]
            if (cur == null || cur.tiles.isEmpty()) {
                putRender(
                    PageRender(
                        page = page,
                        widthPx = plan.widthPx,
                        heightPx = plan.heightPx,
                        degraded = plan.degraded,
                        tiles = emptyList(),
                        tileCount = plan.tileCount
                    )
                )
            }
        }
        withContext(AppDispatchers.main) {
            _state.value = _state.value.copy(zoom = _state.value.zoom)
        }

        // ③ 逐块渲染
        var firstFailure: String? = null
        val res = session.renderTiles(page, plan) { tile ->
            if (firstFailure == null) {
                withContext(AppDispatchers.main) {
                    appendTile(page, tile, plan)
                    _state.value = _state.value.copy(zoom = _state.value.zoom)
                }
            }
        }
        if (res is PdfResult.Failure) {
            firstFailure = res.error.message ?: "页面渲染失败"
        }
        if (firstFailure != null) {
            // 一块都没成功才算失败；已渲染的部分保留，用户至少能看上半页
            val any = synchronized(pageCache) { pageCache[page]?.tiles?.isNotEmpty() == true }
            if (!any) {
                synchronized(renderErrors) { renderErrors[page] = firstFailure!! }
                withContext(AppDispatchers.main) {
                    _state.value = _state.value.copy(zoom = _state.value.zoom)
                }
            }
        }
    }

    /** 某页的渲染失败原因；未失败返回 null。UI 据此把转圈换成可读提示。 */
    fun lastRenderError(page: Int): String? = synchronized(renderErrors) { renderErrors[page] }

    fun onPageSettled(page: Int) {
        if (_state.value.currentPage == page) return
        val bm = _state.value.bookmarks
        _state.value = _state.value.copy(
            currentPage = page,
            currentPageBookmarked = bm.any { it.page == page }
        )
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(800)
            container.prefs.saveProgress(docKey, page)
        }
    }

    // ---------------------------------------------------------------- UI 开关

    fun toggleChrome() = _state.value.let { _state.value = it.copy(chromeVisible = !it.chromeVisible) }
    fun setChrome(visible: Boolean) = _state.value.let { _state.value = it.copy(chromeVisible = visible) }
    fun setZoom(z: Float) = _state.value.let { _state.value = it.copy(zoom = z.coerceIn(1f, zoomLimit)) }

    private var zoomLimit = 5f
    fun setZoomLimit(limit: Float) {
        zoomLimit = limit.coerceIn(1f, 8f)
    }
    fun toggleToc() = _state.value.let { _state.value = it.copy(tocOpen = !it.tocOpen) }
    fun closeToc() = _state.value.let { _state.value = it.copy(tocOpen = false) }
    fun toggleToolIsland() = _state.value.let { _state.value = it.copy(toolIslandOpen = !it.toolIslandOpen) }
    fun closeToolIsland() = _state.value.let { _state.value = it.copy(toolIslandOpen = false) }
    fun setTool(tool: EditorTool) = _state.value.let { _state.value = it.copy(tool = tool) }
    fun setHighlightColor(color: Long) = _state.value.let { _state.value = it.copy(highlightColor = color) }

    // ---------------------------------------------------------------- 标注

    fun annotationsOf(page: Int): List<Annotation> = annotations?.pages?.get(page)?.toList() ?: emptyList()

    fun addAnnotation(page: Int, a: Annotation) {
        val doc = annotations ?: return
        doc.pages.getOrPut(page) { mutableListOf() }.add(a)
        _state.value = _state.value.copy(annotationCount = doc.count(), dirty = true)
        scheduleSave()
    }

    fun undo(page: Int): Boolean {
        val doc = annotations ?: return false
        val list = doc.pages[page] ?: return false
        if (list.isEmpty()) return false
        list.removeAt(list.size - 1)
        _state.value = _state.value.copy(annotationCount = doc.count(), dirty = true)
        scheduleSave()
        return true
    }

    fun clearPage(page: Int) {
        val doc = annotations ?: return
        doc.pages[page]?.clear()
        _state.value = _state.value.copy(annotationCount = doc.count(), dirty = true)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val doc = annotations ?: return@launch
            withContext(AppDispatchers.io) { container.annotations.save(doc) }
            _state.value = _state.value.copy(dirty = false)
        }
    }

    fun flush() {
        val doc = annotations ?: return
        viewModelScope.launch(AppDispatchers.io) { container.annotations.save(doc) }
    }

    // ---------------------------------------------------------------- 标注扁平化导出

    /** 导出进度：null 表示空闲 */
    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    /**
     * 把当前所有标注烧录进 PDF 并写到 [targetUri]（由 SAF 创建，调用方已取得写权限）。
     *
     * 原 PDF 不做任何修改；标注 JSON 也保留，导出属于「另存一份带标注的副本」。
     */
    fun exportFlattened(targetUri: Uri) {
        val doc = annotations ?: run {
            toast("文档尚未就绪")
            return
        }
        if (doc.isEmpty()) {
            toast("当前没有任何标注可导出")
            return
        }
        if (_exportProgress.value != null) return

        viewModelScope.launch {
            _exportProgress.value = 0f
            val pageCount = _state.value.pageCount
            val cacheDir = container.appContext().cacheDir
            val tmp = java.io.File(cacheDir, "flat/${doc.docKey}-${System.currentTimeMillis()}.pdf")
            tmp.parentFile?.mkdirs()

            val res = PdfFlattener.export(
                resolver = container.resolver(),
                sourceUri = uri,
                doc = doc,
                outFile = tmp,
                pageCount = pageCount,
                password = password,
                progress = { p -> _exportProgress.value = p }
            )

            when (res) {
                is PdfResult.Failure -> {
                    // 失败时临时文件同样要删。原来只删成功分支，
                    // 反复导出失败会在 cache 里堆一批几十 MB 的 PDF。
                    runCatching { tmp.delete() }
                    _exportProgress.value = null
                    toast(res.error.message ?: "导出失败")
                }

                is PdfResult.Success -> {
                    // 拷到用户选定位置
                    val copied = withContext(AppDispatchers.io) {
                        runCatching {
                            container.resolver().openOutputStream(targetUri, "w")?.use { out ->
                                res.value.inputStream().use { it.copyTo(out, 64 * 1024) }
                            } ?: throw java.io.IOException("无法写入目标位置")
                        }
                    }
                    runCatching { res.value.delete() }
                    _exportProgress.value = null
                    if (copied.isSuccess) {
                        toast("已导出带标注的 PDF（${doc.count()} 处标注）")
                    } else {
                        toast("保存失败：${copied.exceptionOrNull()?.message ?: "未知错误"}")
                    }
                }
            }
        }
    }

    fun cancelExport() {
        _exportProgress.value = null
    }

    // ---------------------------------------------------------------- 签名（v1.3）

    /** 签名库列表（面板展示用） */
    private val _signatures = MutableStateFlow<List<SignatureAsset>>(emptyList())
    val signatures: StateFlow<List<SignatureAsset>> = _signatures.asStateFlow()

    fun loadSignatures() {
        viewModelScope.launch {
            _signatures.value = withContext(AppDispatchers.io) { container.signatures.list() }
        }
    }

    /**
     * 把签名贴到当前页。
     *
     * 落位规则：水平居中、纵向放在页面偏下（常见签署位置）。
     * 宽占页面 34%，高度按图片宽高比推算 —— 直接给固定高宽比会把长签名压扁。
     */
    private fun placeSignature(asset: SignatureAsset) {
        val page = _state.value.currentPage
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(asset.path) }.getOrNull()
        val ratio = if (bmp != null && bmp.width > 0) {
            bmp.height.toFloat() / bmp.width.toFloat()
        } else {
            0.4f
        }
        bmp?.recycle()

        val w = 0.34f
        val h = (w * ratio).coerceIn(0.04f, 0.4f)
        val left = (1f - w) / 2f
        val top = (0.72f - h / 2f).coerceIn(0.05f, 0.92f)

        addAnnotation(
            page,
            Annotation.Signature(
                id = newId(),
                bounds = com.lpdf.reader.data.local.AnnotRect(left, top, left + w, top + h),
                bitmapPath = asset.path
            )
        )
        toast("签名已贴到第 ${page + 1} 页")
    }

    /** 手写签名：先入库，再贴到当前页，保证下次能复用 */
    fun saveAndPlaceSignature(bitmap: android.graphics.Bitmap, name: String) {
        viewModelScope.launch {
            val cropped = withContext(AppDispatchers.io) {
                com.lpdf.reader.data.local.cropSignature(bitmap)
            }
            val asset = withContext(AppDispatchers.io) {
                container.signatures.save(cropped, name)
            }
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()

            if (asset == null) {
                toast("签名保存失败")
                return@launch
            }
            loadSignatures()
            placeSignature(asset)
        }
    }

    fun pickSignature(asset: SignatureAsset) = placeSignature(asset)

    fun deleteSignature(id: String) {
        viewModelScope.launch {
            withContext(AppDispatchers.io) { container.signatures.delete(id) }
            loadSignatures()
            toast("已删除签名")
        }
    }

    fun toggleSignaturePanel() {
        val open = !_state.value.signatureOpen
        _state.value = _state.value.copy(
            signatureOpen = open,
            tool = if (open) EditorTool.SIGNATURE else EditorTool.NONE
        )
        if (open) loadSignatures()
    }

    fun closeSignaturePanel() {
        _state.value = _state.value.copy(signatureOpen = false)
        if (_state.value.tool == EditorTool.SIGNATURE) {
            _state.value = _state.value.copy(tool = EditorTool.NONE)
        }
    }

    // ---------------------------------------------------------------- 书签（v1.3）

    private fun reloadBookmarks() {
        if (docKey.isEmpty()) return
        viewModelScope.launch {
            val list = withContext(AppDispatchers.io) { container.bookmarks.load(docKey) }
            val cur = _state.value.currentPage
            _state.value = _state.value.copy(
                bookmarks = list,
                currentPageBookmarked = list.any { it.page == cur }
            )
        }
    }

    fun toggleBookmarksPanel() = _state.value.let {
        _state.value = it.copy(bookmarksOpen = !it.bookmarksOpen, tocOpen = false)
    }

    fun closeBookmarks() = _state.value.let { _state.value = it.copy(bookmarksOpen = false) }

    /** 当前页加/取消书签；无标题时自动命名为「第 N 页」 */
    fun toggleBookmarkOnCurrentPage(label: String? = null) {
        val s = _state.value
        if (docKey.isEmpty()) return
        val page = s.currentPage
        val existing = s.bookmarks.firstOrNull { it.page == page }

        viewModelScope.launch {
            val next = if (existing != null) {
                s.bookmarks.filterNot { it.id == existing.id }
            } else {
                s.bookmarks + Bookmark(
                    id = newId(),
                    page = page,
                    label = label?.takeIf { it.isNotBlank() } ?: "第 ${page + 1} 页",
                    createdAt = System.currentTimeMillis()
                )
            }
            withContext(AppDispatchers.io) { container.bookmarks.save(docKey, next) }
            _state.value = _state.value.copy(
                bookmarks = next.sortedBy { it.page },
                currentPageBookmarked = existing == null
            )
            toast(if (existing != null) "已移除书签" else "已添加书签")
        }
    }

    fun renameBookmark(id: String, label: String) {
        val s = _state.value
        if (label.isBlank()) return
        viewModelScope.launch {
            val next = s.bookmarks.map { if (it.id == id) it.copy(label = label.trim()) else it }
            withContext(AppDispatchers.io) { container.bookmarks.save(docKey, next) }
            _state.value = _state.value.copy(bookmarks = next.sortedBy { it.page })
        }
    }

    fun removeBookmark(id: String) {
        val s = _state.value
        viewModelScope.launch {
            val next = s.bookmarks.filterNot { it.id == id }
            withContext(AppDispatchers.io) { container.bookmarks.save(docKey, next) }
            _state.value = _state.value.copy(
                bookmarks = next,
                currentPageBookmarked = next.any { it.page == s.currentPage }
            )
            toast("已删除书签")
        }
    }

    // ---------------------------------------------------------------- 全文搜索（v1.3）

    private var searchJob: Job? = null

    fun openSearch() = _state.value.let {
        _state.value = it.copy(searchOpen = true, tocOpen = false, bookmarksOpen = false)
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchOpen = false,
            searchHits = emptyList(),
            activeHit = -1,
            searching = false,
            searchProgress = null
        )
    }

    fun setSearchQuery(q: String) = _state.value.let { _state.value = it.copy(searchQuery = q) }

    fun toggleMatchCase() = _state.value.let {
        _state.value = it.copy(matchCase = !it.matchCase)
        if (it.searchQuery.isNotBlank()) runSearch(it.searchQuery)
    }

    /** 执行搜索。结果按页升序、页内按字符序，便于「下一处」线性推进 */
    fun runSearch(query: String) {
        val s = _state.value
        if (query.isBlank() || docKey.isEmpty()) {
            _state.value = s.copy(searchHits = emptyList(), activeHit = -1)
            return
        }
        searchJob?.cancel()
        _state.value = s.copy(
            searchQuery = query,
            searching = true,
            searchProgress = 0 to s.pageCount,
            searchHits = emptyList(),
            activeHit = -1
        )
        searchJob = viewModelScope.launch {
            val res = PdfiumEngine.search(
                resolver = container.resolver(),
                uri = uri,
                query = query.trim(),
                password = password,
                matchCase = _state.value.matchCase,
                onProgress = { scanned, total ->
                    _state.value = _state.value.copy(searchProgress = scanned to total)
                }
            )
            when (res) {
                is PdfResult.Failure -> {
                    _state.value = _state.value.copy(searching = false, searchProgress = null)
                    toast(res.error.message ?: "搜索失败")
                }

                is PdfResult.Success -> {
                    val hits = res.value.sortedWith(
                        compareBy({ it.page }, { it.charIndex })
                    )
                    _state.value = _state.value.copy(
                        searchHits = hits,
                        searching = false,
                        searchProgress = null,
                        activeHit = if (hits.isEmpty()) -1 else 0
                    )
                    if (hits.isEmpty()) toast("没有找到「$query」")
                }
            }
        }
    }

    /** 下一处 / 上一处。返回目标页供 UI 滚动 */
    fun stepHit(forward: Boolean): Int? {
        val s = _state.value
        val hits = s.searchHits
        if (hits.isEmpty()) return null
        val cur = s.activeHit
        // 首次点击时，从当前阅读页开始找最近的一处，而不是粗暴跳到第 0 条
        val next = if (cur < 0) {
            hits.indexOfFirst { it.page >= s.currentPage }.takeIf { it >= 0 } ?: 0
        } else {
            if (forward) (cur + 1) % hits.size else (cur - 1 + hits.size) % hits.size
        }
        _state.value = s.copy(activeHit = next)
        return hits[next].page
    }

    /** 点击某条结果直接跳转 */
    fun jumpToHit(index: Int): Int? {
        val hits = _state.value.searchHits
        if (index !in hits.indices) return null
        _state.value = _state.value.copy(activeHit = index)
        return hits[index].page
    }

    /** 当前页上的命中矩形（用于叠加高亮） */
    fun hitRectsOnPage(page: Int): List<PdfiumEngine.AnnotRectLike> {
        val s = _state.value
        if (s.searchHits.isEmpty()) return emptyList()
        return s.searchHits.filter { it.page == page }.flatMap { it.rects }
    }

    /** 当前聚焦命中的唯一矩形（用强调色区分），无则为空 */
    fun activeHitRectsOnPage(page: Int): List<PdfiumEngine.AnnotRectLike> {
        val s = _state.value
        val idx = s.activeHit
        if (idx !in s.searchHits.indices) return emptyList()
        val hit = s.searchHits[idx]
        return if (hit.page == page) hit.rects else emptyList()
    }

    fun toast(text: String) {
        val t = GlassToast(text = text)
        _state.value = _state.value.copy(toast = t)
        viewModelScope.launch {
            delay(2800)
            if (_state.value.toast?.id == t.id) _state.value = _state.value.copy(toast = null)
        }
    }

    fun dismissToast() = _state.value.let { _state.value = it.copy(toast = null) }

    override fun onCleared() {
        flush()
        clearPageCache()
        // 会话持有 native 文档与文件描述符，ViewModel 销毁时必须显式关闭，
        // 否则每进出一次阅读页就漏一个 PdfDocument。
        // onCleared 不能挂起，这里用 runBlocking 等关闭完成 —— 关闭本身很快，
        // 但必须确保 native 资源真的释放了。
        val session = sessionRef
        sessionRef = null
        if (session != null) {
            runCatching { kotlinx.coroutines.runBlocking { session.close() } }
        }
        super.onCleared()
    }

    companion object {
        /**
         * 缓存页数上限。
         *
         * v1.5.0 从 7 降到 3：分块后一页最多 4 块位图，
         * 7 页 × 4 块 × 64MB 的极端情况会直接把 heap 打穿。
         * 3 页（当前 + 前后各 1）刚好覆盖懒加载窗口。
         */
        private const val CACHE_LIMIT = 3
        fun newId(): String = UUID.randomUUID().toString()
    }
}
