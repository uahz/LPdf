package com.lpdf.reader.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import com.lpdf.reader.core.network.HttpClientProvider
import com.lpdf.reader.data.local.PrefsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 在线 PDF 链接导入。
 *
 * 流程：校验协议 → 检查开关 → 流式下载到私有缓存 → 校验魔数 → 返回本地文件。
 * 全程不上传任何本地数据。
 */
class LinkImporter(
    private val context: Context,
    private val prefs: PrefsRepository
) {

    data class Imported(val file: File, val displayName: String)

    suspend fun import(url: String): PdfResult<Imported> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", true)) {
            return@withContext PdfResult.Failure(PdfError.Network("仅支持 HTTPS 链接"))
        }
        val settings = prefs.settings.first()
        if (settings.offlineMode) return@withContext PdfResult.Failure(PdfError.OfflineBlocked())
        if (!settings.allowLinkPreview) {
            return@withContext PdfResult.Failure(PdfError.Network("已在设置中关闭在线链接"))
        }

        val name = Uri.parse(trimmed).lastPathSegment
            ?.substringAfterLast('/')
            ?.ifBlank { "link_${System.currentTimeMillis()}.pdf" }
            ?: "link_${System.currentTimeMillis()}.pdf"
        val safeName = if (name.lowercase().endsWith(".pdf")) name else "$name.pdf"

        // 「仅 Wi-Fi 下载」（v1.6.1 接线）：开关在设置页里存在了很久，
        // 但从来没人读 —— 用户以为用流量时不会下载，实际上照样跑完全程。
        // 判定放在真正开始下载之前：先查一次当前网络类型，
        // 不是 Wi-Fi 就直接拒绝并说明原因，而不是下到一半才停。
        if (settings.wifiOnly && !isOnWifi()) {
            return@withContext PdfResult.Failure(
                PdfError.Network("已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi 网络")
            )
        }

        val dir = File(context.filesDir, "imported").apply { mkdirs() }
        val outFile = File(dir, "${System.currentTimeMillis()}_$safeName")

        try {
            val client = HttpClientProvider.client(context, prefs)
            val request = Request.Builder().url(trimmed).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext PdfResult.Failure(PdfError.Network("下载失败（${resp.code}）"))
                }
                val body = resp.body ?: return@withContext PdfResult.Failure(PdfError.Network("响应为空"))
                if (body.contentLength() > MAX_BYTES) {
                    return@withContext PdfResult.Failure(PdfError.Network("文件超过 100MB 限制"))
                }
                var written = 0L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            written += r
                            if (written > MAX_BYTES) {
                                outFile.delete()
                                return@withContext PdfResult.Failure(PdfError.Network("文件超过 100MB 限制"))
                            }
                            output.write(buf, 0, r)
                        }
                    }
                }
            }
            // 校验魔数
            val head = outFile.inputStream().use { it.readNBytes(1024) }
            if (String(head, Charsets.ISO_8859_1).indexOf("%PDF-") < 0) {
                outFile.delete()
                return@withContext PdfResult.Failure(PdfError.Corrupted("该链接不是有效的 PDF"))
            }
            PdfResult.Success(Imported(outFile, safeName))
        } catch (e: IOException) {
            outFile.delete()
            PdfResult.Failure(PdfError.Network(e.message ?: "网络不可用，已切回本地模式"))
        }
    }

    /**
     * 当前是否处于 Wi-Fi / 以太网。
     *
     * 用 `ConnectivityManager` 的网络能力判断，而不是 `NetworkInfo.getType()` ——
     * 后者在 Android 10+ 已废弃，且在「Wi-Fi 连着但没网、实际走流量」的场景下
     * 会给出错误答案（给出 Wi-Fi，用户被扣了流量）。
     * 这里查的是**承载当前默认网络的传输类型**，与实际出网的链路一致。
     */
    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 清理超过 7 天的导入缓存 */
    fun cleanOldImports(days: Long = 7) {
        val dir = File(context.filesDir, "imported")
        val cutoff = System.currentTimeMillis() - days * 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** 已导入的本地文件列表（启动扫描时并入列表） */
    suspend fun listImported(): List<com.lpdf.reader.core.model.PdfItem> =
        withContext(Dispatchers.IO) {
            File(context.filesDir, "imported").listFiles()
                ?.filter { it.isFile && it.name.lowercase().endsWith(".pdf") }
                ?.map { f ->
                    com.lpdf.reader.core.model.PdfItem(
                        id = f.absolutePath,
                        uri = Uri.fromFile(f).toString(),
                        displayName = f.name.substringAfter('_', f.name),
                        sizeBytes = f.length(),
                        lastModified = f.lastModified(),
                        source = com.lpdf.reader.core.model.PdfSource.LINK_IMPORT
                    )
                }
                ?: emptyList()
        }

    /** 删除导入副本（仅应用私有目录内的文件） */
    suspend fun deleteImported(uri: String) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "imported").canonicalPath
        val f = runCatching { File(Uri.parse(uri).path ?: return@withContext) }.getOrNull()
            ?: return@withContext
        runCatching { if (f.canonicalPath.startsWith(root)) f.delete() }
        Unit
    }

    private companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}

/**
 * 更新检查：只发送版本号，不发送任何设备标识；不自动下载。
 */
class UpdateChecker(private val context: Context, private val prefs: PrefsRepository) {

    sealed class Result {
        data object UpToDate : Result()
        data class Available(val versionName: String, val url: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 说明：V1.1 不内置任何服务端地址，仅预留接口。
     * 正式发布时由运营侧配置一个纯静态 JSON（HTTPS），仅返回版本号与下载页链接。
     */
    suspend fun check(currentVersion: String, manifestUrl: String?): Result =
        withContext(Dispatchers.IO) {
            if (manifestUrl.isNullOrBlank()) return@withContext Result.Failed("未配置更新服务")
            val s = prefs.settings.first()
            if (s.offlineMode) return@withContext Result.Failed("离线模式")
            runCatching {
                val client = HttpClientProvider.client(context, prefs)
                val req = Request.Builder().url(manifestUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    // 极简解析：{"version":"1.2.0","url":"https://..."}
                    val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    if (version == null) Result.Failed("解析失败")
                    else if (version != currentVersion) Result.Available(version, url.orEmpty())
                    else Result.UpToDate
                }
            }.getOrElse { Result.Failed(it.message ?: "网络不可用") }
        }
}
