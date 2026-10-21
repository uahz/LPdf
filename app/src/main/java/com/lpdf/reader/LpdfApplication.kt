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
