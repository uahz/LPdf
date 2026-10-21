package com.lpdf.reader.data.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotationDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 标注扁平化导出（v1.2 核心）。
 *
 * 解决的问题：标注原本只存在本地 JSON（[com.lpdf.reader.data.local.AnnotationStore]），
 * PDF 文件本身不含任何标注信息，换设备 / 重装 / 分享给他人后标注全部丢失。
 *
 * 方案：逐页「渲染底图 → 叠加标注 → 写入新 PdfDocument」。
 * - 底图用 PDFium 渲染（与阅读页同一通路，保证所见即所得）
 * - 标注按归一化坐标换算到页面尺寸后绘制
 * - 输出为全新 PDF，**原文件永不修改**
 *
 * 取舍说明：
 * - 矢量方案（直接改写 PDF 内容流）体积最小、文字可选中，但需要完整 PDF 对象模型操作，
 *   风险高且当前无可靠库；栅格方案实现确定、跨阅读器显示一致，代价是文字不可选中、体积偏大。
 *   本条为 v1.2 刻意选择，若后续需要「保留可选文字」应向矢量方案演进。
 * - 页面 72 DPI 点数为坐标系基准，底图渲染宽度取 [renderWidth]，按比例缩放绘制。
 */
object PdfFlattener {

    /** 导出时每页渲染的目标宽度（像素）。1440 兼顾清晰度与内存。 */
    private const val RENDER_WIDTH = 1440

    /** 归一化矩形换算后的页面像素矩形 */
    data class FlatRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 归一化颜色（0xAARRGGBB Long）转 Android ARGB Int */
    fun argb(color: Long): Int = color.toInt()

    /**
     * 取标注涉及的所有矩形，换算到页面像素坐标。
     * 只有 Highlight 是多矩形结构；其余类型返回空。
     */
    fun flatRects(a: Annotation, pageW: Float, pageH: Float): List<FlatRect> =
        when (a) {
            is Annotation.Highlight -> a.rects.map {
                FlatRect(it.left * pageW, it.top * pageH, it.right * pageW, it.bottom * pageH)
            }

            else -> emptyList()
        }

    /** 墨迹点序列换算到页面像素坐标 */
    fun flatPoints(a: Annotation.Ink, pageW: Float, pageH: Float): List<Pair<Float, Float>> =
        a.points.map { (x, y) -> x * pageW to y * pageH }

    /** 墨迹/形状线宽换算为像素，下限 2f 防止导出后不可见 */
    fun flatStrokeWidth(a: Annotation.Ink, pageW: Float): Float =
        (a.width * pageW).coerceAtLeast(2f)

    /** 形状描边宽度换算为像素 */
    fun flatShapeStroke(a: Annotation.Shape, pageW: Float): Float =
        (a.stroke * pageW).coerceAtLeast(2f)

    /**
     * 把标注烧录进 PDF。
     *
     * @param resolver 用于读取源 PDF 的 ContentResolver（经 PdfiumEngine）
     * @param sourceUri 源 PDF
     * @param doc 该文档的标注集合（页码从 0 开始）
     * @param outFile 输出文件，调用方负责选择位置（SAF）
     * @param pageCount 总页数
     * @param password 加密文档口令
     * @param flatBackground true 时输出纯图（兼容性最好、体积大）；false 时……
     *        目前统一走矢量文字 + 栅格底图，此参数保留给后续演进。
     * @param progress 进度回调 0f~1f
     */
    suspend fun export(
        resolver: android.content.ContentResolver,
        sourceUri: android.net.Uri,
        doc: AnnotationDoc,
        outFile: File,
        pageCount: Int,
        password: String? = null,
        flatBackground: Boolean = true,
        progress: (Float) -> Unit = {}
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        if (pageCount <= 0) {
            return@withContext PdfResult.Failure(PdfError.Corrupted("文档没有可读页面"))
        }

        // v1.5.0：走常驻会话分块渲染。
        // 旧版每页调一次 renderPage（内部重开文档），而且超长页会直接失败 ——
        // 只要文档里有一张 CAD 长图，整本导出就中断。
        val sessionRes = PdfiumEngine.openSession(resolver, sourceUri, password)
        val session = when (sessionRes) {
            is PdfResult.Failure -> return@withContext sessionRes
            is PdfResult.Success -> sessionRes.value
        }

        val pdf = PdfDocument()
        try {
            for (pageIndex in 0 until pageCount) {
                coroutineContext.ensureActive()

                val planRes = session.plan(pageIndex, RENDER_WIDTH)
                val plan = when (planRes) {
                    is PdfResult.Failure -> return@withContext planRes
                    is PdfResult.Success -> planRes.value
                }

                // 以分块后的整页尺寸建页；点位与像素 1:1，宽高比即真实比例
                val pageW = plan.rects.maxOf { it.left + it.width }
                val pageH = plan.rects.maxOf { it.top + it.height }
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create()
                val page = pdf.startPage(info)
                val canvas: Canvas = page.canvas

                // 1) 底图逐块贴到整页对应位置 —— 分块只是渲染手段，
                //    输出仍是一张完整页面，接缝处由 PdfDocument 的矢量画布天然对齐
                val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
                var lastError: PdfError? = null
                val renderRes = session.renderTiles(pageIndex, plan) { tile ->
                    coroutineContext.ensureActive()
                    canvas.drawBitmap(
                        tile.bitmap,
                        null,
                        RectF(
                            tile.left.toFloat(),
                            tile.top.toFloat(),
                            (tile.left + tile.width).toFloat(),
                            (tile.top + tile.height).toFloat()
                        ),
                        tilePaint
                    )
                    tile.bitmap.recycle()
                }
                if (renderRes is PdfResult.Failure) lastError = renderRes.error
                if (lastError != null) {
                    runCatching { pdf.finishPage(page) }
                    return@withContext PdfResult.Failure(lastError)
                }

                // 2) 叠加该页标注
                val annots = doc.pages[pageIndex].orEmpty()
                annots.forEach { a ->
                    coroutineContext.ensureActive()
                    drawAnnotation(canvas, a, pageW.toFloat(), pageH.toFloat())
                }

                pdf.finishPage(page)
                progress((pageIndex + 1).toFloat() / pageCount)
            }

            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { pdf.writeTo(it) }
            PdfResult.Success(outFile)
        } catch (e: SecurityException) {
            PdfResult.Failure(PdfError.PermissionDenied())
        } catch (e: java.io.IOException) {
            PdfResult.Failure(PdfError.WriteFailed(e.message ?: "写入失败"))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            PdfResult.Failure(PdfError.Unknown("导出标注失败", t))
        } finally {
            runCatching { pdf.close() }
            runCatching { session.close() }
        }
    }

    // -----------------------------------------------------------------------
    // 绘制：与阅读页 AnnotationLayer 保持一致
    // -----------------------------------------------------------------------

    private fun drawAnnotation(canvas: Canvas, a: Annotation, w: Float, h: Float) {
        when (a) {
            is Annotation.Highlight -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = argb(a.color)
                    alpha = (0.42f * 255).toInt()   // 与屏幕 0.42f 一致
                }
                flatRects(a, w, h).forEach { r ->
                    canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
                }
            }

            is Annotation.Ink -> {
                if (a.points.size > 1) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = argb(a.color)
                        style = Paint.Style.STROKE
                        strokeWidth = flatStrokeWidth(a, w)
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val pts = flatPoints(a, w, h)
                    val path = Path().apply {
                        moveTo(pts.first().first, pts.first().second)
                        pts.drop(1).forEach { p -> lineTo(p.first, p.second) }
                    }
                    canvas.drawPath(path, paint)
                }
            }

            is Annotation.Shape -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = argb(a.color)
                    style = if (a.filled) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = flatShapeStroke(a, w)
                }
                val rect = RectF(
                    a.bounds.left * w,
                    a.bounds.top * h,
                    a.bounds.right * w,
                    a.bounds.bottom * h
                )
                when (a.kind) {
                    "OVAL" -> canvas.drawOval(rect, paint)
                    else -> canvas.drawRect(rect, paint)
                }
            }

            is Annotation.Note -> {
                // 批注锚点：导出时画成小圆点 + 文字，避免导出后只剩一个点不知道写了什么
                val cx = a.x * w
                val cy = a.y * h
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#FF9500")
                }
                canvas.drawCircle(cx, cy, 6f, dot)
                canvas.drawCircle(cx, cy, 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

                if (a.text.isNotBlank()) {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.parseColor("#FF9500")
                        textSize = 10f
                        typeface = Typeface.DEFAULT
                    }
                    canvas.drawText(a.text.take(80), cx + 10f, cy + 4f, textPaint)
                }
            }

            is Annotation.TextBox -> {
                val box = RectF(
                    a.bounds.left * w,
                    a.bounds.top * h,
                    a.bounds.right * w,
                    a.bounds.bottom * h
                )
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRect(box, bg)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = a.color.toInt()
                    textSize = (a.size * w).coerceAtLeast(6f)
                    typeface = Typeface.DEFAULT
                }
                // 按盒宽做简单换行
                val maxWidth = box.width() - 4f
                var lineY = box.top + textPaint.textSize
                a.text.split('\n').forEach { rawLine ->
                    var line = rawLine
                    while (line.isNotEmpty()) {
                        val count = textPaint.breakText(line, true, maxWidth, null).coerceAtLeast(1)
                        if (lineY < box.bottom) {
                            canvas.drawText(line.substring(0, count), box.left + 2f, lineY, textPaint)
                        }
                        lineY += textPaint.textSize * 1.25f
                        line = line.substring(count)
                    }
                }
            }

            is Annotation.Signature -> {
                val box = RectF(
                    a.bounds.left * w,
                    a.bounds.top * h,
                    a.bounds.right * w,
                    a.bounds.bottom * h
                )
                val bmp = runCatching { BitmapFactory.decodeFile(a.bitmapPath) }.getOrNull()
                if (bmp != null) {
                    canvas.drawBitmap(
                        bmp, null, box,
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    )
                    bmp.recycle()
                }
            }

            is Annotation.FormField -> {
                // 表单字段用文本形式落到页面上（真正的 AcroForm 交互式填写属 v2.0）
                val box = RectF(
                    a.bounds.left * w,
                    a.bounds.top * h,
                    a.bounds.right * w,
                    a.bounds.bottom * h
                )
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#0A84FF")
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                canvas.drawRect(box, border)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#1C1C1E")
                    textSize = (box.height() * 0.6f).coerceIn(8f, 28f)
                }
                canvas.drawText(a.value, box.left + 3f, box.centerY() + textPaint.textSize / 3f, textPaint)
            }
        }
    }
}
