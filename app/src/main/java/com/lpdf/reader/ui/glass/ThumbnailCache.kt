package com.lpdf.reader.ui.glass

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 缩略图内存缓存（v1.3.4）。
 *
 * ## 为什么需要
 *
 * 列表项原来这样加载缩略图：
 *
 * ```kotlin
 * val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
 * ```
 *
 * `remember` 的生命周期跟着 Composable 实例。`LazyColumn` 里滚出屏幕的项
 * 虽然不参与布局，但缓存条目要等 GC 才回收 —— 滚动几百个文档就攒出几十 MB，
 * 低端机直接 OOM。
 *
 * ## 策略
 *
 * - LRU，按条目数和字节数双限
 * - 同步 `get`：缩略图已在磁盘且是 WebP，解码快；异步反而引入占位闪烁
 * - 磁盘文件被删时自然返回 null，不需要额外失效逻辑
 * - 注意：这里**不主动 recycle**。ImageBitmap 可能仍被某帧的 RenderNode 引用，
 *   提前 recycle 会触发 `Canvas: trying to draw recycled bitmap`。
 *   靠 LRU 逐出 + GC 回收即可，双限保证峰值可控。
 */
internal object ThumbnailCache {

    private const val MAX_ENTRIES = 120
    private const val MAX_BYTES = 24L * 1024 * 1024

    private val lru = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

    private var bytes = 0L

    fun get(path: String): ImageBitmap? {
        synchronized(lru) {
            lru[path]?.let { return it }
        }
        // 解码放在锁外，避免一张大图卡住整列表
        val decoded = runCatching {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }.getOrNull() ?: return null

        synchronized(lru) {
            // 竞争者可能已经放进去了，直接复用，丢掉自己这份
            lru[path]?.let { return it }
            lru[path] = decoded
            bytes += decoded.allocationBytes()
            trim()
            return decoded
        }
    }

    /** 调用方需持有 [lru] 的锁 */
    private fun trim() {
        while (lru.size > MAX_ENTRIES || bytes > MAX_BYTES) {
            if (lru.size <= 1) break
            val eldest = lru.entries.firstOrNull() ?: break
            bytes -= eldest.value.allocationBytes()
            lru.remove(eldest.key)
        }
        if (bytes < 0) bytes = 0L
    }

    /** 缓存占用（诊断用） */
    fun sizeBytes(): Long = synchronized(lru) { bytes }

    fun clear() = synchronized(lru) {
        lru.clear()
        bytes = 0L
    }

    private fun ImageBitmap.allocationBytes(): Long =
        runCatching {
            val bmp = asAndroidBitmap()
            bmp.allocationByteCount.toLong()
        }.getOrElse {
            runCatching { width.toLong() * height.toLong() * 4L }.getOrDefault(0L)
        }
}
