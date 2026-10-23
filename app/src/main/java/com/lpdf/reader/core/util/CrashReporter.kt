package com.lpdf.reader.core.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留痕（v1.3.1）。
 *
 * 目的：没有崩溃日志的「打开闪退」只能靠猜。这里在进程启动的最早阶段接管
 * 默认异常处理器，把堆栈写进应用私有目录 `filesDir/logs/last_crash.txt`，
 * 下次启动时可读取并就地展示，用户可以直接复制发出来。
 *
 * 三条硬约束：
 * 1. **绝不吞掉崩溃**：写入完成后依旧交回原处理器，系统的标准行为（日志、重启）不变。
 * 2. **绝不因记录而二次崩溃**：全部 IO 包在 runCatching 里，磁盘满 / 无权限都不影响主流程。
 * 3. **只存最后一次**：只保留最近一份，覆盖写，不无限增长。
 *
 * 不申请任何权限，不联网，日志不出设备。
 */
object CrashReporter {

    private const val DIR = "logs"
    private const val FILE = "last_crash.txt"

    /** 崩溃标记：本次启动是否处于「上次崩溃之后」，用于决定是否弹提示 */
    @Volatile
    private var installed = false

    private fun dirOf(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileOf(context: Context) = File(dirOf(context), FILE)

    /**
     * 安装处理器。**必须在 Application.onCreate 的最开头调用**，
     * 晚于此的初始化异常就抓不到了。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 交回原处理器，保持系统标准崩溃行为
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val stamp = nowStamp()
        val text = buildString {
            appendLine("===== LPdf 崩溃报告 =====")
            appendLine("时间：$stamp")
            appendLine("线程：${thread.name}")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine(sw.toString())
        }
        // 先写临时文件再改名，避免写一半断电留下半截日志
        val target = fileOf(context)
        val tmp = File(dirOf(context), "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    /** 是否有上次崩溃记录 */
    fun hasReport(context: Context): Boolean = runCatching {
        fileOf(context).let { it.exists() && it.length() > 0 }
    }.getOrDefault(false)

    /** 读取报告全文；没有则返回 null */
    fun read(context: Context): String? = runCatching {
        fileOf(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 用户确认后清除，避免每次启动都弹 */
    fun clear(context: Context) {
        runCatching { fileOf(context).delete() }
    }

    /** 报告文件路径，供「分享」使用 */
    fun file(context: Context): File = fileOf(context)

    /** 统一时间格式，崩溃记录与界面异常共用 */
    fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * 记录一次**非致命**的初始化失败。
     *
     * 用于「失败了也得让应用跑下去，但必须能查到」的场景（如 PDFBox 资源加载失败）。
     * 只写日志，不弹窗、不改全局状态 —— 真正致命的错误仍应让它自然崩溃，
     * 由默认处理器生成完整报告，这样堆栈不会被这里截断。
     */
    fun log(tag: String, error: Throwable) {
        android.util.Log.e("LPdf", "$tag: ${error.stackTraceToString()}")
    }

    /** 把界面层异常格式化成与崩溃日志同构的文本，便于用户一次复制完整 */
    fun formatUiError(error: Throwable): String = buildString {
        appendLine("===== LPdf 界面异常 =====")
        appendLine("时间：${nowStamp()}")
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine(error.stackTraceToString())
    }

    /**
     * 从堆栈里抽出第一处属于本应用的帧，作为「负责人看得懂」的那一行。
     * 形如 `PdfListPage.kt:186 (PdfListPageKt)`。
     */
    fun summarize(report: String): String? {
        val lines = report.lineSequence()
            .filter { it.trimStart().startsWith("at ") && it.contains("com.lpdf.reader") }
            .toList()
        val first = lines.firstOrNull() ?: return null
        // at com.lpdf.reader.ui.page.list.PdfListPageKt$PdfListPage$1.invoke(PdfListPage.kt:186)
        val cls = first.substringAfter("at ").substringBefore("(")
        val loc = first.substringAfter("(", "").substringBefore(")")
        val simple = cls.substringAfterLast('.').substringBefore('$')
        return if (loc.isNotBlank()) "$simple · $loc" else simple
    }

    /** 异常类名 + message，作为标题下的一行 */
    fun headline(report: String): String? {
        return report.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.substringBefore("\n")
            ?.take(140)
    }
}
