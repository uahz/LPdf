package com.lpdf.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PDFBox 元数据写读自洽性冒烟测试（纯 JVM，不需要 pdfium 的 .so）。
 *
 * 目的不是验证 PDFBox 本身，而是把「我们打算怎么用」钉死成可执行断言：
 * 写入的四个字段能原样读回，且文件仍是合法 PDF（头部魔数 + 页数）。
 * 后续 MetadataWriter 落地时，逻辑会被这里的用例结构直接复用。
 */
class MetadataWriterSmokeTest {

    private fun blankDoc(pages: Int = 2): PDDocument = PDDocument().apply {
        repeat(pages) { addPage(PDPage(PDRectangle.A4)) }
    }

    @Test
    fun `写入的元数据能原样读回`() {
        val out = ByteArrayOutputStream()
        blankDoc().use { doc ->
            doc.documentInformation = PDDocumentInformation().apply {
                title = "季度报告"
                author = "张三"
                subject = "财务"
                keywords = "lpdf, test"
            }
            doc.save(out)
        }

        PDDocument.load(out.toByteArray()).use { reopened ->
            val info = reopened.documentInformation
            assertEquals("季度报告", info.title)
            assertEquals("张三", info.author)
            assertEquals("财务", info.subject)
            assertEquals("lpdf, test", info.keywords)
            assertEquals(2, reopened.numberOfPages)
        }
    }

    @Test
    fun `文件头仍是合法 PDF 且未被破坏`() {
        val out = ByteArrayOutputStream()
        blankDoc(pages = 3).use { doc ->
            doc.documentInformation = PDDocumentInformation().apply { title = "x" }
            doc.save(out)
        }
        val bytes = out.toByteArray()
        assertTrue("应以 %PDF- 开头", String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-")
        PDDocument.load(bytes).use { assertEquals(3, it.numberOfPages) }
    }

    @Test
    fun `清空字段后读到空串而不是崩溃`() {
        val out = ByteArrayOutputStream()
        blankDoc(pages = 1).use { doc ->
            doc.documentInformation = PDDocumentInformation().apply { title = "" ; author = "" }
            doc.save(out)
        }
        PDDocument.load(out.toByteArray()).use { reopened ->
            assertEquals("", reopened.documentInformation.title ?: "")
        }
    }
}
