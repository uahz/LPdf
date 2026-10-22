package com.lpdf.reader.data.local

import android.content.Context
import android.net.Uri
import com.lpdf.reader.data.pdf.PdfiumEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缩略图预生成队列（v1.7.0）。
 *
 * ## 解决的问题
 *
 * 首屏列表只对「正在显示的几项」生成缩略图。用户往下一滑，
 * 下面几项还是灰块，得等 PDFium 现开文档、渲染第一页 —— 每张要 100~300ms。
 * 一次滑过 5 项就是 1 秒多的空白。
 *
 * ## 为什么串行
 *
 * PDFium 的 `PdfDocument` 虽然可以多实例，但每打开一次文档都要读一遍
 * 交叉引用表并分配 native 内存。并行预热 20 本的代价是内存峰值翻倍，
 * 而在列表场景里用户根本等不了那么快 —— 串行把队列排好，滑到哪里就已经好了。
 *
 * 队列用 [Channel] 而不是 `launch` 一堆协程：去重和取消都由 Channel 的
 * 容量控制，不会出现「同一项排了 3 次队」或者「滑回来又从头算一遍」。
 */
class ThumbnailPrewarmer(
    private val context: Context,
    private val store: ThumbnailStore,
    /**
     * 每次渲染后的回调：(itemId, 缩略图路径, 主色)。
     *
     * 声明成挂起函数：调用方通常要切回主线程更新列表状态，
     * 若做成普通回调，调用方自己还得再起一个协程，容易漏（漏了就是缩略图不出现）。
     */
    private val onReady: suspend (id: String?, path: String?, color: Long?) -> Unit
) {

    /** 一项待预热的任务 */
    data class Task(
        val id: String,
        val uri: String,
        val lastModified: Long
    )

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
    private var pump: Job? = null

    /** 已经在队列里或已完成的 id，避免重复排队 */
    private val queued = HashSet<String>()
    private val lock = Any()

    /**
     * 投递一批任务。重复投递同一 id 会被忽略。
     *
     * 容量给 32：列表场景一次预热的量级就在这个范围，
     * 再多说明调用方的调用方式有问题（应该只传「即将出现」的那几项）。
     */
    private val channel = Channel<Task>(capacity = 32)

    fun enqueue(tasks: List<Task>) {
        val fresh = ArrayList<Task>(tasks.size)
        synchronized(lock) {
            tasks.forEach { t ->
                if (t.id.isEmpty() || t.uri.isEmpty()) return@forEach
                if (queued.add(t.id)) fresh += t
            }
        }
        if (fresh.isEmpty()) return
        fresh.forEach { runCatching { channel.trySend(it) } }
        ensurePumping()
    }

    private fun ensurePumping() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (true) {
                val job = channel.receiveCatching().getOrNull() ?: break
                runCatching {
                    val key = store.cacheKey(job.id, job.lastModified)
                    if (!store.has(key)) {
                        val res = PdfiumEngine.renderPage(
                            context.contentResolver, Uri.parse(job.uri), 0, THUMB_WIDTH
                        )
                        res.getOrNull()?.let { bmp ->
                            store.write(key, bmp)
                            bmp.recycle()
                        }
                    }
                    if (store.has(key)) {
                        val color = store.sampleColor(key)
                        val path = store.file(key).takeIf { it.exists() }?.absolutePath
                        onReady(job.id, path, color)
                    }
                }
                // 无论成败都要出队，否则失败项会永久占据 queued 里的一格，
                // 用户重新导入同一本书时再也生成不出来
                synchronized(lock) { queued.remove(job.id) }
            }
        }
    }

    fun cancel() {
        pump?.cancel()
        pump = null
        synchronized(lock) { queued.clear() }
    }

    private companion object {
        /**
         * 缩略图宽度：必须与列表页 ListViewModel.THUMB_WIDTH 一致。
         * 两处不一致的话会为同一本书生成两份不同尺寸的缓存，
         * 谁后写谁覆盖，列表会在两种清晰度之间来回跳。
         */
        const val THUMB_WIDTH = 360
    }
}
