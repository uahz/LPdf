package com.lpdf.reader.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 签名库（v1.3）。
 *
 * 用户手写签名后存为 PNG，可反复复用（签合同、盖章场景常见）。
 *
 * 为什么单独一个目录而不是塞进 cache：
 * - 签名是**用户资产**，不是可再生的缓存，必须放 filesDir 才不会被动清理
 * - 与 [ThumbnailStore]（cacheDir，可随时清）严格区分
 *
 * 透明背景：导出的 PNG 保留 alpha，贴到 PDF 上不会带白底方块。
 */
data class SignatureAsset(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long
) {
    val file: File get() = File(path)
    fun exists(): Boolean = file.exists() && file.length() > 0
}

class SignatureStore(private val root: File) {

    private val dir: File by lazy { File(root, "signatures").apply { mkdirs() } }
    private val index: File by lazy { File(dir, "index.json") }

    /** 列出全部签名，按创建时间倒序（最近用的在前） */
    suspend fun list(): List<SignatureAsset> = withContext(Dispatchers.IO) {
        if (!index.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SignatureAsset(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    path = o.optString("path"),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }.filter { it.exists() }.sortedByDescending { it.createdAt }
        }.getOrElse { emptyList() }
    }

    /**
     * 保存一张手写签名。
     *
     * @param bitmap 已裁掉空白区域、透明背景的位图
     * @return 保存后的资产；写盘失败返回 null
     */
    suspend fun save(bitmap: Bitmap, name: String): SignatureAsset? = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString()
            val f = File(dir, "$id.png")
            f.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val asset = SignatureAsset(
                id = id,
                name = name.ifBlank { "签名 ${System.currentTimeMillis() % 100000}" },
                path = f.absolutePath,
                createdAt = System.currentTimeMillis()
            )
            val current = list()
            writeIndex(listOf(asset) + current)
            asset
        }.getOrNull()
    }

    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        val all = list().map { if (it.id == id) it.copy(name = newName) else it }
        writeIndex(all)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = list()
        val target = all.firstOrNull { it.id == id }
        target?.let { runCatching { it.file.delete() } }
        writeIndex(all.filterNot { it.id == id })
    }

    private fun writeIndex(list: List<SignatureAsset>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("path", s.path)
                    put("createdAt", s.createdAt)
                }
            )
        }
        index.writeText(arr.toString())
    }

    /** 读取签名位图；文件缺失返回 null，由调用方决定如何降级 */
    suspend fun loadBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** 总占用字节数（设置页「清空签名」展示用） */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}

/**
 * 把签名画布裁成「内容外接框」并保留透明背景。
 *
 * 手写时用户很难正好画满画布，直接整张保存会导致贴到 PDF 上时
 * 签名偏小或位置飘。这里按实际笔迹边界裁切，并留少量内边距。
 */
fun cropSignature(bitmap: Bitmap, paddingRatio: Float = 0.08f): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return bitmap

    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1

    // 逐像素找不透明区域；签名画布通常 600x300 量级，直接扫描可接受
    val row = IntArray(w)
    for (y in 0 until h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val alpha = (row[x] ushr 24) and 0xFF
            if (alpha > 8) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }

    if (maxX < minX || maxY < minY) return bitmap  // 全透明，原样返回

    val padX = ((maxX - minX + 1) * paddingRatio).toInt().coerceAtLeast(2)
    val padY = ((maxY - minY + 1) * paddingRatio).toInt().coerceAtLeast(2)

    val left = (minX - padX).coerceAtLeast(0)
    val top = (minY - padY).coerceAtLeast(0)
    val right = (maxX + padX + 1).coerceAtMost(w)
    val bottom = (maxY + padY + 1).coerceAtMost(h)

    val cw = (right - left).coerceAtLeast(1)
    val ch = (bottom - top).coerceAtLeast(1)
    return runCatching { Bitmap.createBitmap(bitmap, left, top, cw, ch) }.getOrDefault(bitmap)
}
