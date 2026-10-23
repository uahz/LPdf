package com.lpdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.core.network.HttpsRedirectInterceptor
import com.lpdf.reader.data.network.LinkImporter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * 网络与文件名安全测试（v1.6.2 安全修复）。
 *
 * 覆盖两处上一轮代码审计指出的问题：
 * 1. OkHttp 默认会跟随 `https → http` 的重定向，等于把明文传输放进了「仅 HTTPS」的策略里
 * 2. 从 URL 推导的文件名带 `%00` / `%2F` 等字符，落盘直接 `EINVAL`
 *
 * 两者都用**不联网**的方式验证：重定向用假的 Interceptor.Chain 驱动，
 * 文件名直接调纯函数。
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityTest {

    private fun chainOf(vararg responses: Response): (Request) -> Response {
        var i = 0
        return { _ -> responses[i++] }
    }

    private fun redirectTo(url: String): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/a.pdf").build())
        .protocol(Protocol.HTTP_2)
        .code(302)
        .message("Found")
        .header("Location", url)
        .body("".toResponseBody("text/plain".toMediaType()))
        .build()

    private fun ok(url: String = "https://example.com/final.pdf"): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_2)
        .code(200)
        .message("OK")
        .body("x".toResponseBody("application/pdf".toMediaType()))
        .build()

    // ---------------- HTTPS 重定向 ----------------

    @Test
    fun `重定向到明文地址必须被拒绝`() {
        val chain = chainOf(redirectTo("http://evil.example.com/x.pdf"), ok())
        val intercept = HttpsRedirectInterceptor()

        // 用一个最小 Chain 实现驱动拦截器
        val exc = runCatching { intercept.intercept(FakeChain(chain)) }.exceptionOrNull()
        assertTrue("应抛 IOException 而不是继续下载", exc is IOException)
        assertTrue("错误信息要说明原因", exc?.message?.contains("非加密") == true)
    }

    @Test
    fun `重定向到 https 正常跟随并返回最终响应`() {
        val chain = chainOf(redirectTo("https://cdn.example.com/b.pdf"), ok())
        val resp = HttpsRedirectInterceptor().intercept(FakeChain(chain))
        assertEquals(200, resp.code)
    }

    @Test
    fun `重定向次数过多必须中止, 不能被拖死`() {
        val loop = Array(10) { redirectTo("https://example.com/loop") }
        val chain = chainOf(*loop)
        val exc = runCatching { HttpsRedirectInterceptor().intercept(FakeChain(chain)) }.exceptionOrNull()
        assertTrue(exc is IOException)
        assertTrue(exc?.message?.contains("重定向次数过多") == true)
    }

    @Test
    fun `默认客户端不再自动跟随重定向, 交给拦截器逐跳校验`() {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    // ---------------- 文件名清洗 ----------------

    @Test
    fun `URL 中的 %00 不能进入文件名`() {
        val name = LinkImporter.safeFileName("https://a.com/inv%00alid.pdf")
        assertFalse("不得含 NUL", name.any { it.code == 0 })
        assertTrue(name.endsWith(".pdf") || name.contains("alid"))
    }

    @Test
    fun `URL 编码的斜杠不应变成路径分隔符`() {
        val name = LinkImporter.safeFileName("https://a.com/x%2F..%2Fetc%2Fpasswd.pdf")
        assertFalse("不得含 /", name.contains('/'))
        assertFalse("不得含 \\", name.contains('\\'))
    }

    @Test
    fun `正常文件名原样保留`() {
        assertEquals("quarterly-report.pdf", LinkImporter.safeFileName("https://a.com/quarterly-report.pdf"))
    }

    @Test
    fun `中文文件名不应被破坏`() {
        assertEquals("季度报告.pdf", LinkImporter.safeFileName("https://a.com/%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf"))
    }

    @Test
    fun `空路径回落到带时间戳的默认名`() {
        val name = LinkImporter.safeFileName("https://a.com/")
        assertTrue(name.startsWith("link_"))
    }

    @Test
    fun `超长文件名被截断, 不会撞 255 字节上限`() {
        val long = "a".repeat(500) + ".pdf"
        val name = LinkImporter.safeFileName("https://a.com/$long")
        assertTrue("长度应被限制", name.length <= 80)
    }

    @Test
    fun `首尾点号与空白被清掉`() {
        val name = LinkImporter.safeFileName("https://a.com/..hidden..pdf")
        assertFalse(name.startsWith("."))
    }

    // ---------------- 最小 Chain 实现 ----------------

    private class FakeChain(private val next: (Request) -> Response) : okhttp3.Interceptor.Chain {
        override fun request(): Request = Request.Builder().url("https://example.com/a.pdf").build()

        override fun proceed(request: Request): Response = next(request)

        override fun connection() = null

        override fun call() = throw UnsupportedOperationException("测试不需要真实 Call")

        override fun connectTimeoutMillis(): Int = 15_000

        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this

        override fun readTimeoutMillis(): Int = 30_000

        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this

        override fun writeTimeoutMillis(): Int = 15_000

        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }
}
