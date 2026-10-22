package com.lpdf.reader.ui.page.list

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfHealth
import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.core.model.PdfSource
import com.lpdf.reader.data.local.ListFilter
import com.lpdf.reader.data.local.SortOrder
import com.lpdf.reader.data.pdf.PdfiumEngine
import com.lpdf.reader.ui.glass.GlassToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 列表页状态。
 */
data class ListUiState(
    val items: List<PdfItem> = emptyList(),
    val query: String = "",
    val searchActive: Boolean = false,
    val loading: Boolean = false,
    val permissionDenied: Boolean = false,
    val selected: PdfItem? = null,
    val toast: GlassToast? = null,
    val progressText: String? = null,
    val passwordRequest: PdfItem? = null,
    val passwordError: Boolean = false,
    val sortOrder: SortOrder = SortOrder.MODIFIED_DESC,
    val listFilter: ListFilter = ListFilter.ALL,
    val annotatedIds: Set<String> = emptySet(),
    /** id -> 缩略图文件绝对路径（异步补齐，用于列表渲染） */
    val thumbs: Map<String, String> = emptyMap(),
    /** 已授权目录数量 */
    val treeCount: Int = 0,
    /** 请求列表页弹出系统文件选择器（权限失效后让用户重新挑文件） */
    val reselectRequest: Boolean = false
) {
    val visibleItems: List<PdfItem>
        get() {
            val base = when (listFilter) {
                ListFilter.ALL -> items
                ListFilter.RECENT -> items.filter {
                    it.lastModified > System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                }
                ListFilter.ANNOTATED -> items.filter { annotatedIds.contains(it.id) }
                ListFilter.ENCRYPTED -> items.filter { it.isEncrypted }
                ListFilter.SAF -> items.filter { it.source == PdfSource.SAF_DIR }
            }
            val q = if (query.isBlank()) base else base.filter {
                it.displayName.contains(query, ignoreCase = true)
            }
            return when (sortOrder) {
                SortOrder.MODIFIED_DESC -> q.sortedByDescending { it.lastModified }
                SortOrder.NAME_ASC -> q.sortedBy { it.displayName.lowercase() }
                SortOrder.SIZE_DESC -> q.sortedByDescending { it.sizeBytes }
                SortOrder.RECENT -> q
            }
        }

    val totalPages: Int get() = items.sumOf { it.pageCount ?: 0 }
}

/**
 * 列表页逻辑：扫描 → 校验 → 缩略图 → 排序筛选。
 *
 * 线程约定：
 * - 扫描 / 渲染 / 采样一律 IO
 * - 状态写入一律 Main（保证 Compose 快照一致）
 */
class ListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private val thumbSemaphore = Semaphore(2)
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val trees = MutableStateFlow<Set<String>>(emptySet())

    /** enrich 已排队集合，避免重复渲染同一文件 */
    private val enrichQueued = HashSet<String>()

    init {
        viewModelScope.launch {
            container.prefs.hiddenIds.collect { h ->
                hidden.value = h
                _state.value = _state.value.copy(items = _state.value.items.filterNot { h.contains(it.id) })
            }
        }
        viewModelScope.launch {
            container.prefs.safTrees.collect { t ->
                trees.value = t
                _state.value = _state.value.copy(treeCount = t.size)
            }
        }
        viewModelScope.launch {
            container.prefs.settings.collect { s ->
                _state.value = _state.value.copy(sortOrder = s.sortOrder, listFilter = s.listFilter)
            }
        }
        viewModelScope.launch {
            // 标注过的文件 id（用于「已标注」筛选）
            container.annotations.allKeysFlow().collect { keys ->
                _state.value = _state.value.copy(annotatedIds = keys)
            }
        }
    }

    // ---------------------------------------------------------------- 扫描

    fun scan() {
        scanJob?.cancel()
        enrichQueued.clear()
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, items = emptyList(), thumbs = emptyMap())
            val collected = LinkedHashMap<String, PdfItem>()
            try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    container.mediaStore.scan { batch ->
                        batch.forEach { collected[it.id] = it }
                        // scan 的回调是非挂起 lambda，主线程写状态需显式开协程
                        viewModelScope.launch { publish(collected.values.toList()) }
                    }
                }
                // SAF 目录
                trees.value.forEach { tree ->
                    val list = container.mediaStore.scanTree(Uri.parse(tree))
                    list.forEach { collected[it.id] = it }
                    publish(collected.values.toList())
                }
                // 在线导入的本地文件
                container.linkImporter.listImported().forEach { collected[it.id] = it }
                _state.value = _state.value.copy(permissionDenied = false)
            } catch (e: SecurityException) {
                _state.value = _state.value.copy(permissionDenied = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("扫描失败，请重试")
            } finally {
                val all = sortList(collected.values.toList())
                _state.value = _state.value.copy(items = all, loading = false)
                all.take(MAX_ENRICH).forEach { enrich(it) }
            }
        }
    }

    private suspend fun publish(list: List<PdfItem>) {
        withContext(AppDispatchers.main) {
            _state.value = _state.value.copy(items = sortList(list))
        }
    }

    // ---------------------------------------------------------------- 缩略图 / 页数

    /**
     * 缩略图预生成队列（v1.7.0）。
     *
     * 首屏只 enrich 可见的几项，往下一滑就是一片灰块，每张要等 100~300ms。
     * 这里把「接下来会出现的几项」提前排进去，串行生成。
     */
    private val prewarmer by lazy {
        com.lpdf.reader.data.local.ThumbnailPrewarmer(
            context = container.appContext(),
            store = container.thumbnails,
            onReady = { id, path, color ->
                if (id == null || path == null) return@ThumbnailPrewarmer
                withContext(AppDispatchers.main) {
                    val thumbMap = _state.value.thumbs.toMutableMap()
                    thumbMap[id] = path
                    _state.value = _state.value.copy(
                        items = _state.value.items.map { it2 ->
                            if (it2.id == id) it2.copy(highlightColor = color ?: it2.highlightColor)
                            else it2
                        },
                        thumbs = thumbMap
                    )
                }
                Unit
            }
        )
    }

    /**
     * 预热一批缩略图。调用方（列表页）传入当前可见项之后的那几项。
     *
     * 已经生成过的项在预生成器内部被去重，所以重复调用不会重复渲染 ——
     * 滚动时每帧都会回调可见范围，这里不设防会瞬间排爆队列。
     */
    fun prewarm(items: List<com.lpdf.reader.core.model.PdfItem>) {
        if (items.isEmpty()) return
        prewarmer.enqueue(
            items.filter { !container.thumbnails.has(container.thumbnails.cacheKey(it.id, it.lastModified)) }
                .map {
                    com.lpdf.reader.data.local.ThumbnailPrewarmer.Task(
                        id = it.id,
                        uri = it.uri,
                        lastModified = it.lastModified
                    )
                }
        )
    }

    override fun onCleared() {
        prewarmer.cancel()
        super.onCleared()
    }

    private fun enrich(item: PdfItem) {
        synchronized(enrichQueued) {
            if (!enrichQueued.add(item.id)) return
        }
        viewModelScope.launch(AppDispatchers.io) {
            thumbSemaphore.withPermit {
                val key = container.thumbnails.cacheKey(item.id, item.lastModified)
                if (!container.thumbnails.has(key)) {
                    val res = PdfiumEngine.renderPage(
                        container.resolver(), Uri.parse(item.uri), 0, THUMB_WIDTH
                    )
                    res.getOrNull()?.let { bmp ->
                        container.thumbnails.write(key, bmp)
                        bmp.recycle()
                    }
                }
                val color = container.thumbnails.sampleColor(key)
                val count = PdfiumEngine.pageCount(container.resolver(), Uri.parse(item.uri)).getOrNull()
                val path = container.thumbnails.file(key).takeIf { it.exists() }?.absolutePath
                withContext(AppDispatchers.main) {
                    val thumbMap = _state.value.thumbs.toMutableMap()
                    if (path != null) thumbMap[item.id] = path
                    _state.value = _state.value.copy(
                        items = _state.value.items.map {
                            if (it.id == item.id) it.copy(
                                highlightColor = color,
                                pageCount = count,
                                health = if (it.health == PdfHealth.BROKEN) it.health else PdfHealth.OK
                            ) else it
                        },
                        thumbs = thumbMap
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 选择 / 打开

    fun select(item: PdfItem) {
        _state.value = _state.value.copy(selected = item)
    }

    fun dismissSelection() {
        _state.value = _state.value.copy(selected = null)
    }

    /**
     * 打开前轻量校验：
     * - 损坏 → 玻璃提示，并把该行标记为 BROKEN
     * - 加密 → 弹出玻璃密码面板
     * - 正常 → 回调进入阅读页
     */
    fun requestOpen(item: PdfItem, onReady: (PdfItem, String?) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(progressText = "正在打开…")
            val result = withTimeoutOrNull(VALIDATE_TIMEOUT_MS) {
                container.validator.validate(Uri.parse(item.uri))
            }
            _state.value = _state.value.copy(progressText = null)
            when {
                result == null -> {
                    markBroken(item)
                    toast("「${item.displayName}」无响应，可能已损坏")
                }

                result is PdfResult.Success -> {
                    markOk(item, result.value)
                    onReady(item, null)
                }

                result is PdfResult.Failure -> when (val err = result.error) {
                    is PdfError.PasswordRequired, is PdfError.WrongPassword ->
                        _state.value = _state.value.copy(passwordRequest = item, passwordError = false)

                    is PdfError.PermissionDenied ->
                        _state.value = _state.value.copy(permissionDenied = true)

                    is PdfError.NotFound -> {
                        // 授权已失效（重启 / 清数据 / 文件被移走）。给出「重新选择文件」
                        // 的入口，而不是让用户对着灰掉的行发呆。
                        markBroken(item)
                        toast(
                            "「${item.displayName}」访问权限已失效",
                            actionLabel = "重新选择"
                        ) { _state.value = _state.value.copy(reselectRequest = true) }
                    }

                    else -> {
                        markBroken(item)
                        toast(err.message ?: "无法打开该文件")
                    }
                }
            }
        }
    }

    fun submitPassword(password: String, onReady: (PdfItem, String?) -> Unit) {
        val item = _state.value.passwordRequest ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(progressText = "正在解锁…")
            val res = PdfiumEngine.pageCount(container.resolver(), Uri.parse(item.uri), password)
            _state.value = _state.value.copy(progressText = null)
            if (res is PdfResult.Success) {
                markOk(item, res.value)
                _state.value = _state.value.copy(passwordRequest = null, passwordError = false, selected = null)
                onReady(item, password)
            } else {
                _state.value = _state.value.copy(passwordError = true)
            }
        }
    }

    fun dismissPassword() {
        _state.value = _state.value.copy(passwordRequest = null, passwordError = false)
    }

    private fun markBroken(item: PdfItem) {
        _state.value = _state.value.copy(items = _state.value.items.map {
            if (it.id == item.id) it.copy(health = PdfHealth.BROKEN) else it
        })
    }

    private fun markOk(item: PdfItem, pageCount: Int) {
        _state.value = _state.value.copy(items = _state.value.items.map {
            if (it.id == item.id) it.copy(health = PdfHealth.OK, pageCount = pageCount) else it
        })
    }

    // ---------------------------------------------------------------- 文件操作

    fun hide(item: PdfItem) {
        viewModelScope.launch {
            container.prefs.hide(item.id)
            hidden.value = hidden.value + item.id
            _state.value = _state.value.copy(
                items = _state.value.items.filterNot { it.id == item.id },
                selected = null
            )
            toast("已从列表隐藏")
        }
    }

    fun unhideAll() {
        viewModelScope.launch {
            container.prefs.unhideAll()
            toast("已恢复全部隐藏项")
            scan()
        }
    }

    /** 仅删除应用内记录与缓存的导入副本，绝不删除用户原始 PDF */
    fun forget(item: PdfItem) {
        viewModelScope.launch {
            hide(item)
            val key = container.thumbnails.cacheKey(item.id, item.lastModified)
            container.thumbnails.file(key).delete()
            if (item.source == PdfSource.LINK_IMPORT) container.linkImporter.deleteImported(item.uri)
        }
    }

    // ---------------------------------------------------------------- 搜索 / 排序 / 筛选

    fun setSearchActive(active: Boolean) {
        _state.value = _state.value.copy(searchActive = active, query = if (active) _state.value.query else "")
    }

    fun query(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun setSort(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order, items = sortList(_state.value.items, order))
        viewModelScope.launch { container.prefs.update { it.copy(sortOrder = order) } }
    }

    fun setFilter(filter: ListFilter) {
        _state.value = _state.value.copy(listFilter = filter)
        viewModelScope.launch { container.prefs.update { it.copy(listFilter = filter) } }
    }

    // ---------------------------------------------------------------- 目录授权 / 导入

    fun addTree(uri: Uri) {
        viewModelScope.launch {
            val persisted = runCatching {
                container.resolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            container.prefs.addSafTree(uri.toString())
            scan()
            // 持久化失败时目录只在本次进程有效，重启后条目全部变成「无法打开」。
            // 原来这里静默吞掉，用户只会觉得「昨天还能开的书今天打不开了」。
            if (persisted.isFailure) {
                toast("该目录未授予长期访问权限，重启后可能需要重新选择")
            }
        }
    }

    /** 从系统「打开方式」或分享进入时直接导入单个文件 */
    fun importFromUri(uri: Uri, onReady: (PdfItem, String?) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(progressText = "正在读取文件…")
            val name = container.mediaStore.displayNameOf(uri) ?: "导入文档.pdf"
            // 先把读取权限变成长久授权，否则下次启动列表里躺着一个点不开的条目。
            // 某些来源（如其它 App 的 content provider）本身不支持持久化，
            // 这时不该拦住用户，只是记录下来并在打开失败时给出可操作的提示。
            val persisted = runCatching {
                container.resolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            val item = PdfItem(
                id = uri.toString(),
                uri = uri.toString(),
                displayName = name,
                sizeBytes = container.mediaStore.sizeOf(uri),
                lastModified = System.currentTimeMillis(),
                source = PdfSource.SAF_DIR,
                persistedUriPermission = persisted
            )
            _state.value = _state.value.copy(
                progressText = null,
                items = listOf(item) + _state.value.items
            )
            enrich(item)
            requestOpen(item, onReady)
        }
    }

    /** 导入在线链接（仍落盘为本地文件） */
    fun importLink(url: String, onImported: (PdfItem) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(progressText = "正在下载…")
            val res = container.linkImporter.import(url)
            _state.value = _state.value.copy(progressText = null)
            when (res) {
                is PdfResult.Success -> {
                    val f = res.value.file
                    val item = PdfItem(
                        id = f.absolutePath,
                        uri = Uri.fromFile(f).toString(),
                        displayName = res.value.displayName,
                        sizeBytes = f.length(),
                        lastModified = f.lastModified(),
                        source = PdfSource.LINK_IMPORT
                    )
                    _state.value = _state.value.copy(items = listOf(item) + _state.value.items)
                    enrich(item)
                    toast("已导入「${item.displayName}」")
                    onImported(item)
                }

                is PdfResult.Failure -> toast(res.error.message ?: "导入失败")
            }
        }
    }

    // ---------------------------------------------------------------- 提示

    fun toast(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val t = GlassToast(text = text, actionLabel = actionLabel, onAction = onAction)
        _state.value = _state.value.copy(toast = t)
        viewModelScope.launch {
            delay(4000)
            if (_state.value.toast?.id == t.id) {
                _state.value = _state.value.copy(toast = null)
            }
        }
    }

    fun dismissToast() {
        _state.value = _state.value.copy(toast = null)
    }

    /** 列表页消费掉「重新选择文件」请求，避免重组时反复弹选择器 */
    fun consumeReselect() {
        if (_state.value.reselectRequest) {
            _state.value = _state.value.copy(reselectRequest = false)
        }
    }

    private fun sortList(list: List<PdfItem>, order: SortOrder = _state.value.sortOrder): List<PdfItem> {
        val visible = list.filterNot { hidden.value.contains(it.id) }
        return when (order) {
            SortOrder.MODIFIED_DESC -> visible.sortedByDescending { it.lastModified }
            SortOrder.NAME_ASC -> visible.sortedBy { it.displayName.lowercase() }
            SortOrder.SIZE_DESC -> visible.sortedByDescending { it.sizeBytes }
            SortOrder.RECENT -> visible
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val VALIDATE_TIMEOUT_MS = 4_000L
        private const val THUMB_WIDTH = 360
        private const val MAX_ENRICH = 150
    }
}
