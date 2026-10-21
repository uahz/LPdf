package com.lpdf.reader.data.pdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import io.legere.pdfiumandroid.FindFlags
import io.legere.pdfiumandroid.FindResult
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
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

    /**
     * 只创建一次。PdfiumCore 的构造会触发 System.loadLibrary，
     * 每次 new 都重新走一遍加载路径毫无必要，且会让 native 异常反复抛。
     */
    @Volatile
    private var coreInstance: PdfiumCore? = null

    /** native 库是否可用。加载失败时置 false，上层据此给出可读提示而不是崩溃。 */
    @Volatile
    var nativeAvailable: Boolean = true
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun core(): PdfiumCore {
        coreInstance?.let { return it }
        val ctx = appContext ?: throw PdfError.Unknown("渲染内核未初始化")
        return try {
            PdfiumCore(ctx).also { coreInstance = it }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / NoClassDefFoundError 都属于这里：
            // ABI 不匹配或 so 未打进包时才会发生
            nativeAvailable = false
            throw PdfError.Unknown("PDF 引擎加载失败：${t.javaClass.simpleName}")
        }
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

    /**
     * 全文搜索（v1.3）。
     *
     * 在**同一个**已打开文档内逐页搜索，避免循环里反复 openDocument 造成 IO 爆炸。
     * 命中结果带回归一化矩形（0~1），可直接用于阅读页叠加高亮。
     *
     * @param maxResults 命中上限，防止「的」这类高频词把内存打满
     * @param matchCase 是否区分大小写
     * @param onProgress 进度回调（已扫描页数 / 总页数）
     */
    data class SearchHit(
        val page: Int,
        val snippet: String,
        val rects: List<AnnotRectLike>,
        /** 匹配到的起始字符序号，用于「上一个 / 下一个」定位 */
        val charIndex: Int
    )

    /** 归一化矩形（避免 data 层依赖 UI 层模型） */
    data class AnnotRectLike(val left: Float, val top: Float, val right: Float, val bottom: Float)

    suspend fun search(
        resolver: ContentResolver,
        uri: Uri,
        query: String,
        password: String? = null,
        matchCase: Boolean = false,
        maxResults: Int = 300,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): PdfResult<List<SearchHit>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext PdfResult.Success(emptyList())

        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            var textPage: PdfTextPage? = null
            var find: FindResult? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                val doc = core.newDocument(pfd, password ?: "")
                val total: Int = doc.getPageCount().toInt()
                val hits = mutableListOf<SearchHit>()
                val flags = if (matchCase) setOf(FindFlags.MatchCase) else emptySet()

                for (p in 0 until total) {
                    if (hits.size >= maxResults) break
                    onProgress(p, total)

                    val page = doc.openPage(p)
                    // 页面点位尺寸，用于把 char box 归一化
                    val pageW = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
                    val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                    val tp = page.openTextPage()
                    textPage = tp

                    var start = 0
                    var guard = 0
                    while (hits.size < maxResults && guard++ < 500) {
                        val fr = tp.findStart(query, flags, start) ?: break
                        find = fr
                        val idx = fr.getSchResultIndex()
                        val count = fr.getSchCount()
                        if (idx < 0) break

                        // 该匹配覆盖的矩形（通常 1~2 个）
                        val rects = buildList {
                            val n = tp.textPageCountRects(idx, idx + count)
                            val maxRects = n.coerceAtMost(8)
                            for (k in 0 until maxRects) {
                                val r = tp.textPageGetRect(k)
                                if (r != null && r.width() > 0f && r.height() > 0f) {
                                    add(
                                        AnnotRectLike(
                                            left = (r.left / pageW).coerceIn(0f, 1f),
                                            top = (r.top / pageH).coerceIn(0f, 1f),
                                            right = (r.right / pageW).coerceIn(0f, 1f),
                                            bottom = (r.bottom / pageH).coerceIn(0f, 1f)
                                        )
                                    )
                                }
                            }
                        }

                        val snippet = runCatching {
                            val totalChars = tp.textPageCountChars()
                            val from = (idx - 20).coerceAtLeast(0)
                            val to = (idx + count + 30).coerceAtMost(totalChars)
                            val raw = tp.textPageGetText(from, to - from) ?: ""
                            raw.replace(Regex("\\s+"), " ").trim()
                        }.getOrElse { "" }

                        hits += SearchHit(p, snippet.ifBlank { query }, rects, idx)
                        fr.closeFind()
                        find = null
                        start = idx + count
                        if (start <= idx) break
                    }

                    textPage = null
                    runCatching { tp.close() }
                    runCatching { page.close() }
                }

                runCatching { find?.closeFind() }
                runCatching { textPage?.close() }
                core.closeDocument(doc)
                onProgress(total, total)
                PdfResult.Success(hits)
            } catch (e: SecurityException) {
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("搜索失败", t))
            } finally {
                runCatching { find?.closeFind() }
                runCatching { textPage?.close() }
                runCatching { pfd?.close() }
            }
        }
    }

    const val MAX_RENDER_WIDTH = 2048
}
