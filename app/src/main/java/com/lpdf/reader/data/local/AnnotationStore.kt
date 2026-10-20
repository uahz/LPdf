package com.lpdf.reader.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 标注图层：与 PDF 原文件分离存储（开发规范 §5.1）。
 *
 * - 坐标系为「归一化页面坐标」0~1，与缩放/分辨率无关
 * - 每文档一个 JSON，键为 uri+mtime 的哈希
 * - 写入防抖由调用方控制
 */
data class AnnotRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

sealed class Annotation {
    abstract val id: String

    data class Highlight(
        override val id: String,
        val rects: List<AnnotRect>,
        val color: Long
    ) : Annotation()

    data class Ink(
        override val id: String,
        val points: List<Pair<Float, Float>>,   // 归一化坐标序列
        val width: Float,                        // 归一化线宽（相对页面宽度）
        val color: Long
    ) : Annotation()

    data class Note(
        override val id: String,
        val x: Float,
        val y: Float,
        val text: String
    ) : Annotation()

    data class Shape(
        override val id: String,
        val kind: String,                        // RECT / OVAL / ARROW
        val bounds: AnnotRect,
        val stroke: Float,
        val color: Long,
        val filled: Boolean = false
    ) : Annotation()

    data class TextBox(
        override val id: String,
        val bounds: AnnotRect,
        val text: String,
        val size: Float,
        val color: Long
    ) : Annotation()

    data class Signature(
        override val id: String,
        val bounds: AnnotRect,
        val bitmapPath: String
    ) : Annotation()

    data class FormField(
        override val id: String,
        val fieldName: String,
        val bounds: AnnotRect,
        val value: String
    ) : Annotation()
}

/** 单文档标注集合 */
data class AnnotationDoc(
    val docKey: String,
    val pages: MutableMap<Int, MutableList<Annotation>> = mutableMapOf()
) {
    fun all(): List<Pair<Int, Annotation>> = pages.entries.flatMap { (p, list) -> list.map { p to it } }
    fun count(): Int = pages.values.sumOf { it.size }
    fun isEmpty(): Boolean = pages.values.all { it.isEmpty() }
}

class AnnotationStore(private val root: File) {

    private val dir: File by lazy { File(root, "annotations").apply { mkdirs() } }

    fun keyOf(uri: String, lastModified: Long): String =
        Integer.toHexString((uri + "|" + lastModified).hashCode())

    private fun fileOf(key: String) = File(dir, "$key.json")

    suspend fun load(key: String): AnnotationDoc = withContext(Dispatchers.IO) {
        val doc = AnnotationDoc(key)
        val f = fileOf(key)
        if (!f.exists()) return@withContext doc
        runCatching {
            val rootObj = JSONObject(f.readText())
            val pagesObj = rootObj.optJSONObject("pages") ?: JSONObject()
            pagesObj.keys().forEach { pageStr ->
                val page = pageStr.toIntOrNull() ?: return@forEach
                val arr = pagesObj.optJSONArray(pageStr) ?: JSONArray()
                val list = mutableListOf<Annotation>()
                for (i in 0 until arr.length()) {
                    parse(arr.getJSONObject(i))?.let { list += it }
                }
                if (list.isNotEmpty()) doc.pages[page] = list
            }
        }
        doc
    }

    suspend fun save(doc: AnnotationDoc): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val pagesObj = JSONObject()
            doc.pages.forEach { (page, list) ->
                val arr = JSONArray()
                list.forEach { arr.put(serialize(it)) }
                pagesObj.put(page.toString(), arr)
            }
            val rootObj = JSONObject().put("docKey", doc.docKey).put("pages", pagesObj)
            val tmp = File(dir, "${doc.docKey}.tmp")
            tmp.writeText(rootObj.toString())
            tmp.renameTo(fileOf(doc.docKey))
        }.isSuccess
    }

    suspend fun clear(key: String) = withContext(Dispatchers.IO) { fileOf(key).delete() }

    /** 所有已存在标注文档的 key（用于列表「已标注」筛选） */
    fun allKeysFlow(): kotlinx.coroutines.flow.Flow<Set<String>> = kotlinx.coroutines.flow.flow {
        emit(withContext(Dispatchers.IO) {
            dir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapTo(mutableSetOf()) { it.nameWithoutExtension }
                ?: emptySet()
        })
    }

    /** 删除全部标注文件（设置页「清除全部标注」） */
    suspend fun clearAllStorage() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    // ---- JSON 序列化 ----

    private fun rectJson(r: AnnotRect) = JSONObject()
        .put("l", r.left).put("t", r.top).put("r", r.right).put("b", r.bottom)

    private fun parseRect(o: JSONObject) = AnnotRect(
        o.optDouble("l", 0.0).toFloat(), o.optDouble("t", 0.0).toFloat(),
        o.optDouble("r", 0.0).toFloat(), o.optDouble("b", 0.0).toFloat()
    )

    private fun serialize(a: Annotation): JSONObject = when (a) {
        is Annotation.Highlight -> JSONObject()
            .put("type", "highlight").put("id", a.id).put("color", a.color)
            .put("rects", JSONArray().also { arr -> a.rects.forEach { arr.put(rectJson(it)) } })

        is Annotation.Ink -> JSONObject()
            .put("type", "ink").put("id", a.id).put("color", a.color).put("width", a.width)
            .put("points", JSONArray().also { arr -> a.points.forEach { arr.put(JSONArray().put(it.first).put(it.second)) } })

        is Annotation.Note -> JSONObject()
            .put("type", "note").put("id", a.id).put("x", a.x).put("y", a.y).put("text", a.text)

        is Annotation.Shape -> JSONObject()
            .put("type", "shape").put("id", a.id).put("kind", a.kind)
            .put("bounds", rectJson(a.bounds)).put("stroke", a.stroke)
            .put("color", a.color).put("filled", a.filled)

        is Annotation.TextBox -> JSONObject()
            .put("type", "text").put("id", a.id).put("bounds", rectJson(a.bounds))
            .put("text", a.text).put("size", a.size).put("color", a.color)

        is Annotation.Signature -> JSONObject()
            .put("type", "sign").put("id", a.id).put("bounds", rectJson(a.bounds))
            .put("path", a.bitmapPath)

        is Annotation.FormField -> JSONObject()
            .put("type", "form").put("id", a.id).put("field", a.fieldName)
            .put("bounds", rectJson(a.bounds)).put("value", a.value)
    }

    private fun parse(o: JSONObject): Annotation? = runCatching {
        when (o.optString("type")) {
            "highlight" -> {
                val arr = o.optJSONArray("rects") ?: JSONArray()
                val rects = (0 until arr.length()).map { parseRect(arr.getJSONObject(it)) }
                Annotation.Highlight(o.getString("id"), rects, o.getLong("color"))
            }

            "ink" -> {
                val arr = o.optJSONArray("points") ?: JSONArray()
                val pts = (0 until arr.length()).map {
                    val p = arr.getJSONArray(it)
                    p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                }
                Annotation.Ink(o.getString("id"), pts, o.getDouble("width").toFloat(), o.getLong("color"))
            }

            "note" -> Annotation.Note(
                o.getString("id"), o.getDouble("x").toFloat(),
                o.getDouble("y").toFloat(), o.getString("text")
            )

            "shape" -> Annotation.Shape(
                o.getString("id"), o.getString("kind"), parseRect(o.getJSONObject("bounds")),
                o.getDouble("stroke").toFloat(), o.getLong("color"), o.optBoolean("filled")
            )

            "text" -> Annotation.TextBox(
                o.getString("id"), parseRect(o.getJSONObject("bounds")), o.getString("text"),
                o.getDouble("size").toFloat(), o.getLong("color")
            )

            "sign" -> Annotation.Signature(
                o.getString("id"), parseRect(o.getJSONObject("bounds")), o.getString("path")
            )

            "form" -> Annotation.FormField(
                o.getString("id"), o.getString("field"), parseRect(o.getJSONObject("bounds")),
                o.getString("value")
            )

            else -> null
        }
    }.getOrNull()
}
