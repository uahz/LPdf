package com.lpdf.reader.data.pdf

import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF 元数据读写（v1.8.0）。
 *
 * ## 为什么读写都走 PDFBox，读不走 pdfium
 *
 * pdfium 的 `getDocumentMeta()` 只有四个字段（title/author/subject/keywords），
 * 而且工具箱拿到的源已经是暂存 `File`（经 `stageCopy`），再回 `ContentResolver`
 * 属于绕路。PDFBox 的 [PDDocumentInformation] 字段全、也能顺手读到创建/修改时间
 * 与生产器 —— 体检报告（v1.9.0）会直接复用这里。
 *
 * ## 写入边界（与 PROGRESS.md 附录 A.7 的约定一致）
 *
 * 只改 `/Info` 字典 + 重建 xref，**不碰页面内容流**；落盘只走 PDFBox 自己的
 * [PDDocument.save]，写完的文件**绝不允许再进 pdfium 的 `saveAsCopy` 通路**
 * （pdfium 会把内存里的旧快照整体写出，覆盖 PDFBox 的改动）。
 * 这里输出的永远是新文件，原文件保持只读 —— 与工具箱其余五个功能一致。
 *
 * ## 增量保存不做

 * `saveIncremental` 要求输出流能定位到原文件尾部，对「另存到 exports 目录」
 * 的模式没有意义（新文件不在原文件基础上），反而引入 xref 追加的兼容性风险。
 * 整份 `save()` 在这里更稳。
 */
object MetadataEditor {

    /** 一次读出的完整元数据。四个可编辑字段 + 两个只读信息 */
    data class Meta(
        val title: String,
        val author: String,
        val subject: String,
        val keywords: String,
        /** 生成该 PDF 的软件（只读展示，如 "Microsoft Word"） */
        val producer: String,
        /** 创建时间（只读展示，ISO 格式或空） */
        val creationDate: String
    ) {
        val hasAny: Boolean
            get() = title.isNotBlank() || author.isNotBlank() ||
                subject.isNotBlank() || keywords.isNotBlank()
    }

    /** 可编辑的四个字段。UI 直接持有这个结构，避免四个散落的 String 状态 */
    data class Editable(
        val title: String = "",
        val author: String = "",
        val subject: String = "",
        val keywords: String = ""
    )

    fun Meta.toEditable(): Editable = Editable(title, author, subject, keywords)

    /** 读取元数据。文件不存在 / 不是 PDF / 损坏都归入统一错误类型 */
    suspend fun read(file: File): PdfResult<Meta> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            return@withContext PdfResult.Failure(PdfError.NotFound())
        }
        try {
            PDDocument.load(file).use { doc ->
                val info = doc.documentInformation
                PdfResult.Success(
                    Meta(
                        title = info.title.orEmpty(),
                        author = info.author.orEmpty(),
                        subject = info.subject.orEmpty(),
                        keywords = info.keywords.orEmpty(),
                        producer = info.producer.orEmpty(),
                        creationDate = info.creationDate?.let { fmt(it.timeInMillis) }.orEmpty()
                    )
                )
            }
        } catch (e: SecurityException) {
            PdfResult.Failure(PdfError.PasswordRequired("该 PDF 已加密，元数据为只读"))
        } catch (t: Throwable) {
            // 加密文档在 PDFBox 里抛的是 IOException("Cannot decrypt encrypted PDF")
            // 或 BadSecurityHandlerException —— 都归到加密提示，用户能看懂
            val msg = t.message.orEmpty()
            if (msg.contains("encrypt", true) || msg.contains("password", true)) {
                PdfResult.Failure(PdfError.PasswordRequired("该 PDF 已加密，元数据为只读"))
            } else {
                PdfResult.Failure(PdfError.Corrupted("读取元数据失败：${t.javaClass.simpleName}"))
            }
        }
    }

    /**
     * 把 [meta] 写入新文件 [outFile]。源 [src] 保持不动。
     *
     * @param keepDates false（默认）时同时刷新 ModificationDate，与所有桌面
     *        编辑器行为一致；标题四字段全空时视为「清除全部元数据」也允许，
     *        因为这是用户的显式选择
     */
    suspend fun write(
        src: File,
        outFile: File,
        meta: Editable,
        keepDates: Boolean = false
    ): PdfResult<File> = withContext(Dispatchers.IO) {
        if (!src.exists() || !src.canRead()) {
            return@withContext PdfResult.Failure(PdfError.NotFound())
        }
        outFile.parentFile?.mkdirs()
        try {
            PDDocument.load(src).use { doc ->
                doc.documentInformation = PDDocumentInformation().apply {
                    title = meta.title.trim()
                    author = meta.author.trim()
                    subject = meta.subject.trim()
                    keywords = meta.keywords.trim()
                    if (!keepDates) {
                        // 与所有桌面编辑器行为一致：改元数据即刷新修改时间。
                        // PDFBox 的 setter 收 Calendar；格式化（D:YYYYMMDDHHmmSS）由库内完成
                        modificationDate = java.util.Calendar.getInstance()
                    }
                }
                // 落盘只走 PDFBox。此处产出的文件不允许再进 pdfium saveAsCopy 通路
                doc.save(outFile)
            }
            PdfResult.Success(outFile)
        } catch (e: SecurityException) {
            PdfResult.Failure(PdfError.PermissionDenied())
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("encrypt", true) || msg.contains("password", true)) {
                PdfResult.Failure(PdfError.PasswordRequired("该 PDF 已加密，无法写入元数据"))
            } else {
                // 失败时清掉半成品，避免 exports 里堆损坏文件
                runCatching { outFile.delete() }
                PdfResult.Failure(PdfError.WriteFailed("写入元数据失败：${t.javaClass.simpleName}"))
            }
        }
    }

    /** PDF 日期格式 D:YYYYMMDDHHmmSS。PDDocumentInformation 的 setter 只吃 Calendar，这里转格式化展示用 */
    internal fun fmt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}
