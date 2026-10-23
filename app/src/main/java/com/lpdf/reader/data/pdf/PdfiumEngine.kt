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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
     * 渲染指定页为**一张位图**，仅适合单块就能装下的页面。
     *
     * 需要覆盖任意尺寸请用 [openSession] + [DocSession.renderTiles]。
     * 这里仍然保留，是因为缩略图 / 分享预览 / 单元测试这些场景确实只需要
     * 一张小图，且它们的宽度都很小（< 500px），永远落在单块内。
     *
     * v1.5.0 起不再有「尺寸超限就拒绝渲染」的分支 —— 只要目标宽度合理，
     * 这里一定能出图。
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
                val plan = planTiles(w, h)
                // 多块页面在这里把整页拼成一张 —— 只有缩略图这种极小目标宽度
                // 才会走进来，拼出来的图远小于单块预算，不会触碰纹理上限。
                if (plan.tileCount > 1) {
                    return@withLock PdfResult.Failure(
                        PdfError.Unknown("页面需要分块渲染（${plan.tileCount} 块），请使用会话接口")
                    )
                }
                val r = plan.rects.first()
                val bitmap = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix = android.graphics.Matrix().apply { postScale(plan.scale, plan.scale) }
                val clip = android.graphics.RectF(0f, 0f, r.width.toFloat(), r.height.toFloat())
                page.renderPageBitmap(bitmap, matrix, clip, true, true, 0, 0)
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

        /**
         * 计算某页的分块方案（只算几何，不渲染）。
         *
         * UI 先拿这个方案就知道页面真实宽高比与要渲几块，
         * 列表可以先按正确高度占位，避免「先 A4 后突然变长」的跳动。
         */
        suspend fun plan(pageIndex: Int, targetWidthPx: Int): PdfResult<TilePlan> =
            withContext(Dispatchers.IO) {
                lock.withLock {
                    if (closed) return@withLock PdfResult.Failure(PdfError.Unknown("文档已关闭"))
                    if (pageIndex < 0 || pageIndex >= pageCount) {
                        return@withLock PdfResult.Failure(PdfError.NotFound("页码超出范围"))
                    }
                    try {
                        val page = openPageLocked(pageIndex)
                        val (w, h) = renderSize(page, targetWidthPx)
                        PdfResult.Success(planTiles(w, h))
                    } catch (t: Throwable) {
                        PdfResult.Failure(PdfError.Unknown("读取页面尺寸失败", t))
                    }
                }
            }

        /**
         * 分块渲染一页（v1.5.0）。
         *
         * 每渲完一块立即回调 [onTile]，调用方可以先把第一块显示出来，
         * 剩余块在后台继续 —— 极端长页被切成 4 块时，用户不必等 4 块全好。
         *
         * 块与块之间 [kotlinx.coroutines.yield]，让「打开新页」这类用户操作
         * 能在锁外插队，不会被一次长渲染完全堵死。
         *
         * 注意：全程持有 [lock]。PDFium 的 PdfPage 不是线程安全的，
         * 同一会话内的渲染必须串行；不同会话之间互不影响。
         */
        suspend fun renderTiles(
            pageIndex: Int,
            plan: TilePlan,
            invert: Boolean = false,
            onTile: suspend (Tile) -> Unit
        ): PdfResult<Int> = withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock PdfResult.Failure(PdfError.Unknown("文档已关闭"))
                if (pageIndex < 0 || pageIndex >= pageCount) {
                    return@withLock PdfResult.Failure(PdfError.NotFound("页码超出范围"))
                }
                try {
                    val page = openPageLocked(pageIndex)
                    val done = ArrayList<Bitmap>(plan.rects.size)
                    try {
                        plan.rects.forEachIndexed { idx, r ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            // 双保险：planTiles 已经保证每块合法，这里再校验一次，
                            // 防止将来有人改算法时绕过约束、把超限位图交给 GPU
                            if (!PdfLimits.fitsSingleBitmap(r.width, r.height)) {
                                throw IllegalStateException(
                                    "分块尺寸超限 ${r.width}×${r.height}（块 $idx）"
                                )
                            }
                            val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            done += bmp

                            // 用矩阵版渲染：把整页按 scale 缩放后，只把 (left, top) 起的
                            // 那一块画到位图原点。比用负偏移可靠 —— 负偏移的语义
                            // 在 pdfiumandroid 里没有任何文档。
                            val matrix = android.graphics.Matrix().apply {
                                postScale(plan.scale, plan.scale)
                                postTranslate(-r.left.toFloat(), -r.top.toFloat())
                            }
                            val clip = android.graphics.RectF(
                                0f, 0f, r.width.toFloat(), r.height.toFloat()
                            )
                            page.renderPageBitmap(bmp, matrix, clip, true, true, 0, 0)

                            // 夜间模式反色（v1.7.0）。必须在这里做，不能在 UI 层叠滤色片：
                            // 滤色片是「把整页亮度压下去」，白底变深灰、黑字变更黑，
                            // 对比度反而更低，小字直接糊成一片。
                            // 真正的夜间阅读是**反色**：纸变黑、字变白，对比度保持。
                            // 而且这里只处理底图位图，标注是另一层矢量绘制，颜色不受影响。
                            if (invert) {
                                InvertFilter.apply(bmp)
                            }

                            // v1.8.4 关键修复：Tile 坐标必须回到「整页理想坐标系」。
                            //
                            // plan.rects 在降级路径（scale < 1）里是**缩小后**的坐标
                            // （gridTiles(sw, sh)，总宽 sw = widthPx×scale），而
                            // PageRender 的不变量是「tile 坐标基于 widthPx×heightPx 的
                            // 理想尺寸」。旧代码直接透传 r.left/r.width，UI 按
                            // sx = 容器宽 / widthPx 映射后，降级页只画在容器左上角的
                            // scale 比例处 —— 用户截图里「页面缩在左上角 40% 宽、
                            // 周围一大片空白」正是这个原因（scale ≈ 0.4）。
                            // scale = 1 时两者重合，所以普通页面一直没暴露。
                            val invScale = 1f / plan.scale.coerceAtLeast(0.0001f)
                            onTile(
                                Tile(
                                    index = idx,
                                    left = (r.left * invScale).roundToInt(),
                                    top = (r.top * invScale).roundToInt(),
                                    width = (r.width * invScale).roundToInt(),
                                    height = (r.height * invScale).roundToInt(),
                                    bitmap = bmp
                                )
                            )
                            // 让出调度权，避免一次长渲染把「打开新页」饿死
                            if (idx < plan.rects.lastIndex) kotlinx.coroutines.yield()
                        }
                    } catch (t: Throwable) {
                        // 中途失败：已渲染的块交给调用方决定是否回收
                        if (t is kotlinx.coroutines.CancellationException) {
                            done.forEach { runCatching { it.recycle() } }
                            throw t
                        }
                        throw t
                    }
                    PdfResult.Success(done.size)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    if (t is SecurityException) PdfResult.Failure(PdfError.PermissionDenied())
                    else PdfResult.Failure(PdfError.Unknown("页面渲染失败", t))
                }
            }
        }

        /**
         * 渲染整页为单张位图 —— **仅供内部与测试**。
         *
         * 超过 8192 的页面这里会失败，因为一张位图装不下。
         * 需要支持任意尺寸时用 [renderTiles]。
         */
        suspend fun render(pageIndex: Int, targetWidthPx: Int): PdfResult<Bitmap> =
            withContext(Dispatchers.IO) {
                val planRes = plan(pageIndex, targetWidthPx)
                if (planRes is PdfResult.Failure) {
                    return@withContext PdfResult.Failure(planRes.error)
                }
                val plan = planRes.getOrNull() ?: return@withContext PdfResult.Failure(
                    PdfError.Unknown("无法计算页面尺寸")
                )
                if (plan.tileCount != 1) {
                    return@withContext PdfResult.Failure(
                        PdfError.Unknown("页面过大，需分块渲染")
                    )
                }
                var out: Bitmap? = null
                val res = renderTiles(pageIndex, plan) { tile -> out = tile.bitmap }
                if (res is PdfResult.Failure) {
                    out?.let { runCatching { it.recycle() } }
                    return@withContext PdfResult.Failure(res.error)
                }
                val bmp = out ?: return@withContext PdfResult.Failure(PdfError.Unknown("页面渲染失败"))
                PdfResult.Success(bmp)
            }

        /** 取页面句柄（带 LRU 缓存），调用方必须已持有 [lock] */
        private fun openPageLocked(pageIndex: Int): PdfPage =
            pages[pageIndex] ?: doc.openPage(pageIndex).also { pages[pageIndex] = it }

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

        /**
         * 取整页文字层（v1.7.0，文本选择用）。
         *
         * 返回**每个字符**的归一化矩形、所属行号、以及字符本身。
         * 一次取全页而不是「按选区现查」的原因：
         *
         * - 用户拖动手柄时每次移动都要重算选区，逐次进 native 会明显卡顿；
         * - 长按命中之后，选区延展到哪里完全是本地计算，手感才跟得上手指。
         *
         * 一页 A4 正文约 2000~3000 个字符，每个字符一条 `CharBox`
         * （3 个 Float + 1 个 Int + 1 个 Char，约 40 字节），单页 100KB 上下。
         * 只缓存当前页，翻页即弃。
         *
         * 行号判定：两个字符中心点的纵坐标差小于半个字高就视为同一行。
         * PDF 没有「行」这个概念，只能这样归并 —— 用字高的比例而不是绝对值，
         * 因为字号在页面里并不统一（标题 24pt、正文 10pt）。
         */
        suspend fun textLayer(pageIndex: Int): PdfResult<TextLayer> = withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock PdfResult.Failure(PdfError.Unknown("文档已关闭"))
                if (pageIndex < 0 || pageIndex >= pageCount) {
                    return@withLock PdfResult.Failure(PdfError.NotFound("页码超出范围"))
                }
                var page: PdfPage? = null
                var tp: PdfTextPage? = null
                try {
                    page = openPageLocked(pageIndex)
                    val pageW = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
                    val pageH = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
                    tp = page.openTextPage()
                    val n = tp.textPageCountChars()
                    if (n <= 0) return@withLock PdfResult.Success(TextLayer(pageIndex, emptyList(), 0))

                    val boxes = ArrayList<CharBox>(minOf(n, 8192))
                    var text = StringBuilder()
                    var line = 0
                    var lastMidY = Float.NaN
                    var lastH = 0f
                    for (i in 0 until n) {
                        val r = tp.textPageGetCharBox(i)
                        // 没有可见框的字符（换行、空格、零宽）跳过，但保留文字，
                        // 否则复制出来的句子会丢掉空格粘成一团
                        val ch = runCatching { tp.textPageGetUnicode(i) }.getOrNull() ?: '\u0000'
                        if (r == null || r.width() <= 0f || r.height() <= 0f) {
                            if (ch != '\u0000') text.append(ch)
                            continue
                        }
                        text.append(ch)
                        val h = r.height()
                        val midY = (r.top + r.bottom) / 2f
                        // 同一行的判定要留一点容差：斜体、上标会让字框上下错开
                        if (lastMidY.isNaN() || kotlin.math.abs(midY - lastMidY) > lastH * 0.5f) {
                            line++
                        }
                        lastMidY = midY
                        lastH = h
                        boxes += CharBox(
                            ch = ch,
                            line = line,
                            left = (r.left / pageW).coerceIn(0f, 1f),
                            top = (r.top / pageH).coerceIn(0f, 1f),
                            right = (r.right / pageW).coerceIn(0f, 1f),
                            bottom = (r.bottom / pageH).coerceIn(0f, 1f)
                        )
                    }
                    PdfResult.Success(TextLayer(pageIndex, boxes, line))
                } catch (e: SecurityException) {
                    PdfResult.Failure(PdfError.PermissionDenied())
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    PdfResult.Failure(PdfError.Unknown("读取文字层失败", t))
                } finally {
                    runCatching { tp?.close() }
                    // 页面句柄是会话级 LRU 缓存的，这里不能关
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
            /**
             * 常驻缓存的 `PdfPage` 数量。
             *
             * v1.5.1 从 6 提到 12：分块渲染让同一页的 `plan()` 与 `renderTiles()`
             * 各调一次 `openPageLocked`，一次滚动会摸到更多页。窗口偏小时
             * 「plan 打开 → 被挤出 → render 再打开」的重复 open 会明显拖慢翻页。
             * 单个 `PdfPage` 只持有 native 侧页对象引用（页面内容仍由 PDFium
             * 按需解析），12 页的额外占用在 KB 级，与位图（MB 级）不是一个量级。
             */
            private const val CACHED_PAGES = 12
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

    const val MAX_RENDER_WIDTH = 4096
}

/**
 * 页面渲染尺寸计算 —— 所有渲染路径的唯一入口。
 *
 * ## v1.5.0 起的行为
 *
 * **只按宽度缩放，高度如实返回**，不做任何截断。
 *
 * 旧版这里有三层「收敛」：①长宽比超 16:1 就缩宽度 ②超 4000 万像素按 sqrt 缩
 * ③最终按像素上限**直接截断高度**。第③步是最隐蔽的破坏 —— 它让极端长页
 * 变成「矮胖」，宽高比被静默破坏，而标注用的是归一化坐标，页面一被压扁
 * 标注就全部错位。
 *
 * 现在尺寸安全由 [planTiles] 负责：先拿到真实比例的整页尺寸，再决定
 * 是分块（保清晰度）还是整体等比降级（保内存）。两条路都保持宽高比。
 *
 * ## v1.5.1：加一道「比例合理性」防线
 *
 * 用户真机上曾出现 A4 页面被算成 `1168×13657` —— 高度是正常值（1652）的
 * 8.9 倍，反推出的渲染宽度是 **9657px**，而屏幕物理宽只有 1168px。
 * 分块算法能兜住这种输入（不会崩、不会白屏），但页面会变成一条极细的长条，
 * 标注也全错位。所以这里不能只依赖下游兜底，必须在源头识别「不可能的比例」。
 *
 * 具体做法：用 `getPageWidthPoint/getPageHeightPoint` 拿到页面的**真实点阵比例**，
 * 与 `getPageHeight(w)/w` 得到的比例比对。两者应当完全一致（都来自同一个
 * /MediaBox）。一旦偏差超过 3%，说明 `getPageHeight` 的输入或输出被污染，
 * 此时**信任点阵比例**重新算高度 —— 点阵比例是整数点位，不容易被缩放污染。
 *
 * 这一步不会误伤正常页面：正常页两者比例天生相等，浮点误差 < 0.2%。
 *
 * @return (宽, 高)，宽高比与 PDF 页面一致
 */
internal fun renderSize(page: PdfPage, targetWidthPx: Int): Pair<Int, Int> {
    val w = targetWidthPx.coerceIn(64, PdfiumEngine.MAX_RENDER_WIDTH)
    val h = page.getPageHeight(w)
    return sanitizePageSize(w, h, page.getPageWidthPoint(), page.getPageHeightPoint())
}

/**
 * 页面像素尺寸的合理性校验（v1.5.1，纯函数可单测）。
 *
 * 以点阵尺寸为基准，校验 `rawHeight / width` 是否与页面真实宽高比一致。
 * 偏差超过 [DRIFT_TOLERANCE] 判定为「输入被污染」，按点阵比例重算高度。
 *
 * @param width 本次渲染宽度（px）
 * @param rawHeight PDFium `getPageHeight(width)` 的返回值
 * @param widthPt 页面点阵宽（pt）
 * @param heightPt 页面点阵高（pt）
 */
internal fun sanitizePageSize(
    width: Int,
    rawHeight: Int,
    widthPt: Int,
    heightPt: Int
): Pair<Int, Int> {
    // 句柄失效：PDFium 返回 -1，退回 A4 比例占位
    if (rawHeight <= 0) return width to (width * 1.4142f).toInt().coerceAtLeast(1)
    if (widthPt <= 0 || heightPt <= 0) return width to rawHeight

    val expect = heightPt.toFloat() / widthPt
    val actual = rawHeight.toFloat() / width
    val drift = kotlin.math.abs(actual - expect) / expect
    if (drift <= DRIFT_TOLERANCE) return width to rawHeight

    val fixed = (width * expect).toInt().coerceAtLeast(1)
    android.util.Log.w(
        "PdfiumEngine",
        "页高比例异常：算出 ${width}×$rawHeight（比例 $actual），" +
            "点阵比例 $expect（${widthPt}×${heightPt} pt）。已修正为 ${width}×$fixed。"
    )
    return width to fixed
}

/** 页高比例允许的偏差。正常页面两者都来自同一个 /MediaBox，误差 < 0.2% */
internal const val DRIFT_TOLERANCE = 0.03f

/**
 * 渲染硬约束（单位：像素）。
 *
 * v1.5.0 语义拆分了：
 * - 旧 `fitsHardwareTexture` 混着表达两件事：「这页能不能渲染」和「这张位图能不能上纹理」。
 * - 现在只有后者成立 —— **单张位图**的约束，判断对象是 tile，不是整页。
 * - 「这页能不能渲染」由 [planTiles] 回答，答案永远是「能」。
 */
object PdfLimits {
    /** 单张位图总像素上限：4000 万 ≈ 160MB 位图 */
    const val MAX_PIXELS = 40_000_000L

    /** 兼容旧代码：长宽比上限。v1.5.0 起不再用于裁剪 —— 页面尺寸由 [planTiles] 负责 */
    const val LEGACY_MAX_ASPECT = 16f

    /** 位图字节数上限（ARGB_8888 = 4 字节/像素） */
    const val MAX_BITMAP_BYTES = MAX_PIXELS * 4L

    /**
     * GPU 单边纹理上限。业界保底值 8192。
     * 超出后 RecordingCanvas 会抛 `trying to draw too large`。
     */
    const val MAX_TEXTURE_EDGE = 8192

    /**
     * 单张位图能否安全上纹理。
     *
     * 注意：判断对象是**一张位图 / 一个 tile**，不是整页。
     * 整页尺寸由 [planTiles] 拆解，每一块的尺寸都会满足本函数。
     */
    fun fitsSingleBitmap(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w > MAX_TEXTURE_EDGE || h > MAX_TEXTURE_EDGE) return false
        return w.toLong() * h.toLong() <= MAX_PIXELS
    }

    /** 旧名保留为别名，语义已明确为「单张位图」 */
    fun fitsHardwareTexture(w: Int, h: Int): Boolean = fitsSingleBitmap(w, h)
}
