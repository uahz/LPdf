package com.lpdf.reader.core.model

/**
 * 文件来源。用于列表角标与去重策略。
 */
enum class PdfSource {
    /** MediaStore 扫描到的文档 */
    MEDIA_STORE,

    /** 用户通过 SAF 手动授权的目录 */
    SAF_DIR,

    /** 通过在线链接导入（仍落盘为本地文件） */
    LINK_IMPORT
}

/**
 * 文件健康状态。UNKNOWN 表示尚未做轻量校验。
 */
enum class PdfHealth {
    UNKNOWN,
    OK,
    BROKEN,
    ENCRYPTED
}

/**
 * 列表中的一个 PDF 条目。
 *
 * @param id 稳定标识：MediaStore 使用 documentId，SAF 使用 uri 字符串
 * @param displayName 展示文件名
 * @param sizeBytes 字节大小
 * @param lastModified 最后修改时间（毫秒）
 * @param highlightColor 首页缩略图采样主色 ARGB，用于液态玻璃的实时高亮色；null 表示尚未采样
 * @param persistedUriPermission 是否持有持久化目录权限（SAF）
 */
data class PdfItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: PdfSource = PdfSource.MEDIA_STORE,
    val health: PdfHealth = PdfHealth.UNKNOWN,
    val pageCount: Int? = null,
    val highlightColor: Long? = null,
    val persistedUriPermission: Boolean = false
) {
    val isBroken: Boolean get() = health == PdfHealth.BROKEN
    val isEncrypted: Boolean get() = health == PdfHealth.ENCRYPTED
}
