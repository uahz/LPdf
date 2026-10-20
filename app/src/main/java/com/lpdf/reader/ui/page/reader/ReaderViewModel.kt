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
import com.lpdf.reader.data.local.ReadingMode
import com.lpdf.reader.data.pdf.PdfiumEngine
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
enum class EditorTool { NONE, HIGHLIGHT, INK, NOTE, SHAPE, TEXT, ERASER }

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
    val dirty: Boolean = false
)

/**
 * 阅读页逻辑。
 *
 * 性能约定：
 * - 页面位图按页懒加载，LRU 上限 [CACHE_LIMIT] 页
 * - 渲染请求串行（PDFium 非线程安全），只渲染当前可见 + 前后各 1 页
 * - 切页保存进度走 DataStore（防抖 800ms）
 */
class ReaderViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 已渲染页缓存：pageIndex -> Bitmap */
    private val pageCache = object : LinkedHashMap<Int, android.graphics.Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, android.graphics.Bitmap>?): Boolean {
            if (size > CACHE_LIMIT) {
                eldest?.value?.recycle()
                return true
            }
            return false
        }
    }

    private var uri: Uri = Uri.EMPTY
    private var password: String? = null
    private var docKey: String = ""
    private var annotations: AnnotationDoc? = null
    private var saveJob: Job? = null
    private var progressJob: Job? = null

    fun bind(uriString: String, displayName: String, lastModified: Long, password: String?) {
        if (docKey.isNotEmpty() && this.uri.toString() == uriString) return
        this.uri = Uri.parse(uriString)
        this.password = password
        this.docKey = container.annotations.keyOf(uriString, lastModified)
        _state.value = _state.value.copy(
            docKey = docKey,
            displayName = displayName,
            loading = true,
            error = null,
            pageCount = 0
        )
        open(displayName)
    }

    private fun open(displayName: String) {
        viewModelScope.launch {
            val countRes = PdfiumEngine.pageCount(container.resolver(), uri, password)
            when (countRes) {
                is PdfResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = countRes.error
                )

                is PdfResult.Success -> {
                    val count = countRes.value
                    val saved = withContext(AppDispatchers.io) {
                        container.prefs.progressMapSnapshot(docKey)
                    }
                    val toc = PdfiumEngine.tableOfContents(container.resolver(), uri).getOrNull() ?: emptyList()
                    val doc = container.annotations.load(docKey)
                    annotations = doc
                    _state.value = _state.value.copy(
                        loading = false,
                        pageCount = count,
                        currentPage = saved.coerceIn(0, (count - 1).coerceAtLeast(0)),
                        toc = toc,
                        annotationCount = doc.count()
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    fun bitmapFor(page: Int): android.graphics.Bitmap? {
        synchronized(pageCache) { return pageCache[page] }
    }

    /** 预渲染指定页；宽度为屏幕像素宽 */
    suspend fun ensureRendered(page: Int, widthPx: Int) {
        if (page < 0 || page >= _state.value.pageCount) return
        synchronized(pageCache) { if (pageCache.containsKey(page)) return }
        val res = PdfiumEngine.renderPage(container.resolver(), uri, page, widthPx, password)
        val bmp = res.getOrNull() ?: return
        withContext(AppDispatchers.main) {
            synchronized(pageCache) { pageCache[page] = bmp }
            // 触发重组
            _state.value = _state.value.copy(zoom = _state.value.zoom)
        }
    }

    fun onPageSettled(page: Int) {
        if (_state.value.currentPage == page) return
        _state.value = _state.value.copy(currentPage = page)
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
        synchronized(pageCache) {
            pageCache.values.forEach { it.recycle() }
            pageCache.clear()
        }
        super.onCleared()
    }

    companion object {
        private const val CACHE_LIMIT = 7
        fun newId(): String = UUID.randomUUID().toString()
    }
}
