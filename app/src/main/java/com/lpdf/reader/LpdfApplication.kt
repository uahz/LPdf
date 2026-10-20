package com.lpdf.reader

import android.app.Application
import android.content.Context
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.data.local.AnnotationStore
import com.lpdf.reader.data.local.MediaStoreSource
import com.lpdf.reader.data.local.PrefsRepository
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
        PdfiumEngine.init(this)
        container = AppContainer(this)
        // 启动时清理过期导入缓存（>7 天），不阻塞主线程
        CoroutineScope(SupervisorJob() + AppDispatchers.io).launch {
            container.linkImporter.cleanOldImports()
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
    val linkImporter = LinkImporter(context, prefs)
    val updateChecker = UpdateChecker(context, prefs)

    /** 统一出口，避免页面层到处拿 Context */
    fun resolver() = context.contentResolver
    fun appContext(): Context = context
}
