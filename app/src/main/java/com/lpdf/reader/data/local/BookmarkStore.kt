package com.lpdf.reader.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户书签（v1.3）。
 *
 * 与 PDF 内嵌的 TOC（[com.lpdf.reader.data.pdf.PdfiumEngine.TocEntry]）不同：
 * TOC 是作者预设的、只读的；书签是**用户自己**加的页签，可命名、可备注。
 *
 * 存储：每文档一个 JSON，键与标注一致（uri + mtime 的哈希），
 * 因此文档被替换（mtime 变化）后书签自动失效，不会错位到别的文档。
 *
 * 数据结构上不挂在 [AnnotationStore] 里，因为书签不是「画在页面上的东西」，
 * 它参与搜索/跳转，但不参与扁平化导出 —— 混在一起会让导出逻辑变复杂。
 */
data class Bookmark(
    val id: String,
    val page: Int,
    val label: String,
    val createdAt: Long
)

class BookmarkStore(private val root: File) {

    private val dir: File by lazy { File(root, "bookmarks").apply { mkdirs() } }

    /** 内存缓存：key -> 书签列表，供 Flow 推送 */
    private val cache = mutableMapOf<String, MutableStateFlow<List<Bookmark>>>()

    private fun fileOf(key: String) = File(dir, "$key.json")

    private fun flowOf(key: String): MutableStateFlow<List<Bookmark>> =
        cache.getOrPut(key) { MutableStateFlow(emptyList()) }

    /** 某文档的书签流（按页码升序） */
    fun bookmarksFlow(key: String): Flow<List<Bookmark>> = flowOf(key).asStateFlow()

    /** 当前快照，非响应式场景直接用 */
    fun snapshot(key: String): List<Bookmark> = flowOf(key).value

    suspend fun load(key: String): List<Bookmark> = withContext(Dispatchers.IO) {
        val f = fileOf(key)
        val list = if (!f.exists()) emptyList() else runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Bookmark(
                    id = o.optString("id"),
                    page = o.optInt("page", 0),
                    label = o.optString("label", ""),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }
        }.getOrElse { emptyList() }
        val sorted = list.sortedBy { it.page }
        flowOf(key).value = sorted
        sorted
    }

    suspend fun save(key: String, list: List<Bookmark>) = withContext(Dispatchers.IO) {
        val sorted = list.sortedBy { it.page }
        val obj = JSONObject()
        val arr = JSONArray()
        sorted.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("page", b.page)
                    put("label", b.label)
                    put("createdAt", b.createdAt)
                }
            )
        }
        obj.put("items", arr)
        fileOf(key).writeText(obj.toString())
        flowOf(key).value = sorted
    }

    /** 同一文档内所有书签的页码集合（列表页用于打角标） */
    suspend fun allKeys(): List<String> = withContext(Dispatchers.IO) {
        // 只认 .json：removeSuffix 对 "abc.tmp" 这类名字不生效，
        // 会把它原样当成 key 混进结果里
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        cache.values.forEach { it.value = emptyList() }
        cache.clear()
    }
}
