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
            var doc: PdfDocument? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                doc = core.newDocument(pfd, password ?: "")
                val count: Int = doc.getPageCount().toInt()
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
                // 原来这里只在成功路径 closeDocument：getPageCount 抛异常
                // （损坏文档很常见）时 native 文档就泄漏了。
                runCatching { doc?.let { core().closeDocument(it) } }
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
            var doc: PdfDocument? = null
            var page: PdfPage? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                doc = core.newDocument(pfd, password ?: "")
                page = doc.openPage(pageIndex)
                val (w, h) = renderSize(page, targetWidthPx)
                // 显存水位检查：createBitmap 成功不代表 drawBitmap 能过。
                // RecordingCanvas 在把位图交给 GPU 时若超过硬件纹理上限会直接抛
                // RuntimeException（v1.3.2 真机崩溃即此），这里提前拦掉。
                if (!PdfLimits.fitsHardwareTexture(w, h)) {
                    return@withLock PdfResult.Failure(
                        PdfError.Unknown("页面尺寸超出设备渲染上限（${w}×${h}），已跳过渲染")
                    )
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.renderPageBitmap(bitmap, 0, 0, w, h, true)
                PdfResult.Success(bitmap)
            } catch (e: SecurityException) {
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("页面渲染失败", t))
            } finally {
                // 顺序不能反：先关 page 再关 doc。
                // 原来只在成功路径上 close，一旦 renderPageBitmap 抛异常，
                // native 文档就永久泄漏 —— 反复打开损坏 PDF 会把内存吃满。
                runCatching { page?.close() }
                runCatching { doc?.let { core().closeDocument(it) } }
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
                var doc: PdfDocument? = null
                try {
                    pfd = resolver.openFileDescriptor(uri, "r")
                        ?: return@withLock PdfResult.Success(emptyList())
                    val core = core()
                    doc = core.newDocument(pfd)
                    val flat = mutableListOf<TocEntry>()
                    fun walk(items: List<PdfDocument.Bookmark>, depth: Int) {
                        items.forEach { b ->
                            flat += TocEntry(b.title ?: "未命名", b.pageIdx.toInt(), depth)
                            walk(b.children ?: emptyList(), depth + 1)
                        }
                    }
                    walk(core.getTableOfContents(doc) ?: emptyList(), 0)
                    PdfResult.Success(flat)
                } catch (t: Throwable) {
                    PdfResult.Success(emptyList())
                } finally {
                    runCatching { doc?.let { core().closeDocument(it) } }
                    runCatching { pfd?.close() }
                }
            }
        }

    /** 页面尺寸（pt） */
    data class PageSize(val widthPt: Float, val heightPt: Float, val rotation: Int)

    // ------------------------------------------------------------------
    // 常驻文档会话（v1.3.5）
    // ------------------------------------------------------------------

    /**
     * 一次打开、多次渲染的文档会话。
     *
     * ## 为什么要这个
     *
     * 原来 [renderPage] 每渲染一页都要 `openFileDescriptor` + `newDocument`
     * + `closeDocument` + `close`，全部串行在 [MUTEX] 上。连续滚动时
     * LazyColumn 一次可见 2~3 页，每页都走一遍打开流程 —— 一本 399 页的书
     * 打开后要等第一次「打开 + 解析 + 渲染」三连完成，界面上就是一个
     * 转不完的圈。
     *
     * 会话把「打开文档」的开销从**每页一次**降到**每本书一次**：
     * - 打开后缓存 `PdfDocument` 与文件描述符，渲染时只 `openPage` + `render`
     * - 自带独立互斥锁，不再和 [pageCount] / [tableOfContents] 抢全局锁
     * - 用完必须 [close]；上层在离开阅读页时调用
     *
     * 注意：PDFium 的 `PdfDocument` 仍然不是线程安全的，同一会话内的
     * 所有调用都由 [lock] 串行化。不同会话之间互不影响。
     */
    class DocSession internal constructor(
        private val doc: PdfDocument,
        private val pfd: ParcelFileDescriptor,
        val pageCount: Int,
        private val password: String?
    ) {
        private val lock = Mutex()
        private var closed = false
        private val pages = object : LinkedHashMap<Int, PdfPage>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>?): Boolean {
                if (size <= CACHED_PAGES) return false
                // 被淘汰的 PdfPage 必须显式关闭，否则 native 内存会持续上涨
                runCatching { eldest?.value?.close() }
                return true
            }
        }

        suspend fun render(pageIndex: Int, targetWidthPx: Int): PdfResult<Bitmap> =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    if (closed) return@withLock PdfResult.Failure(PdfError.Unknown("文档已关闭"))
                    if (pageIndex < 0 || pageIndex >= pageCount) {
                        return@withLock PdfResult.Failure(PdfError.NotFound("页码超出范围"))
                    }
                    try {
                        val page = pages[pageIndex] ?: doc.openPage(pageIndex).also { pages[pageIndex] = it }
                        val (w, h) = renderSize(page, targetWidthPx)
                        if (!PdfLimits.fitsHardwareTexture(w, h)) {
                            return@withLock PdfResult.Failure(
                                PdfError.Unknown("页面尺寸超出设备渲染上限（${w}×${h}），已跳过渲染")
                            )
                        }
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.renderPageBitmap(bitmap, 0, 0, w, h, true)
                        PdfResult.Success(bitmap)
                    } catch (t: Throwable) {
                        if (t is SecurityException) PdfResult.Failure(PdfError.PermissionDenied())
                        else PdfResult.Failure(PdfError.Unknown("页面渲染失败", t))
                    }
                }
            }

        /** 页面点位尺寸，用于布局占位（避免渲染前高度为 0 导致跳动） */
        suspend fun pageSize(pageIndex: Int): PdfResult<PageSize> = withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock PdfResult.Failure(PdfError.Unknown("文档已关闭"))
                try {
                    val page = pages[pageIndex] ?: doc.openPage(pageIndex).also { pages[pageIndex] = it }
                    PdfResult.Success(
                        PageSize(
                            widthPt = page.getPageWidthPoint().toFloat(),
                            heightPt = page.getPageHeightPoint().toFloat(),
                            rotation = page.getPageRotation()
                        )
                    )
                } catch (t: Throwable) {
                    PdfResult.Failure(PdfError.Unknown("读取页面尺寸失败", t))
                }
            }
        }

        suspend fun toc(): List<TocEntry> = withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock emptyList()
                runCatching {
                    val flat = mutableListOf<TocEntry>()
                    fun walk(items: List<PdfDocument.Bookmark>, depth: Int) {
                        items.forEach { b ->
                            flat += TocEntry(b.title ?: "未命名", b.pageIdx.toInt(), depth)
                            walk(b.children ?: emptyList(), depth + 1)
                        }
                    }
                    walk(coreInstance?.getTableOfContents(doc) ?: emptyList(), 0)
                    flat
                }.getOrDefault(emptyList())
            }
        }

        suspend fun close() {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    if (closed) return@withLock
                    closed = true
                    pages.values.forEach { p -> runCatching { p.close() } }
                    pages.clear()
                    runCatching { coreInstance?.closeDocument(doc) }
                    runCatching { pfd.close() }
                }
            }
        }

        companion object {
            /** 常驻缓存的 PdfPage 数量：连续滚动时通常 2~3 页可见，留 6 页余量 */
            private const val CACHED_PAGES = 6
        }
    }

    /**
     * 打开一个文档会话。
     *
     * 返回的 [DocSession] 由调用方负责 [DocSession.close]。
     */
    suspend fun openSession(
        resolver: ContentResolver,
        uri: Uri,
        password: String? = null
    ): PdfResult<DocSession> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                val doc = core.newDocument(pfd, password ?: "")
                val count = doc.getPageCount().toInt()
                if (count <= 0) {
                    runCatching { core.closeDocument(doc) }
                    runCatching { pfd.close() }
                    return@withLock PdfResult.Failure(PdfError.Corrupted("文档没有可读页面"))
                }
                PdfResult.Success(DocSession(doc, pfd, count, password))
            } catch (e: SecurityException) {
                runCatching { pfd?.close() }
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                runCatching { pfd?.close() }
                val msg = t.message ?: ""
                when {
                    msg.contains("password", true) -> PdfResult.Failure(PdfError.WrongPassword())
                    msg.contains("security", true) -> PdfResult.Failure(PdfError.PasswordRequired())
                    msg.contains("denied", true) -> PdfResult.Failure(PdfError.PermissionDenied())
                    else -> PdfResult.Failure(PdfError.Unknown("打开文档失败", t))
                }
            }
        }
    }


    suspend fun pageSize(
        resolver: ContentResolver,
        uri: Uri,
        pageIndex: Int
    ): PdfResult<PageSize> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            var pfd: ParcelFileDescriptor? = null
            var doc: PdfDocument? = null
            var pg: PdfPage? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                doc = core.newDocument(pfd)
                pg = doc.openPage(pageIndex)
                PdfResult.Success(
                    PageSize(
                        widthPt = pg.getPageWidthPoint().toFloat(),
                        heightPt = pg.getPageHeightPoint().toFloat(),
                        rotation = pg.getPageRotation()
                    )
                )
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("读取页面尺寸失败", t))
            } finally {
                runCatching { pg?.close() }
                runCatching { doc?.let { core().closeDocument(it) } }
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
                var doc: PdfDocument? = null
                try {
                    pfd = resolver.openFileDescriptor(uri, "r")
                        ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                    val core = core()
                    doc = core.newDocument(pfd)
                    val m = doc.getDocumentMeta()
                    PdfResult.Success(Meta(m.title, m.author, m.subject, m.keywords))
                } catch (t: Throwable) {
                    PdfResult.Failure(PdfError.Unknown("读取元数据失败", t))
                } finally {
                    runCatching { doc?.let { core().closeDocument(it) } }
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
            var doc: PdfDocument? = null
            var textPage: PdfTextPage? = null
            var find: FindResult? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return@withLock PdfResult.Failure(PdfError.NotFound())
                val core = core()
                doc = core.newDocument(pfd, password ?: "")
                val total: Int = doc.getPageCount().toInt()
                val hits = mutableListOf<SearchHit>()
                val flags = if (matchCase) setOf(FindFlags.MatchCase) else emptySet()

                for (p in 0 until total) {
                    if (hits.size >= maxResults) break
                    onProgress(p, total)

                    // 单页失败不能拖垮整次搜索：损坏页 openPage/openTextPage 会抛，
                    // 原来的写法会让异常直接跳到 finally，当前页的 page/textPage
                    // 没被关掉 —— native 内存越搜越涨。这里单页兜住并 continue。
                    var page: PdfPage? = null
                    var tp: PdfTextPage? = null
                    try {
                        page = doc.openPage(p)
                        // 页面点位尺寸，用于把 char box 归一化
                        val pageW = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
                        val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                        tp = page.openTextPage()
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
                    } catch (t: Throwable) {
                        // 单页解析失败：跳过该页，保留已找到的结果
                        if (t is SecurityException) throw t
                    } finally {
                        textPage = null
                        runCatching { find?.closeFind() }
                        find = null
                        runCatching { tp?.close() }
                        runCatching { page?.close() }
                    }
                }

                runCatching { find?.closeFind() }
                runCatching { textPage?.close() }
                onProgress(total, total)
                PdfResult.Success(hits)
            } catch (e: SecurityException) {
                PdfResult.Failure(PdfError.PermissionDenied())
            } catch (t: Throwable) {
                PdfResult.Failure(PdfError.Unknown("搜索失败", t))
            } finally {
                runCatching { find?.closeFind() }
                runCatching { textPage?.close() }
                runCatching { doc?.let { core().closeDocument(it) } }
                runCatching { pfd?.close() }
            }
        }
    }

    const val MAX_RENDER_WIDTH = 2048
}

/**
 * 页面渲染尺寸计算 —— 所有渲染路径的唯一入口。
 *
 * ## 为什么必须限死
 *
 * v1.3.2 真机（OnePlus PKG110 / Android 17）崩溃：
 *
 * ```
 * RuntimeException: Canvas: trying to draw too large(527487968bytes) bitmap.
 *   at RecordingCanvas.throwIfCannotDraw
 * ```
 *
 * 5.27 亿字节 ≈ 5 亿像素（ARGB_8888 每像素 4 字节）。这不是我们主动建的图太大，
 * 而是 PDF 页面**本身的尺寸**骇人 —— 常见于：
 *
 * - CAD / 工程图 / 地图导出的 PDF，单页 MediaBox 动辄上万点
 * - 扫描件以 2000+ dpi 存储
 * - PDF 内部把页面挂在极端缩放矩阵上
 *
 * 陷阱在于 `getPageHeight(width)` 的行为：它按**给定宽度缩放后**返回高度。
 * 当页面长宽比极度悬殊（比如 1:400 的施工图），把宽度设为 1080px 后
 * 高度会达到几十万像素，乘出来就是天文数字。原来只限制了宽度，
 * 等于没有约束面积 —— `MAX_RENDER_WIDTH` 形同虚设。
 *
 * ## 策略
 *
 * 先按目标宽度算出比例，再按两条硬约束收敛：
 *
 * 1. `MAX_PIXELS`：总像素上限（约 4000 万，16MB 位图），保证任何情况下不会撑爆显存
 * 2. `MAX_ASPECT`：长宽比上限（16:1），超过就说明是异形页面，
 *    此时宁可让页面偏小也不让它变成一根针
 *
 * 收敛后重新算高度，并**再次**用像素上限兜一次 —— 因为 PDF 里可能存在
 * 非等比缩放的页面矩阵，`w * h` 不一定等于预期的比例。
 *
 * @return (宽, 高)，均已保证 >= 1 且总面积 <= MAX_PIXELS
 */
internal fun renderSize(page: PdfPage, targetWidthPx: Int): Pair<Int, Int> {
    val wantW = targetWidthPx.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH)
    var w = wantW
    var h = page.getPageHeight(w)

    // 页面句柄失效时 PDFium 返回 -1，此时退回 A4 比例占位，避免建出负尺寸位图
    if (h <= 0) {
        return w to (w * 1.4142f).toInt().coerceAtLeast(1)
    }

    // ① 长宽比约束：超长页面先缩宽度，让高度落回合理区间
    val maxH = (w * PdfLimits.MAX_ASPECT).toInt()
    if (h > maxH && maxH > 0) {
        val shrink = maxH.toFloat() / h.toFloat()
        w = (w * shrink).toInt().coerceAtLeast(64)
        h = page.getPageHeight(w)
        if (h <= 0) {
            return w to (w * 1.4142f).toInt().coerceAtLeast(1)
        }
    }

    // ② 像素总量约束：无论如何不得超过 MAX_PIXELS
    val pixels = w.toLong() * h.toLong()
    if (pixels > PdfLimits.MAX_PIXELS) {
        val scale = kotlin.math.sqrt(PdfLimits.MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        w = (w * scale).toInt().coerceAtLeast(48)
        // 宽度变了高度必须重新问 PDFium，不能直接乘比例
        val h2 = page.getPageHeight(w)
        h = if (h2 > 0) h2 else (w * 1.4142f).toInt()
    }

    // ③ 最后兜底：极端页面矩阵下 ② 可能仍未收敛，此时直接截断高度
    var safeH = h.coerceAtLeast(1)
    val maxHByPixels = (PdfLimits.MAX_PIXELS / w.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    if (safeH > maxHByPixels) safeH = maxHByPixels

    return w to safeH
}

/** 渲染硬约束（单位：像素） */
object PdfLimits {
    /** 单页位图总像素上限：4000 万 ≈ 160MB 位图，与 W^2 = 6324^2 相当 */
    const val MAX_PIXELS = 40_000_000L

    /** 长宽比上限：超过 16:1 的页面视为异形，缩宽度处理 */
    const val MAX_ASPECT = 16f

    /** 位图字节数上限（ARGB_8888 = 4 字节/像素） */
    const val MAX_BITMAP_BYTES = MAX_PIXELS * 4L

    /**
     * GPU 单边纹理上限。
     *
     * 业界保底值 8192：几乎所有支持 Vulkan/GLES3 的设备都至少这么宽。
     * 超出后 RecordingCanvas 会抛
     * `trying to draw too large(Nbytes) bitmap`。
     */
    const val MAX_TEXTURE_EDGE = 8192

    /** 位图单边上限，取纹理上限与像素上限的较小者 */
    fun fitsHardwareTexture(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w > MAX_TEXTURE_EDGE || h > MAX_TEXTURE_EDGE) return false
        return w.toLong() * h.toLong() <= MAX_PIXELS
    }
}
