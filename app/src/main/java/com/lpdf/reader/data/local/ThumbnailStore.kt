package com.lpdf.reader.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 缩略图与采样主色缓存。
 *
 * - 存于 cacheDir/thumbs，LRU 由系统管理
 * - 采样主色用于液态玻璃的「实时高亮色」
 * - 全部 IO 走后台线程
 */
class ThumbnailStore(private val context: Context) {

    private val dir: File by lazy { File(context.cacheDir, "thumbs").apply { mkdirs() } }
    private val colorMemo = ConcurrentHashMap<String, Long>()

    fun cacheKey(id: String, lastModified: Long): String =
        (id + "_" + lastModified).hashCode().toUInt().toString(16)

    fun file(key: String): File = File(dir, "$key.webp")
    fun has(key: String): Boolean = file(key).let { it.exists() && it.length() > 0 }

    suspend fun write(key: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            file(key).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, out)
            }
        }
        Unit
    }

    /** 采样主色：缩放为 8×8 后按亮度加权，剔除极端色 */
    suspend fun sampleColor(key: String): Long? = withContext(Dispatchers.IO) {
        colorMemo[key]?.let { return@withContext it }
        runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(file(key).absolutePath)
                ?: return@withContext null
            val small = Bitmap.createScaledBitmap(bmp, 8, 8, true)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (x in 0 until small.width) for (y in 0 until small.height) {
                val c = small.getPixel(x, y)
                val lum = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
                if (lum < 0.15 || lum > 0.92) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            bmp.recycle()
            small.recycle()
            if (n == 0) return@withContext 0xFFE8D9BE  // 默认暖米色
            val avg = (0xFFL shl 24) or
                ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val softened = Formatters.softenColor(avg)
            colorMemo[key] = softened
            softened
        }.getOrNull()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        colorMemo.clear()
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** 缓存目录总大小（含导入文件） */
    suspend fun totalCacheBytes(): Long = withContext(Dispatchers.IO) {
        fun size(f: File): Long = if (f.isDirectory) (f.listFiles()?.sumOf { size(it) } ?: 0L) else f.length()
        size(context.cacheDir) + size(File(context.filesDir, "imported"))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        File(context.filesDir, "imported").listFiles()?.forEach { it.delete() }
        colorMemo.clear()
    }

    /** 从 URI 直接读取本地文件（在线导入的 PDF） */
    fun localFile(uri: String): File? =
        uri.removePrefix("file://").let { File(it) }.takeIf { it.exists() }

    fun treeName(uri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri))?.name }
            .getOrNull() ?: uri
}
