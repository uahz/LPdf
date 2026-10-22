package com.lpdf.reader.ui.page.marks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.data.local.BookmarkHit
import com.lpdf.reader.data.local.LibraryAggregator
import com.lpdf.reader.data.local.scanAll
import com.lpdf.reader.ui.glass.GlassListRow
import com.lpdf.reader.ui.glass.GlassStateCard
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书签页（v1.6.0）。
 *
 * 阅读页里的书签面板只管「当前文档」，这里管**全部文档**。
 * 典型场景：给三本书各标了几页，想一次看到所有标记，不必一本本打开去找。
 *
 * ## 数据怎么来
 *
 * 书签按文档存在 `bookmarks/<key>.json`，key 是 `hash(uri|mtime)` ——
 * **键里没有 uri**，无法从文件反推它属于哪个文档。所以这里反过来：
 * 拿扫描出的文档去算 key，再和磁盘上实际存在的 key 求交。
 * 文档被移动/改名的旧书签会变成孤儿，自然不显示 ——
 * 显示出来但点了打不开更糟。
 */
data class BookmarksUiState(
    val loading: Boolean = true,
    val hits: List<BookmarkHit> = emptyList(),
    /** 有书签的文档数（与 hits.size 不同：一本书可能标了 10 页） */
    val docCount: Int = 0
)

class BookmarksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()

    /**
     * 每次页面重新可见时调用。
     *
     * 第一次进来要做全量扫描，之后只需重算聚合（书签文件很小）。
     * 以前这里是「加载过就不再加载」，导致在阅读页加了书签、切回书签页
     * 看不到新的 —— 必须杀进程重开才行。
     */
    fun refresh() = viewModelScope.launch { load() }

    private suspend fun load() {
        _state.value = _state.value.copy(loading = true)
        val docs = withContext(Dispatchers.IO) {
            val hidden = container.prefs.hiddenIds.first()
            runCatching { container.mediaStore.scanAll().filterNot { hidden.contains(it.id) } }
                .getOrDefault(emptyList())
        }
        val hits = withContext(Dispatchers.IO) {
            val keys = container.bookmarks.allKeys()
            if (keys.isEmpty()) return@withContext emptyList()
            val byKey = keys.associateWith { k ->
                runCatching { container.bookmarks.load(k) }.getOrDefault(emptyList())
            }
            LibraryAggregator.bookmarks(docs, keys, { keyOf(it) }) { k -> byKey[k] ?: emptyList() }
        }
        _state.value = BookmarksUiState(
            loading = false,
            hits = hits,
            docCount = hits.map { it.doc.id }.distinct().size
        )
    }

    private fun keyOf(item: PdfItem): String =
        container.annotations.keyOf(item.uri, item.lastModified)
}

@Composable
fun BookmarksPage(
    container: AppContainer,
    /** modified 必须带：阅读页要拿它算存储键，否则跳过去看不到这些书签 */
    onOpenAt: (uri: String, name: String, modified: Long, page: Int) -> Unit
) {
    val vm: BookmarksViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BookmarksViewModel(container) as T
        }
    )
    val state by vm.state.collectAsState()

    // 每次可见都重读：在阅读页新加的书签，切回来必须立刻能看到
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) { vm.refresh() }

    val colors = LocalColors.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(topInset + 20.dp))
        Column(Modifier.padding(horizontal = LSpace.pageHorizontal)) {
            Text("书签", style = LType.TitleL, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.loading -> "正在读取…"
                    state.hits.isEmpty() -> "还没有标记任何页面"
                    else -> "${state.hits.size} 处标记 · ${state.docCount} 个文档"
                },
                style = LType.Caption,
                color = colors.secondary
            )
        }
        Spacer(Modifier.height(16.dp))

        if (state.hits.isEmpty() && !state.loading) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = LSpace.pageHorizontal),
                Alignment.TopCenter
            ) {
                GlassStateCard(
                    icon = LIcon.Bookmark,
                    title = "还没有书签",
                    message = "阅读时点底栏的书签按钮，就能把当前页记下来。\n所有文档的书签都会汇总到这里。"
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = LSpace.pageHorizontal,
                    end = LSpace.pageHorizontal,
                    top = 4.dp,
                    // 底部留出底栏高度，否则最后一条会被玻璃挡住
                    bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.hits, key = { "${it.doc.id}-${it.page}-${it.createdAt}" }) { hit ->
                    BookmarkRow(hit) {
                        onOpenAt(hit.doc.uri, hit.doc.displayName, hit.doc.lastModified, hit.page)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(hit: BookmarkHit, onClick: () -> Unit) {
    val colors = LocalColors.current
    GlassListRow(
        title = hit.label.ifBlank { "第 ${hit.page + 1} 页" },
        subtitle = hit.doc.displayName,
        thumbPath = null,
        onClick = onClick,
        onLongClick = onClick,
        trailing = {
            Text("P${hit.page + 1}", style = LType.Micro, color = colors.secondary)
        },
        badge = {
            LIconView(LIcon.BookmarkFilled, size = 15.dp, color = colors.accent, strokeWidth = 1.8.dp)
        },
        compact = true
    )
}
