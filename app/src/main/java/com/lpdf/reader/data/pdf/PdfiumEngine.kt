package com.lpdf.reader.data.pdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDFium 渲染内核封装。
 *
 * 约束（开发规范 §3.3）：
 * - PDFium 非线程安全 → 全局 [MUTEX] 串行化
 * - 仅在 IO 调度器调用，禁止主线程
 * - 渲染位图宽度上限 [MAX_RENDER_WIDTH]，超出按比例缩小，防止 OOM
 */
object PdfiumEngine {

    private val MUTEX = Mutex()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun core(): PdfiumCore {
        val ctx = appContext ?: throw PdfError.Unknown("渲染内核未初始化")
        return PdfiumCore(ctx)
    }

    /** 打开文档并返回页数。password 为空表示不加密。 */
    suspend fun pageCount(
        resolver: ContentResolver,
        uri: Uri,
        password: String? = null
    ): PdfResult<Int> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                val doc = core.newDocument(pfd, password ?: "")
                val count: Int = doc.getPageCount().toInt()
                core.closeDocument(doc)
                if (count <= 0) {
                    PdfResult.Failure(PdfError.Corrupted("文档没有可读页面"))
                } else {
                    PdfResult.Success(count)
                }
            } catch (e: SecurityException) {
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                when {
                    msg.contains("password", true) -> PdfResult.Failure(PdfError.WrongPassword())
                    msg.contains("security", true) -> PdfResult.Failure(PdfError.PasswordRequired())
                    msg.contains("denied", true) -> PdfResult.Failure(PdfError.PermissionDenied())
                    else -> PdfResult.Failure(PdfError.Corrupted())
                }
            } finally {
                runCatching { pfd?.close() }
            }
        }
    }

    /**
     * 渲染指定页为位图。
     *
     * @param targetWidthPx 目标宽度（像素），内部按 [MAX_RENDER_WIDTH] 上限裁剪
     */
    suspend fun renderPage(
        resolver: ContentResolver,
        uri: Uri,
        pageIndex: Int,
        targetWidthPx: Int,
        password: String? = null
    ): PdfResult<Bitmap> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                val doc = core.newDocument(pfd, password ?: "")
                val page: PdfPage = doc.openPage(pageIndex)
                val width = targetWidthPx.coerceIn(64, MAX_RENDER_WIDTH)
                val w = page.getPageWidth(width)
                val h = page.getPageHeight(width)
                val bitmap = Bitmap.createBitmap(
                    w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.renderPageBitmap(bitmap, 0, 0, w, h, true)
                page.close()
                core.closeDocument(doc)
                PdfResult.Success(bitmap)
            } catch (e: SecurityException) {
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("页面渲染失败", t))
            } finally {
                runCatching { pfd?.close() }
            }
        }
    }

    /** 目录（书签）条目 */
    data class TocEntry(val title: String, val pageIndex: Int, val depth: Int)

    suspend fun tableOfContents(resolver: ContentResolver, uri: Uri): PdfResult<List<TocEntry>> =
        withContext(Dispatchers.IO) {
            MUTEX.withLock {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = resolver.openFileDescriptor(uri, "r")
                        ?: return@withLock PdfResult.Success(emptyList())
                    val core = core()
                    val doc = core.newDocument(pfd)
                    val flat = mutableListOf<TocEntry>()
                    fun walk(items: List<PdfDocument.Bookmark>, depth: Int) {
                        items.forEach { b ->
                            flat += TocEntry(b.title ?: "未命名", b.pageIdx.toInt(), depth)
                            walk(b.children ?: emptyList(), depth + 1)
                        }
                    }
                    walk(core.getTableOfContents(doc) ?: emptyList(), 0)
                    core.closeDocument(doc)
                    PdfResult.Success(flat)
                } catch (t: Throwable) {
                    PdfResult.Success(emptyList())
                } finally {
                    runCatching { pfd?.close() }
                }
            }
        }

    /** 页面尺寸（pt） */
    data class PageSize(val widthPt: Float, val heightPt: Float, val rotation: Int)

    suspend fun pageSize(
        resolver: ContentResolver,
        uri: Uri,
        pageIndex: Int
    ): PdfResult<PageSize> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                val doc = core.newDocument(pfd)
                val page = doc.openPage(pageIndex)
                val size = PageSize(
                    widthPt = page.getPageWidthPoint().toFloat(),
                    heightPt = page.getPageHeightPoint().toFloat(),
                    rotation = page.getPageRotation()
                )
                page.close()
                core.closeDocument(doc)
                PdfResult.Success(size)
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("读取页面尺寸失败", t))
            } finally {
                runCatching { pfd?.close() }
            }
        }
    }

    /** 文档元数据 */
    data class Meta(
        val title: String?,
        val author: String?,
        val subject: String?,
        val keywords: String?
    )

    suspend fun metadata(resolver: ContentResolver, uri: Uri): PdfResult<Meta> =
        withContext(Dispatchers.IO) {
            MUTEX.withLock {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = resolver.openFileDescriptor(uri, "r")
                        ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                    val core = core()
                    val doc = core.newDocument(pfd)
                    val m = doc.getDocumentMeta()
                    core.closeDocument(doc)
                    PdfResult.Success(Meta(m.title, m.author, m.subject, m.keywords))
                } catch (t: Throwable) {
                    PdfResult.Failure(PdfError.Unknown("读取元数据失败", t))
                } finally {
                    runCatching { pfd?.close() }
                }
            }
        }

    const val MAX_RENDER_WIDTH = 2048
}
