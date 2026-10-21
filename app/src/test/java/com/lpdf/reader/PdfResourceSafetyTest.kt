package com.lpdf.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.data.pdf.PdfLimits
import com.lpdf.reader.data.pdf.PdfValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 失效授权与渲染闸门的回归测试（v1.3.4）。
 *
 * ## 为什么不在这里测 PDFium
 *
 * Robolectric 跑不动 PDFium 的 native 库：`System.loadLibrary` 在 JVM 上失败后
 * 调用链会阻塞，整轮测试挂死 30 分钟才被超时杀掉。因此这一层只覆盖
 * **不依赖 native** 的部分：
 *
 * - 授权失效 / 文件丢失的**错误归类**（决定列表页给不给用户自救入口）
 * - 渲染尺寸闸门（v1.3.2 那 527MB 位图崩溃的防线）
 *
 * native 资源释放依赖 try/finally 的结构正确性，由代码评审 + 真机长跑覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfResourceSafetyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `文件不存在时校验器返回 NotFound 而不是 Unknown`() = kotlinx.coroutines.runBlocking {
        val missing = Uri.fromFile(File(app.cacheDir, "definitely-not-here.pdf"))
        val res = PdfValidator(app.contentResolver).validate(missing)
        assertTrue("应为 Failure，实际 $res", res is PdfResult.Failure)
        val err = (res as PdfResult.Failure).error
        assertTrue(
            "缺失文件必须归到 NotFound，列表页才能给出「重新选择」入口，实际 ${err::class.java.simpleName}",
            err is PdfError.NotFound
        )
    }

    @Test
    fun `被移除的文件同样归到 NotFound`() = kotlinx.coroutines.runBlocking {
        val f = File(app.cacheDir, "vanishing-${System.nanoTime()}.pdf")
        f.writeText("%PDF-1.7\n")
        assertTrue(f.delete())
        val res = PdfValidator(app.contentResolver).validate(Uri.fromFile(f))
        assertTrue((res as PdfResult.Failure).error is PdfError.NotFound)
    }

    @Test
    fun `空文件归到 Corrupted 而不是 NotFound`() = kotlinx.coroutines.runBlocking {
        // 文件在、但内容为空：应该是「文件坏了」，不是「找不到」。
        // 两者给用户的提示和动作完全不同，不能混。
        val f = File(app.cacheDir, "empty-${System.nanoTime()}.pdf")
        f.writeBytes(ByteArray(0))
        val res = PdfValidator(app.contentResolver).validate(Uri.fromFile(f))
        assertTrue((res as PdfResult.Failure).error is PdfError.Corrupted)
    }

    @Test
    fun `非 PDF 内容归到 Corrupted`() = kotlinx.coroutines.runBlocking {
        val f = File(app.cacheDir, "notpdf-${System.nanoTime()}.pdf")
        f.writeText("just some plain text pretending to be a pdf")
        val res = PdfValidator(app.contentResolver).validate(Uri.fromFile(f))
        assertTrue((res as PdfResult.Failure).error is PdfError.Corrupted)
    }

    @Test
    fun `渲染尺寸闸门拦住超大与超长宽比`() {
        // v1.3.2 真机崩溃：Canvas trying to draw too large(527487968bytes)
        assertTrue(PdfLimits.fitsHardwareTexture(2048, 2048))
        assertTrue(!PdfLimits.fitsHardwareTexture(9000, 100))
        assertTrue(!PdfLimits.fitsHardwareTexture(2048, 9000))
        assertTrue(!PdfLimits.fitsHardwareTexture(8000, 8000))
    }

    @Test
    fun `渲染尺寸闸门允许常见页面比例`() {
        // A4 @ 2x ≈ 1190x1684，正常手机阅读页，不能被误杀
        assertTrue(PdfLimits.fitsHardwareTexture(1190, 1684))
        assertTrue(PdfLimits.fitsHardwareTexture(1080, 1528))
        assertTrue(PdfLimits.fitsHardwareTexture(300, 300))
    }

    @Test
    fun `像素上限与位图字节数自洽`() {
        // 2048x2048 ARGB = 16.7MB，MAX_PIXELS 必须留够余量
        assertTrue(PdfLimits.MAX_PIXELS >= 2048L * 2048L)
        assertEquals(4L, PdfLimits.MAX_BITMAP_BYTES / PdfLimits.MAX_PIXELS)
    }
}
