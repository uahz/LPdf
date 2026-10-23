package com.lpdf.reader.data.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * 分段式的 `PdfDocument` 写入器（v1.6.2 引入，用于解决工具箱的 OOM）。
 *
 * ## 为什么需要它
 *
 * `android.graphics.pdf.PdfDocument` 的机制是：每页 `startPage` → `finishPage`
 * 之后，页面的**绘制命令全部留在内存里**，直到 `writeTo()` 才一次性序列化。
 * 页数越多占用越大，而且**没有任何 API 能中途释放已经 finish 的页**。
 *
 * 对 LPdf 来说这条路必然炸：
 * - 单页底图最宽 1600px（ARGB_8888 ≈ 10MB），而 PdfDocument 内部还要按矢量命令重放
 * - 在线导入上限 100MB，可能是几百页的扫描件
 * - 压缩 / 拆分 / 加水印 / 导出批注四条管线**全是这个模式**
 *
 * ## 做法
 *
 * 每写满 [pagesPerSegment] 页就落盘成一个**分段 PDF**，内存随即清空（重新 new 一个
 * `PdfDocument`）。全部页写完后，用 PDFBox 把分段按顺序合并成最终文件。
 *
 * 峰值内存从「全书页命令」降到「一个分段的页命令」，与总页数**无关**。
 *
 * ## 为什么合并用 PDFBox 而不是「再画一遍」
 *
 * PDFBox 的合并是对象级拼接，不重新栅格化 —— 不会二次损失清晰度，也不会再吃位图内存。
 * pdfium 的 `saveAsCopy` 在这里帮不上忙：它只能整份另存，没有页面导入能力
 * （已用 `javap` 核对 `PdfPage` / `PdfDocument` 全部公开方法）。
 *
 * ## 与 pdfium 写入通路的约定
 *
 * 见 PROGRESS.md 附录 A.7：PDFBox 的产物只走 PDFBox 自己的 `save()`，
 * 不再交给 pdfium 保存，否则会被 pdfium 的内存快照覆盖。
 */
internal object SegmentedPdfWriter {

    /**
     * 每个分段页数。
     *
     * 8 页的依据：单页位图渲染上限约 10MB，8 页 ≈ 80MB 的中间开销 ——
     * 比原来「100 页全留内存」低一个数量级，又不会因为分段太碎而产生几十次
     * 文件打开/合并（每次合并都要读一遍结构，太碎反而变慢）。
     *
     * 这个值的语义是「上限而非固定值」，实际按 [segmentSize] 动态收敛。
     */
    const val DEFAULT_PAGES_PER_SEGMENT = 8

    /**
     * 按「总页数」收窄分段大小。
     *
     * 小文档（≤ 20 页）不用分那么多段，减少合并次数；大文档固定 8 页，
     * 保证峰值内存可控。抽成纯函数是为了能单测 —— 这里算错会导致
     * 「段数 × 单段页数 ≠ 总页数」，最后几页凭空消失。
     */
    fun segmentSize(totalPages: Int, preferred: Int = DEFAULT_PAGES_PER_SEGMENT): Int {
        if (totalPages <= 0) return 1
        val cap = when {
            totalPages <= 20 -> totalPages   // 一次写完，不分段
            totalPages <= 60 -> 16
            else -> preferred
        }
        return cap.coerceAtLeast(1)
    }

    /**
     * 分段写入的进度回调。
     *
     * @param pageIndex 0-based 当前页
     * @param total 总页数
     */
    fun interface PageProgress {
        fun onPage(pageIndex: Int, total: Int)
    }

    /**
     * 把 `totalPages` 页按 [segmentSize] 分段写入 [outFile]。
     *
     * @param totalPages 总页数，必须 > 0
     * @param dir 分段临时文件目录（调用方负责清理）
     * @param preferredSegment 每段页数上限，默认 [DEFAULT_PAGES_PER_SEGMENT]
     * @param onPage 每完成一页回调一次；pageIndex 单调递增
     * @param drawPage 绘制第 index 页。实现里必须自己 `startPage` / `finishPage`
     *        并处理位图回收 —— 位图不能在 `finishPage` 之后继续持有。
     */
    suspend fun write(
        totalPages: Int,
        outFile: File,
        dir: File,
        preferredSegment: Int = DEFAULT_PAGES_PER_SEGMENT,
        onPage: PageProgress = PageProgress { _, _ -> },
        drawPage: suspend (doc: PdfDocument, index: Int) -> Unit
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        if (totalPages <= 0) {
            return@withContext PdfResult.Failure(PdfError.Corrupted("文档没有可读页面"))
        }

        dir.mkdirs()
        val size = segmentSize(totalPages, preferredSegment)
        val segments = ArrayList<File>()

        try {
            var done = 0
            while (done < totalPages) {
                coroutineContext.ensureActive()
                val end = (done + size).coerceAtMost(totalPages)

                // 分段用独立文件，序号补零保证合并时排序稳定（不依赖文件系统顺序）
                val segFile = File(dir, "seg_${segments.size.toString().padStart(4, '0')}.pdf")
                val doc = PdfDocument()
                try {
                    for (i in done until end) {
                        coroutineContext.ensureActive()
                        drawPage(doc, i)
                        onPage.onPage(i, totalPages)
                    }
                    segFile.outputStream().use { doc.writeTo(it) }
                } finally {
                    // 关键：close 之后这个分段的页命令才真正从内存消失
                    runCatching { doc.close() }
                }
                segments += segFile
                done = end
            }

            merge(segments, outFile)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            when (t) {
                is SecurityException -> PdfResult.Failure(PdfError.PermissionDenied())
                is OutOfMemoryError -> PdfResult.Failure(
                    PdfError.WriteFailed("内存不足，请关闭其他应用后重试，或先拆分再操作")
                )
                is PdfError -> PdfResult.Failure(t)
                else -> PdfResult.Failure(
                    PdfError.WriteFailed("写入 PDF 失败：${t.message ?: t.javaClass.simpleName}")
                )
            }
        } finally {
            segments.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 单页也走同一条路，避免两套代码。
     *
     * 页数 = 1 时分段不存在，直接在目标路径上写，少一次合并拷贝。
     */
    suspend fun writeOne(
        outFile: File,
        draw: suspend (doc: PdfDocument) -> Unit
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            draw(doc)
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { doc.writeTo(it) }
            PdfResult.Success(outFile)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            PdfResult.Failure(PdfError.WriteFailed("写入 PDF 失败：${t.message}"))
        } finally {
            runCatching { doc.close() }
        }
    }

    /**
     * 对象级合并分段（PDFBox）。
     *
     * 全程不碰位图，内存占用只与「一份文档的交叉引用表」有关。
     * 合并失败时把原因包成 [PdfError.WriteFailed]，调用方照常展示。
     */
    fun merge(segments: List<File>, outFile: File): PdfResult<File> {
        if (segments.isEmpty()) {
            return PdfResult.Failure(PdfError.Corrupted("没有可写入的页面"))
        }
        outFile.parentFile?.mkdirs()

        // 只有一段时直接搬过去，省一次完整解析
        if (segments.size == 1) {
            val only = segments.first()
            return runCatching {
                only.copyTo(outFile, overwrite = true)
                PdfResult.Success(outFile)
            }.getOrElse { PdfResult.Failure(PdfError.WriteFailed("写入 PDF 失败：${it.message}")) }
        }

        return try {
            com.tom_roush.pdfbox.multipdf.PDFMergerUtility().apply {
                segments.forEach { addSource(it) }
                destinationFileName = outFile.absolutePath
                mergeDocuments(
                    com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()
                )
            }
            PdfResult.Success(outFile)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            PdfResult.Failure(PdfError.WriteFailed("合并分段失败：${t.message}"))
        }
    }

    /**
     * 把一段标注/水印绘制在页面底图上并 finishPage。
     *
     * 四条管线都要「底图 + 叠加层」，抽出来避免各自写一遍
     * （原来 `PdfWriterEngine.compose` / `watermark` / `PdfFlattener` 各写了一份，
     * 其中两处在异常路径上漏回收位图）。
     */
    fun drawBitmapPage(
        doc: PdfDocument,
        pageNo: Int,
        widthPt: Int,
        heightPt: Int,
        rotation: Int = 0,
        overlay: ((Canvas, Int, Int) -> Unit)? = null,
        draw: (Canvas) -> Unit
    ) {
        val info = PdfDocument.PageInfo
            .Builder(widthPt.coerceAtLeast(1), heightPt.coerceAtLeast(1), pageNo)
            .create()
        val page = doc.startPage(info)
        try {
            val canvas = page.canvas
            if (rotation % 360 != 0) {
                canvas.save()
                canvas.rotate(rotation.toFloat(), widthPt / 2f, heightPt / 2f)
                draw(canvas)
                canvas.restore()
            } else {
                draw(canvas)
            }
            overlay?.invoke(canvas, widthPt, heightPt)
        } finally {
            doc.finishPage(page)
        }
    }

    /** 白底填充：位图未覆盖到的边缘要保持白纸，而不是透明（某些阅读器会显示成黑色） */
    fun whiteBackground(canvas: Canvas, widthPt: Int, heightPt: Int) {
        canvas.drawColor(Color.WHITE)
    }
}
