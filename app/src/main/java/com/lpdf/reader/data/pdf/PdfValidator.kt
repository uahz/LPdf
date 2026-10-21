package com.lpdf.reader.data.pdf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.lpdf.reader.core.model.PdfError
import com.lpdf.reader.core.model.PdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * PDF 轻量校验器：“先验身、再开卷”。
 *
 * - 只读取文件头与尾部若干字节，绝不整文件读入内存
 * - 任何超过 3s 的操作都被视为失败（由上层 withTimeout 控制）
 */
class PdfValidator(private val resolver: ContentResolver) {

    data class Probe(
        val magicOk: Boolean,
        val hasEofMarker: Boolean,
        val encryptedHint: Boolean
    ) {
        val looksValid: Boolean get() = magicOk && hasEofMarker
    }

    /**
     * 快速探测：4KB 头部 + 8KB 尾部。
     * 注意：PDF 允许注释/前置垃圾字节，因此魔数允许出现在前 1024 字节内。
     */
    suspend fun probe(uri: Uri): PdfResult<Probe> = withContext(Dispatchers.IO) {
        try {
            val head = readHead(uri, HEAD_LEN)
            if (head.isEmpty()) {
                return@withContext PdfResult.Failure(PdfError.Corrupted("文件为空"))
            }
            val magicOk = indexOfMagic(head) >= 0
            val tail = readTail(uri, TAIL_LEN)
            val tailStr = String(tail, Charsets.ISO_8859_1)
            val hasEof = tailStr.contains("%%EOF")
            val encrypted = tailStr.contains("/Encrypt")
            PdfResult.Success(Probe(magicOk, hasEof, encrypted))
        } catch (e: SecurityException) {
            PdfResult.Failure(PdfError.PermissionDenied())
        } catch (e: java.io.FileNotFoundException) {
            // SAF 授权被撤销 / 文件被移走后 openInputStream 会抛 FileNotFoundException，
            // 归到 Unknown 的话列表页只能给出「无法打开该文件」，
            // 用户没有任何自救入口。
            PdfResult.Failure(PdfError.NotFound())
        } catch (t: Throwable) {
            PdfResult.Failure(PdfError.Unknown(cause = t))
        }
    }

    /** 完整校验：魔数 + EOF + 可打开（页数 > 0） */
    suspend fun validate(uri: Uri): PdfResult<Int> {
        val probe = probe(uri)
        val p = probe.getOrNull() ?: return probe as PdfResult.Failure
        if (!p.magicOk) return PdfResult.Failure(PdfError.Corrupted("不是有效的 PDF 文件"))
        if (!p.hasEofMarker) return PdfResult.Failure(PdfError.Corrupted("PDF 结构不完整"))
        // 打开一次以获得真实页数；加密文档会在此返回 PasswordRequired
        return PdfiumEngine.pageCount(resolver, uri)
    }

    fun queryDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "未命名.pdf"
    }.getOrDefault(uri.lastPathSegment ?: "未命名.pdf")

    fun querySize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    // ---- 私有 IO ----

    private fun readHead(uri: Uri, len: Int): ByteArray {
        resolver.openInputStream(uri)?.use { return it.readAtMost(len) }
        val f = uri.path?.let { File(it) } ?: throw java.io.FileNotFoundException("无法定位文件: $uri")
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $f")
        return FileInputStream(f).use { it.readAtMost(len) }
    }

    /**
     * 读取文件尾部。content:// 流的 available() 不可靠，
     * 因此优先用 AssetFileDescriptor 的长度做 seek；失败则流式丢弃前段。
     */
    private fun readTail(uri: Uri, len: Int): ByteArray {
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val length = afd.length
                if (length > len) {
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        fis.channel.position(length - len)
                        val buf = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val r = fis.read(buf, off, len - off)
                            if (r <= 0) break
                            off += r
                        }
                        return if (off == len) buf else buf.copyOf(off)
                    }
                }
            }
        }
        val input = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("无法打开: $uri")
        return input.use {
            var remaining = it.available().toLong() - len
            if (remaining > 0) {
                while (remaining > 0) {
                    val skipped = it.skip(remaining)
                    if (skipped <= 0) break
                    remaining -= skipped
                }
                it.readAtMost(len)
            } else {
                it.readAtMost(len)
            }
        }
    }

    private fun InputStream.readAtMost(len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = read(out, off, len - off)
            if (r <= 0) break
            off += r
        }
        return if (off == len) out else out.copyOf(off)
    }

    private fun indexOfMagic(bytes: ByteArray): Int {
        val magic = MAGIC
        if (bytes.size < magic.size) return -1
        val limit = minOf(bytes.size - magic.size, 1024 - magic.size).coerceAtLeast(0)
        for (i in 0..limit) {
            var ok = true
            for (j in magic.indices) {
                if (bytes[i + j] != magic[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return -1
    }

    companion object {
        private const val HEAD_LEN = 4096
        private const val TAIL_LEN = 8192
        private val MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
