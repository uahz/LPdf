package com.lpdf.reader

import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.data.pdf.MetadataEditor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MetadataEditor 端到端测试（v1.8.0）。
 *
 * PDFBox 是纯 JVM 库，可以直接在单测里跑真实的 load/save 往返，
 * 不需要 pdfium 的 .so。这里钉死三条行为契约：
 * 1. write 的产物能被 read 读回相同字段
 * 2. 写入失败 / 读不到时错误归类正确，且不留半成品
 * 3. 源文件在写入前后字节不变（「原文件只读」承诺）
 */
class MetadataEditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makePdf(name: String, title: String = "旧标题"): File {
        val f = tmp.newFile(name)
        PDDocument().apply {
            addPage(PDPage(PDRectangle.A4))
            documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation().apply {
                this.title = title
            }
            save(f)
            close()
        }
        return f
    }

    @Test
    fun `write 产物能被 read 读回相同字段`() = runBlocking {
        val src = makePdf("src.pdf")
        val out = File(tmp.root, "out.pdf")
        val res = MetadataEditor.write(
            src, out,
            MetadataEditor.Editable(title = "新标题", author = "李四", subject = "合同", keywords = "lpdf")
        )
        assertTrue(res is PdfResult.Success)
        val readBack = MetadataEditor.read(out)
        assertTrue(readBack is PdfResult.Success)
        val meta = (readBack as PdfResult.Success).value
        assertEquals("新标题", meta.title)
        assertEquals("李四", meta.author)
        assertEquals("合同", meta.subject)
        assertEquals("lpdf", meta.keywords)
        assertTrue(meta.hasAny)
    }

    @Test
    fun `源文件字节不被写入改动`() = runBlocking {
        val src = makePdf("keep.pdf")
        val before = src.readBytes()
        MetadataEditor.write(
            src, File(tmp.root, "out2.pdf"),
            MetadataEditor.Editable(title = "改")
        )
        assertTrue(before.contentEquals(src.readBytes()))
    }

    @Test
    fun `ModificationDate 被刷新且原创建时间保留`() = runBlocking {
        val src = makePdf("dates.pdf")
        val original = PDDocument.load(src).use {
            it.documentInformation.creationDate?.timeInMillis
        }
        val out = File(tmp.root, "dates_out.pdf")
        val res = MetadataEditor.write(src, out, MetadataEditor.Editable(title = "x"))
        assertTrue(res is PdfResult.Success)
        PDDocument.load(out).use { doc ->
            val info = doc.documentInformation
            assertTrue(info.modificationDate != null)
            if (original != null) {
                assertEquals(original, info.creationDate?.timeInMillis)
            }
        }
    }

    @Test
    fun `非 PDF 文件读取归入损坏错误`() = runBlocking {
        val junk = tmp.newFile("junk.pdf").apply { writeText("not a pdf") }
        val res = MetadataEditor.read(junk)
        assertTrue(res is PdfResult.Failure)
        assertEquals("PdfError.Corrupted", (res as PdfResult.Failure).error.javaClass.simpleName.let { "PdfError.$it" })
    }

    @Test
    fun `写入失败不留下半成品`() = runBlocking {
        val junk = tmp.newFile("junk2.pdf").apply { writeText("not a pdf") }
        val out = File(tmp.root, "should_not_exist.pdf")
        val res = MetadataEditor.write(junk, out, MetadataEditor.Editable(title = "x"))
        assertTrue(res is PdfResult.Failure)
        assertTrue("半成品应被清理", !out.exists())
    }
}
