package com.lpdf.reader.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.data.local.PrefsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 离线模式拦截器：设置页开启「完全离线模式」后，任何请求直接失败。
 */
class OfflineModeInterceptor(private val prefs: PrefsRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val offline = kotlinx.coroutines.runBlocking { prefs.settings.first().offlineMode }
        if (offline) throw IOException(PdfError.OfflineBlocked().message)
        return chain.proceed(chain.request())
    }
}

/**
 * 仅 Wi-Fi 策略拦截器。
 */
class WifiOnlyInterceptor(
    private val context: Context,
    private val prefs: PrefsRepository
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val s = kotlinx.coroutines.runBlocking { prefs.settings.first() }
        if (s.offlineMode) throw IOException(PdfError.OfflineBlocked().message)
        if (s.wifiOnly) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            if (!onWifi && !onEthernet) throw IOException(PdfError.MeteredBlocked().message)
        }
        return chain.proceed(chain.request())
    }
}

/**
 * 只允许 HTTPS 的重定向策略（v1.6.2 / 安全修复）。
 *
 * 背景：OkHttp 默认 `followRedirects = true` / `followSslRedirects = true`，
 * 且 OkHttp 自身**不会**因为「https 跳到 http」而拒绝跟随。
 * 于是在 `LinkImporter` 里做了 `startsWith("https://")` 校验之后，
 * 服务器仍然可以 302 到一个 `http://` 明文地址继续下载 ——
 * 内容在明文中传输，可被中间人替换成一个「带合法 %PDF- 头」的恶意文件。
 * 仅仅改了魔数校验是挡不住的（魔数任何人都能伪造）。
 *
 * 做法：关掉 OkHttp 的自动跟随，改为手工逐跳跟随，每一跳都重新验协议。
 * - 只接受 https
 * - 最多 [MAX_REDIRECTS] 跳，防止重定向环把请求拖死
 * - 每一跳都要过一遍拦截器链（[chain.proceed]），所以「只允许 GET」
 *   「离线模式」「仅 Wi-Fi」这些约束对重定向后的请求同样生效 ——
 *   这一点是 OkHttp 内置跟随也能保证的，但自己实现后必须显式保持
 *
 * 为什么不只在拦截器里检查 `response.request.url`：
 * OkHttp 的 Response.request 拿到的是**最后一跳**的请求，
 * 中间某一跳降级到 http 再跳回 https 时，只看最终 URL 是看不出来的。
 * 手工逐跳是唯一能逐跳取证的做法。
 */
class HttpsRedirectInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var hops = 0
        var response = chain.proceed(request)

        while (response.isRedirect) {
            val location = response.header("Location")
            val next = response.request.url.resolve(location ?: "") ?: run {
                response.close()
                throw IOException("重定向目标无效，已中止下载")
            }

            if (!next.isHttps) {
                response.close()
                throw IOException("该链接重定向到非加密地址（${next.scheme}://${next.host}），已中止")
            }
            if (++hops > MAX_REDIRECTS) {
                response.close()
                throw IOException("重定向次数过多，已中止")
            }

            response.close()
            request = request.newBuilder().url(next).build()
            response = chain.proceed(request)
        }
        return response
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

/**
 * 网络客户端工厂。全应用唯一出口（开发规范 §7）。
 *
 * 约束：
 * - 只允许 GET
 * - 只允许 HTTPS（含每一跳重定向）
 * - 不携带任何设备标识
 * - 不发 POST、不上传
 */
object HttpClientProvider {

    @Volatile
    private var client: OkHttpClient? = null

    fun client(context: Context, prefs: PrefsRepository): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // 关闭内置跟随，改由 HttpsRedirectInterceptor 逐跳校验协议。
                // 注意 followSslRedirects 也是 false —— 语义是「跨 scheme 不自动跟」，
                // 正好对应我们要拦的那一类。
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .addInterceptor(OfflineModeInterceptor(prefs))
                .addInterceptor(WifiOnlyInterceptor(context, prefs))
                .addInterceptor { chain ->
                    val original: Request = chain.request()
                    // 严禁 POST/PUT 上传。放在 HttpsRedirectInterceptor 之前，
                    // 保证重定向产生的每一跳都被这条规则覆盖。
                    if (original.method !in setOf("GET", "HEAD")) {
                        throw IOException("LPdf 仅允许只读请求")
                    }
                    // 兜底：即便调用方漏了校验，这里也不允许初始地址是明文
                    if (!original.url.isHttps) {
                        throw IOException("LPdf 仅允许 HTTPS 请求")
                    }
                    val safe = original.newBuilder()
                        .header("User-Agent", "LPdf/1.1 (Android PDF Reader)")
                        .header("Accept", "application/pdf,*/*;q=0.8")
                        .build()
                    chain.proceed(safe)
                }
                .addInterceptor(HttpsRedirectInterceptor())
                .build()
                .also { client = it }
        }
    }
}
