package com.lpdf.reader.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于系统 PdfRenderer 的轻量写入通路。
 *
 * 说明：
 * - 读取/渲染主通路是 PDFium（见 [PdfiumEngine]），因为系统 PdfRenderer 不支持加密文档与原图导出。
 * - 当只需要「合并 / 拆分 / 提取页 / 压缩 / 图片转 PDF」这类页面级操作时，
 *   用系统 PdfRenderer 绘制到 PdfDocument，避免引入重量级第三方写入库。
 * - 全部操作在后台线程串行执行。
 */
object PdfWriterEngine {

    private val lock = Any()

    /**
     * 跨挂起点的串行锁（v1.6.2）。
     *
     * 工具箱的四条管线原来共用 `synchronized(lock)`；改成锁对象是因为
     * 分段写入需要中途让出调度（`yield`），而 synchronized 里不允许挂起。
     * 与 [lock] 并存不冲突：一个管同步段，一个管异步段。
     */
    private val writeMutex = Mutex()

    /** 单页栅格化：把源 PDF 的指定页画到目标 PdfDocument 上 */
    private fun drawPageTo(
        src: PdfRenderer.Page,
        target: android.graphics.pdf.PdfDocument,
        destPageNo: Int,
        quality: Int = 100,
        scale: Float = 1f
    ) {
        val wPt = (src.width * scale).toInt().coerceAtLeast(1)
        val hPt = (src.height * scale).toInt().coerceAtLeast(1)
        val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(wPt, hPt, destPageNo).create()
        val page = target.startPage(info)
        val bmp = Bitmap.createBitmap(
            (wPt * 2).coerceAtMost(1400),
            (hPt * 2).coerceAtMost(2000),
            Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(Color.WHITE)
        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        target.finishPage(page)
        bmp.recycle()
    }

    /** 提取页（含旋转） */
    data class PageRef(val file: File, val pageIndex: Int, val rotation: Int = 0)

    /**
     * 按顺序把多个页写入一个新 PDF。
     *
     * v1.6.2 起改为**分段落盘**（[SegmentedPdfWriter]）：
     * 旧实现把全部页的绘制命令留在同一个 `PdfDocument` 里，到 `writeTo()` 才落盘，
     * 页数一多就 OOM（100MB 的扫描件可达数百页，单页底图还有 ~10MB）。
     * 现在每 8 页写成一个分段 PDF、随即 `close()` 释放，最后用 PDFBox 对象级合并。
     * 峰值内存与总页数解耦。
     *
     * @param qualityScale 0.25~1.0，用于「压缩」场景（越低体积越小）
     */
    suspend fun compose(
        pages: List<PageRef>,
        outFile: File,
        qualityScale: Float = 1f,
        progress: (Float) -> Unit = {}
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        // 用 runBlocking + Mutex 而不是 synchronized(lock)：
        // 分段写入内部有挂起点（yield 让出调度、避免长渲染饿死其他任务），
        // 而 Kotlin 禁止在 synchronized 临界区里挂起 —— 编不过是一种保护，
        // 提醒我们临界区跨挂起点会阻塞整个线程池。
        writeMutex.withLock {
            if (pages.isEmpty()) {
                return@withLock PdfResult.Failure(PdfError.Corrupted("没有可写入的页面"))
            }
            val tmpDir = File(outFile.absoluteFile.parentFile ?: File("."), "seg_${System.nanoTime()}")
            try {
                val res = SegmentedPdfWriter.write(
                    totalPages = pages.size,
                    outFile = outFile,
                    dir = tmpDir,
                    onPage = { idx, total ->
                        progress((idx + 1).toFloat() / total)
                    }
                ) { doc, index ->
                    val ref = pages[index]
                    drawRefTo(doc, ref, index + 1, qualityScale)
                }
                res
            } finally {
                runCatching { tmpDir.deleteRecursively() }
            }
        }
    }

    /**
     * 把单个页绘制到目标文档（供 [compose] 与分段写入器复用）。
     *
     * 位图在 `finishPage` 之前回收：`PdfDocument.finishPage` 会把画布命令
     * 序列化进内部结构，之后不再引用位图；反过来在 finish 之前 recycle 会让页面变黑。
     */
    private fun drawRefTo(
        doc: android.graphics.pdf.PdfDocument,
        ref: PageRef,
        destPageNo: Int,
        qualityScale: Float
    ) {
        ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (ref.pageIndex !in 0 until renderer.pageCount) return
                renderer.openPage(ref.pageIndex).use { page ->
                    // rotation 由目标页尺寸反映
                    val rotated = ref.rotation % 360 != 0 && ref.rotation % 180 != 0
                    val wPt = if (rotated) page.height else page.width
                    val hPt = if (rotated) page.width else page.height
                    val maxW = (wPt * (2f * qualityScale)).toInt().coerceIn(120, 1600)
                    // 高度也得压预算：一张 1600×20000 的位图是 128MB，比页数更致命
                    val rawH = (maxW.toFloat() * hPt / wPt.toFloat()).toInt().coerceAtLeast(1)
                    val maxH = rawH.coerceAtMost(MAX_PAGE_BITMAP_PX / maxW)
                    val bmp = Bitmap.createBitmap(maxW, maxH, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        SegmentedPdfWriter.drawBitmapPage(
                            doc = doc,
                            pageNo = destPageNo,
                            widthPt = wPt,
                            heightPt = hPt,
                            rotation = if (rotated) ref.rotation else 0
                        ) { canvas ->
                            canvas.drawBitmap(
                                bmp, null,
                                android.graphics.RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()),
                                Paint(Paint.FILTER_BITMAP_FLAG)
                            )
                        }
                    } finally {
                        // 异常路径也要回收，否则长文档中途失败会漏掉一张几十 MB 的位图
                        runCatching { bmp.recycle() }
                    }
                }
            }
        }
    }

    /** 单页位图像素上限（约 40MP ≈ 160MB ARGB，仍在低端机 heap 之内） */
    private const val MAX_PAGE_BITMAP_PX = 40_000_000

    /**
     * 水印参数（v1.3）。
     *
     * @param text 文字水印内容；为空时用 [imagePath]
     * @param fontSize 相对页宽的比例（0.02~0.20），而非绝对 pt，
     *        这样同一份设置在水印到 A4 与大幅面图纸时视觉比例一致
     * @param angle 旋转角度（度），常用 -30 ~ -45
     * @param alpha 透明度 0~1
     * @param tile true 平铺，false 单枚居中
     */
    data class WatermarkSpec(
        val text: String = "",
        val imagePath: String? = null,
        val fontSize: Float = 0.06f,
        val color: Long = 0xFF8A8A8E,
        val angle: Float = -35f,
        val alpha: Float = 0.22f,
        val tile: Boolean = true,
        val tileGap: Float = 0.28f
    )

    /**
     * 给整个文档打水印（v1.3）。
     *
     * 实现路线与 [com.lpdf.reader.data.pdf.PdfFlattener] 一致：
     * 逐页渲染底图 → 画水印 → 组装新 PDF。
     * 这意味着**输出文件不再保留可选文字**，是已知取舍；
     * 若要保留矢量文字需要 PDF 对象级写入，当前无可靠方案。
     */
    suspend fun watermark(
        src: File,
        outFile: File,
        spec: WatermarkSpec,
        pageRange: Set<Int>? = null,
        progress: (Float) -> Unit = {}
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val total = pageCountOf(src)
            if (total <= 0) {
                return@withLock PdfResult.Failure(PdfError.Corrupted("文档没有可读页面"))
            }

            // 图片水印只解码一次，全程复用（分段时也不重复解码）
            val wmBitmap: Bitmap? =
                if (spec.text.isBlank() && spec.imagePath != null) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(spec.imagePath) }.getOrNull()
                } else null

            val tmpDir = File(outFile.absoluteFile.parentFile ?: File("."), "seg_${System.nanoTime()}")
            try {
                SegmentedPdfWriter.write(
                    totalPages = total,
                    outFile = outFile,
                    dir = tmpDir,
                    onPage = { idx, t -> progress((idx + 1).toFloat() / t) }
                ) { doc, i ->
                    drawWatermarkPage(doc, src, i, spec, pageRange, wmBitmap)
                }
            } finally {
                runCatching { wmBitmap?.recycle() }
                runCatching { tmpDir.deleteRecursively() }
            }
        }
    }

    /** 渲染第 [index] 页底图并叠加水印（v1.6.2 抽出，供分段写入复用） */
    private fun drawWatermarkPage(
        doc: android.graphics.pdf.PdfDocument,
        src: File,
        index: Int,
        spec: WatermarkSpec,
        pageRange: Set<Int>?,
        wmBitmap: Bitmap?
    ) {
        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (index !in 0 until renderer.pageCount) return
                renderer.openPage(index).use { page ->
                    val wPt = page.width
                    val hPt = page.height
                    // 底图：按页宽 2 倍渲染，上限 1600 防 OOM
                    val bmpW = (wPt * 2).coerceIn(160, 1600)
                    val rawH = (bmpW.toFloat() * hPt / wPt).toInt().coerceAtLeast(1)
                    // 超长页同样要压：1600×30000 是 192MB，一张就足以把进程打死
                    val bmpH = rawH.coerceAtMost(MAX_PAGE_BITMAP_PX / bmpW)
                    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        SegmentedPdfWriter.drawBitmapPage(
                            doc = doc,
                            pageNo = index + 1,
                            widthPt = wPt,
                            heightPt = hPt,
                            overlay = { canvas, w, h ->
                                // 水印只在指定页码范围
                                if (pageRange == null || pageRange.contains(index)) {
                                    drawWatermark(canvas, w.toFloat(), h.toFloat(), spec, wmBitmap)
                                }
                            }
                        ) { canvas ->
                            canvas.drawBitmap(
                                bmp, null,
                                android.graphics.RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()),
                                Paint(Paint.FILTER_BITMAP_FLAG)
                            )
                        }
                    } finally {
                        runCatching { bmp.recycle() }
                    }
                }
            }
        }
    }

    /**
     * 水印字号：按页宽比例算，但给绝对上下限兜底。
     * 极小页宽（如名片）不至于看不清，极大页宽（如 A0 图纸）不至于糊满。
     */
    internal fun watermarkTextSize(pageWidth: Float, ratio: Float): Float =
        (pageWidth * ratio.coerceIn(0.01f, 0.4f)).coerceIn(8f, pageWidth * 0.4f)

    /** 平铺步进：行距与列距，抽出来便于单测覆盖 */
    internal fun watermarkTileSteps(
        pageWidth: Float,
        textWidth: Float,
        gapRatio: Float,
        hasText: Boolean
    ): Pair<Float, Float> {
        val step = (pageWidth * gapRatio.coerceAtLeast(0.05f)).coerceAtLeast(60f)
        val measured = if (hasText) textWidth else pageWidth * 0.3f
        val stepX = (measured + step * 0.8f).coerceAtLeast(80f)
        return stepX to step
    }

    private fun drawWatermark(
        canvas: Canvas,
        w: Float,
        h: Float,
        spec: WatermarkSpec,
        image: Bitmap?
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = spec.color.toInt()
            alpha = (spec.alpha.coerceIn(0f, 1f) * 255).toInt()
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            // 水印要压得住又看得见，粗体比常规体更容易辨认
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        if (spec.text.isNotBlank()) {
            paint.textSize = watermarkTextSize(w, spec.fontSize)
        }

        val useImage = spec.text.isBlank() && image != null && !image.isRecycled

        canvas.save()
        canvas.rotate(spec.angle, w / 2f, h / 2f)

        if (!spec.tile) {
            // 单枚居中
            if (useImage) {
                drawWmImage(canvas, image!!, w / 2f, h / 2f, w * 0.4f, paint)
            } else if (spec.text.isNotBlank()) {
                canvas.drawText(spec.text, w / 2f, h / 2f + paint.textSize / 3f, paint)
            }
        } else {
            // 平铺：按行距步进，交错排列避免单调
            val (stepX, step) = watermarkTileSteps(
                w, paint.measureText(spec.text), spec.tileGap, spec.text.isNotBlank()
            )
            var row = 0
            var y = -h * 0.2f
            while (y < h * 1.2f) {
                val offset = if (row % 2 == 0) 0f else stepX / 2f
                var x = -w * 0.2f + offset
                while (x < w * 1.2f) {
                    if (useImage) {
                        drawWmImage(canvas, image!!, x, y, w * 0.22f, paint)
                    } else if (spec.text.isNotBlank()) {
                        canvas.drawText(spec.text, x, y, paint)
                    }
                    x += stepX
                }
                y += step
                row++
            }
        }

        canvas.restore()
    }

    private fun drawWmImage(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, targetW: Float, paint: Paint) {
        val ratio = bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f)
        val w = targetW.coerceAtLeast(8f)
        val h = w * ratio
        canvas.drawBitmap(
            bmp, null,
            android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
            paint
        )
    }

    /** 合并多个 PDF（保持原页顺序） */
    suspend fun merge(files: List<File>, outFile: File, progress: (Float) -> Unit = {}): PdfResult<File> {        val refs = mutableListOf<PageRef>()
        files.forEach { f ->
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) refs += PageRef(f, i)
                }
            }
        }
        return compose(refs, outFile, 1f, progress)
    }

    /** 拆分：按页范围输出单个文件 */
    suspend fun extract(file: File, pages: List<Int>, outFile: File): PdfResult<File> =
        compose(pages.map { PageRef(file, it) }, outFile, 1f)

    /** 图片转 PDF：A4 或原尺寸，可加白边 */
    suspend fun imagesToPdf(
        images: List<File>,
        outFile: File,
        a4: Boolean = true,
        progress: (Float) -> Unit = {}
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val doc = android.graphics.pdf.PdfDocument()
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                images.forEachIndexed { idx, img ->
                    val bmp = android.graphics.BitmapFactory.decodeFile(img.absolutePath)
                        ?: return@forEachIndexed
                    val pageW = if (a4) 595 else bmp.width
                    val pageH = if (a4) 842 else bmp.height
                    val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                    val page = doc.startPage(info)
                    val scale = minOf(pageW / bmp.width.toFloat(), pageH / bmp.height.toFloat())
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val left = (pageW - dw) / 2f
                    val top = (pageH - dh) / 2f
                    page.canvas.drawBitmap(
                        bmp, null,
                        android.graphics.RectF(left, top, left + dw, top + dh), paint
                    )
                    doc.finishPage(page)
                    bmp.recycle()
                    progress((idx + 1) / images.size.toFloat())
                }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { doc.writeTo(it) }
                PdfResult.Success(outFile)
            } catch (e: Exception) {
                PdfResult.Failure(PdfError.WriteFailed("生成 PDF 失败：${e.message}"))
            } finally {
                doc.close()
            }
        }
    }

    /** 导出图片：单页 PNG/JPG */
    suspend fun exportImage(
        file: File,
        pageIndex: Int,
        outFile: File,
        dpi: Int = 200,
        jpeg: Boolean = false,
        progress: ((Float) -> Unit)? = null
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(pageIndex).use { p ->
                            val scale = dpi / 72f
                            val w = (p.width * scale).toInt().coerceAtLeast(1)
                            val h = (p.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                bmp.compress(
                                    if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                                    92, out
                                )
                            }
                            bmp.recycle()
                            progress?.invoke(1f)
                        }
                    }
                }
                PdfResult.Success(outFile)
            } catch (e: Exception) {
                PdfResult.Failure(PdfError.WriteFailed("导出图片失败：${e.message}"))
            }
        }
    }

    /** 压缩：逐页降采样重编码，质量档位 0~2 */
    suspend fun compress(file: File, outFile: File, level: Int, progress: (Float) -> Unit = {}): PdfResult<File> {
        val scale = when (level) {
            0 -> 1.0f    // 轻度
            1 -> 0.7f    // 标准
            else -> 0.45f // 强力
        }
        val count = pageCountOf(file)
        val refs = (0 until count).map { PageRef(file, it) }
        return compose(refs, outFile, scale, progress)
    }

    /**
     * 把 content:// 文档复制到应用私有缓存，供只接受 File 的写入通路使用。
     * 仅在「工具箱」需要处理 MediaStore 文档时调用；复制完成后原文件不受影响。
     */
    suspend fun stageCopy(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        cacheDir: File,
        displayName: String
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "staging").apply { mkdirs() }
            val out = File(dir, "${System.currentTimeMillis()}_$displayName")
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return@withContext PdfResult.Failure(PdfError.NotFound("无法读取源文件"))
            // 简单清理：超过 1 小时的暂存文件
            val cutoff = System.currentTimeMillis() - 3600_000L
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            PdfResult.Success(out)
        } catch (e: SecurityException) {
            PdfResult.Failure(PdfError.PermissionDenied())
        } catch (e: Exception) {
            PdfResult.Failure(PdfError.WriteFailed("暂存文件失败：${e.message}"))
        }
    }

    fun pageCountOf(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }.getOrDefault(0)

    /** 插入空白页生成（A4） */
    suspend fun blankA4(outFile: File): PdfResult<File> = withContext(Dispatchers.IO) {
        val doc = android.graphics.pdf.PdfDocument()
        try {
            val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(info)
            page.canvas.drawColor(Color.WHITE)
            doc.finishPage(page)
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { doc.writeTo(it) }
            PdfResult.Success(outFile)
        } catch (e: Exception) {
            PdfResult.Failure(PdfError.WriteFailed("创建空白页失败"))
        } finally {
            doc.close()
        }
    }
}
