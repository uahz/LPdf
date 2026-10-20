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
     * @param qualityScale 0.25~1.0，用于「压缩」场景（越低体积越小）
     */
    suspend fun compose(
        pages: List<PageRef>,
        outFile: File,
        qualityScale: Float = 1f,
        progress: (Float) -> Unit = {}
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val target = android.graphics.pdf.PdfDocument()
            try {
                pages.forEachIndexed { idx, ref ->
                    ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (ref.pageIndex !in 0 until renderer.pageCount) {
                                return@forEachIndexed
                            }
                            renderer.openPage(ref.pageIndex).use { page ->
                                // rotation 由目标页尺寸反映
                                val rotated = ref.rotation % 360 != 0 && ref.rotation % 180 != 0
                                val wPt = if (rotated) page.height else page.width
                                val hPt = if (rotated) page.width else page.height
                                val info = android.graphics.pdf.PdfDocument.PageInfo
                                    .Builder(wPt, hPt, idx + 1).create()
                                val dest = target.startPage(info)
                                val maxW = (wPt * (2f * qualityScale)).toInt().coerceIn(120, 1600)
                                val maxH = (maxW * hPt / wPt.toFloat()).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(maxW, maxH, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val canvas: Canvas = dest.canvas
                                if (rotated) {
                                    canvas.save()
                                    canvas.rotate(ref.rotation.toFloat(), wPt / 2f, hPt / 2f)
                                }
                                canvas.drawBitmap(
                                    bmp, null,
                                    android.graphics.RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()),
                                    Paint(Paint.FILTER_BITMAP_FLAG)
                                )
                                if (rotated) canvas.restore()
                                target.finishPage(dest)
                                bmp.recycle()
                            }
                        }
                    }
                    progress((idx + 1) / pages.size.toFloat())
                }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { target.writeTo(it) }
                PdfResult.Success(outFile)
            } catch (e: Exception) {
                PdfResult.Failure(PdfError.WriteFailed("写入 PDF 失败：${e.message}"))
            } finally {
                target.close()
            }
        }
    }

    /** 合并多个 PDF（保持原页顺序） */
    suspend fun merge(files: List<File>, outFile: File, progress: (Float) -> Unit = {}): PdfResult<File> {
        val refs = mutableListOf<PageRef>()
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
