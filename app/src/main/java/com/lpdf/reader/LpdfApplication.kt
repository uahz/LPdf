package com.lpdf.reader

import android.app.Application
import android.content.Context
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.util.CrashReporter
import com.lpdf.reader.data.local.AnnotationStore
import com.lpdf.reader.data.local.BookmarkStore
import com.lpdf.reader.data.local.MediaStoreSource
import com.lpdf.reader.data.local.PrefsRepository
import com.lpdf.reader.data.local.SignatureStore
import com.lpdf.reader.data.local.ThumbnailStore
import com.lpdf.reader.data.network.LinkImporter
import com.lpdf.reader.data.network.UpdateChecker
import com.lpdf.reader.data.pdf.PdfValidator
import com.lpdf.reader.data.pdf.PdfiumEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。手动依赖注入（单 Activity + 少量全局对象，无需 DI 框架）。
 */
class LpdfApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 第一件事：装崩溃留痕。放在任何初始化之前，晚一行就可能漏掉启动崩溃。
        runCatching { CrashReporter.install(this) }

        PdfiumEngine.init(this)
        container = AppContainer(this)

        // 启动清理属于「锦上添花」，绝不能因为它挂掉整个应用：
        // 早期版本这里是无保护的 launch，一次磁盘异常就会表现为「打开即闪退」。
        CoroutineScope(SupervisorJob() + AppDispatchers.io).launch {
            runCatching { container.linkImporter.cleanOldImports() }
            runCatching { cleanTempDirs(this@LpdfApplication) }
        }
    }

    /**
     * 清理临时产物。
     *
     * 导出带标注的 PDF、工具箱的拆合/压缩，都会先写 `cacheDir` 下的中间文件。
     * 进程被杀、导出失败、用户中途退出，这些文件都不会自己消失。
     * 长期积累会吃掉几百 MB 空间，且在「存储」页里看不出来源。
     *
     * 只清我们自己的子目录，不碰 `cacheDir` 根（可能有系统或其他库的文件）。
     */
    private fun cleanTempDirs(context: Context) {
        listOf("flat", "staging").forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            if (!dir.exists()) return@forEach
            dir.listFiles()?.forEach { f ->
                // 超过 1 小时的中间产物一定是残留：正常流程不会占用这么久
                val stale = System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L
                if (stale) runCatching { f.deleteRecursively() }
            }
        }
    }
}

/**
 * 依赖容器。所有单例在此集中创建，便于替换与测试。
 */
class AppContainer(private val context: Context) {
    val prefs = PrefsRepository(context)
    val mediaStore = MediaStoreSource(context)
    val validator = PdfValidator(context.contentResolver)
    val thumbnails = ThumbnailStore(context)
    val annotations = AnnotationStore(context.filesDir)
    val bookmarks = BookmarkStore(context.filesDir)
    val signatures = SignatureStore(context.filesDir)
    val linkImporter = LinkImporter(context, prefs)
    val updateChecker = UpdateChecker(context, prefs)

    /** 统一出口，避免页面层到处拿 Context */
    fun resolver() = context.contentResolver
    fun appContext(): Context = context
}
