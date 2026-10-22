package com.lpdf.reader.data.local

import com.lpdf.reader.core.model.PdfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 跨文档聚合条目（v1.6.0）。
 *
 * 「书签」页要一次列出所有文档的书签，「首页」要列出最近在读的文档 ——
 * 两者都需要把「按文档存放」的本地数据拉平成一张全局表。
 *
 * 关键问题是**反查**：书签文件用的是 `keyOf(uri, lastModified)` 生成的哈希键，
 * 键本身不包含 uri，所以没法从键推出它属于哪个文件。这里改成
 * 「拿当前已知的文档列表去算键，再和实际存在的键求交」——
 * 文档被移动/改名后旧键会变成孤儿，自然就不出现在列表里，
 * 这比反过来把孤儿书签显示出来（点了还打不开）要好。
 */
data class BookmarkHit(
    val doc: PdfItem,
    val page: Int,
    val label: String,
    val createdAt: Long
)

data class ReadingHit(
    val doc: PdfItem,
    /** 0 基页号 */
    val page: Int,
    val totalPages: Int
)

object LibraryAggregator {

    /**
     * 把书签按文档归并成型。
     *
     * @param docs 当前已知文档（通常是扫描结果 + 隐藏过滤后的可见项）
     * @param keys 实际存在的书签键（[BookmarkStore.allKeys]）
     * @param keyOf 文档 → 存储键
     * @param bookmarksOf 读取某键下的书签快照
     */
    fun bookmarks(
        docs: List<PdfItem>,
        keys: Collection<String>,
        keyOf: (PdfItem) -> String,
        bookmarksOf: (String) -> List<Bookmark>
    ): List<BookmarkHit> {
        if (docs.isEmpty() || keys.isEmpty()) return emptyList()
        val keySet = keys.toHashSet()
        val out = ArrayList<BookmarkHit>()
        docs.forEach { doc ->
            val k = keyOf(doc)
            if (!keySet.contains(k)) return@forEach
            bookmarksOf(k).forEach { b ->
                out += BookmarkHit(doc, b.page, b.label, b.createdAt)
            }
        }
        // 新的排前面：书签是「我最近标记的」而不是「按文档顺序」
        return out.sortedByDescending { it.createdAt }
    }

    /**
     * 最近在读：只保留进度 > 0 的文档，按页号占比降序？
     * 不 —— 占比没有意义（读完 1/500 和 400/450 哪个更「在读」不好说）。
     * 这里只按「有进度」筛出来，排序交给调用方用文件修改时间。
     */
    fun reading(
        docs: List<PdfItem>,
        progress: Map<String, Int>,
        keyOf: (PdfItem) -> String
    ): List<ReadingHit> {
        if (docs.isEmpty() || progress.isEmpty()) return emptyList()
        val out = ArrayList<ReadingHit>()
        docs.forEach { doc ->
            val p = progress[keyOf(doc)] ?: return@forEach
            if (p <= 0) return@forEach
            val total = doc.pageCount ?: 0
            // 进度越界说明文档被替换过（页数变少），退回首页更安全
            val safe = if (total > 0) p.coerceIn(0, total - 1) else p
            out += ReadingHit(doc, safe, total)
        }
        return out.sortedByDescending { it.doc.lastModified }
    }
}

/** 便捷入口：把 IO 读取包起来，UI 层不用关心线程 */
suspend fun BookmarkStore.hitsFor(
    docs: List<PdfItem>,
    keyOf: (PdfItem) -> String
): List<BookmarkHit> = withContext(Dispatchers.IO) {
    val keys = allKeys()
    if (keys.isEmpty()) return@withContext emptyList()
    // 先把需要的键读进内存，再交给纯函数归并 —— 纯函数可单测
    val loaded = keys.associateWith { k -> runCatching { load(k) }.getOrDefault(emptyList()) }
    LibraryAggregator.bookmarks(docs, keys, keyOf) { k -> loaded[k] ?: emptyList() }
}
