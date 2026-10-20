package com.lpdf.reader.core.model

/**
 * 统一错误类型。所有 UseCase / Repository 失败均返回该类型，
 * 由 UI 层映射为「玻璃提示」，禁止抛给系统弹窗。
 */
sealed class PdfError(message: String) : Exception(message) {

    /** 文件头不是 %PDF- 或 trailer 解析失败 */
    class Corrupted(message: String = "文件可能已损坏或格式不受支持") : PdfError(message)

    /** 需要密码 */
    class PasswordRequired(message: String = "该 PDF 已加密，请输入密码") : PdfError(message)

    /** 密码错误 */
    class WrongPassword(message: String = "密码不正确") : PdfError(message)

    /** 没有文档访问权限 */
    class PermissionDenied(message: String = "需要文档访问权限") : PdfError(message)

    /** 用户授权的目录已失效 */
    class DirectoryRevoked(message: String = "目录访问权限已失效，请重新选择") : PdfError(message)

    /** 存储空间不足 */
    class StorageFull(message: String = "存储空间不足") : PdfError(message)

    /** 网络错误（离线模式 / 非 Wi-Fi / 服务端异常） */
    class Network(message: String = "网络不可用，已切回本地模式") : PdfError(message)

    /** 离线模式下被拦截 */
    class OfflineBlocked(message: String = "已开启完全离线模式，本次请求被阻止") : PdfError(message)

    /** 仅 Wi-Fi 策略拦截 */
    class MeteredBlocked(message: String = "当前为移动网络，已按设置暂停联网") : PdfError(message)

    /** 文件不存在 */
    class NotFound(message: String = "文件不存在或已被移动") : PdfError(message)

    /** 写入失败 */
    class WriteFailed(message: String = "保存失败，请重试") : PdfError(message)

    /** 其他未知错误，保留原始信息便于本地排查 */
    class Unknown(message: String = "打开失败，请稍后重试", cause: Throwable? = null) :
        PdfError(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}

/** 统一结果封装 */
sealed class PdfResult<out T> {
    data class Success<T>(val value: T) : PdfResult<T>()
    data class Failure(val error: PdfError) : PdfResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): PdfError? = (this as? Failure)?.error
    inline fun <R> map(block: (T) -> R): PdfResult<R> = when (this) {
        is Success -> Success(block(value))
        is Failure -> this
    }
}

inline fun <T> runPdfCatching(block: () -> T): PdfResult<T> = try {
    PdfResult.Success(block())
} catch (e: PdfError) {
    PdfResult.Failure(e)
} catch (t: Throwable) {
    PdfResult.Failure(PdfError.Unknown(cause = t))
}
