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
 * 网络客户端工厂。全应用唯一出口（开发规范 §7）。
 *
 * 约束：
 * - 只允许 GET
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
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor(OfflineModeInterceptor(prefs))
                .addInterceptor(WifiOnlyInterceptor(context, prefs))
                .addInterceptor { chain ->
                    val original: Request = chain.request()
                    // 严禁 POST/PUT 上传
                    if (original.method !in setOf("GET", "HEAD")) {
                        throw IOException("LPdf 仅允许只读请求")
                    }
                    val safe = original.newBuilder()
                        .header("User-Agent", "LPdf/1.1 (Android PDF Reader)")
                        .header("Accept", "application/pdf,*/*;q=0.8")
                        .build()
                    chain.proceed(safe)
                }
                .build()
                .also { client = it }
        }
    }
}
