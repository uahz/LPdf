package com.lpdf.reader.data.local

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.model.PdfHealth
import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.core.model.PdfSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * MediaStore 扫描源。
 *
 * - 只用 READ_MEDIA_DOCUMENTS 可访问的 Files 集合
 * - 分页 cursor，每批 [BATCH] 条，交给上层增量渲染
 * - 去重键：documentId
 *
 * 关于 [SuppressLint] "Recycle"：本文件的 4 处 `contentResolver.query(...)` 全部紧跟
 * `?.use { ... }`，Cursor 由 Kotlin 标准库的 Closeable.use 保证关闭（含异常路径）。
 * Lint 的 Recycle 检测器对「可空接收者 + use」这种写法识别不出来，是已知误报，
 * 不是真的漏 close —— 故在此统一抑制，而不是把代码改写成不如原来清晰的 try/finally。
 */
@SuppressLint("Recycle")
class MediaStoreSource(private val context: Context) {

    /**
     * 扫描所有 PDF。onBatch 会在 IO 线程被回调，调用方负责切主线程。
     */
    suspend fun scan(onBatch: (List<PdfItem>) -> Unit) = withContext(AppDispatchers.io) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATA
        )
        // MIME 或扩展名任一匹配，避免部分机型 MIME 缺失
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("application/pdf", "%.pdf")
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val batch = ArrayList<PdfItem>(BATCH)
        try {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    ensureActive()
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    if (!name.lowercase().endsWith(".pdf")) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    batch += PdfItem(
                        id = id.toString(),
                        uri = uri.toString(),
                        displayName = name,
                        sizeBytes = c.getLong(sizeCol),
                        lastModified = c.getLong(dateCol) * 1000L,
                        source = PdfSource.MEDIA_STORE,
                        health = PdfHealth.UNKNOWN
                    )
                    if (batch.size >= BATCH) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }
        } catch (e: SecurityException) {
            // 未授权：交给上层展示玻璃权限卡片
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 某些 ROM 查询失败，视为空结果而非崩溃
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    /** 目录扫描（SAF）：递归遍历子目录中的 .pdf */
    suspend fun scanTree(treeUri: Uri): List<PdfItem> = withContext(AppDispatchers.io) {
        val out = mutableListOf<PdfItem>()
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val job = withTimeoutOrNull(TREE_TIMEOUT_MS) {
            walk(tree, out)
        }
        if (job == null) { /* 超时返回已扫到的部分 */ }
        out
    }

    private fun walk(dir: androidx.documentfile.provider.DocumentFile, out: MutableList<PdfItem>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (out.size >= MAX_TREE_FILES) return
            if (f.isDirectory) {
                walk(f, out)
            } else {
                val name = f.name ?: continue
                if (!name.lowercase().endsWith(".pdf")) continue
                out += PdfItem(
                    id = f.uri.toString(),
                    uri = f.uri.toString(),
                    displayName = name,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    source = PdfSource.SAF_DIR,
                    health = PdfHealth.UNKNOWN,
                    persistedUriPermission = true
                )
            }
        }
    }

    /** 提取路径（用于详情展示；Android 13+ 上 DATA 可能为空） */
    fun readablePath(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.toString()
        }.getOrDefault(uri.toString())
    }

    /** 查询显示名（SAF / MediaStore 通用） */
    fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 查询文件大小 */
    fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrNull() ?: 0L

    companion object {
        const val BATCH = 30
        private const val TREE_TIMEOUT_MS = 8000L
        private const val MAX_TREE_FILES = 2000
    }
}

/**
 * 一次性取全量结果。
 *
 * [MediaStoreSource.scan] 设计成流式（列表页要边扫边渲染，不能等全部扫完），
 * 但「首页最近在读」「书签聚合」这类页面必须先有完整文档集合才能算键，
 * 所以这里把回调攒成 List。仅用于非首屏关键路径。
 */
suspend fun MediaStoreSource.scanAll(): List<PdfItem> {
    val out = ArrayList<PdfItem>()
    scan { out += it }
    return out
}
