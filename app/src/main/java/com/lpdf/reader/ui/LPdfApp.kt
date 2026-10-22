package com.lpdf.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.model.PdfItem
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.data.local.LibraryAggregator
import com.lpdf.reader.data.local.scanAll
import com.lpdf.reader.ui.glass.LocalGlassCapability
import com.lpdf.reader.ui.glass.rememberGlassCapability
import com.lpdf.reader.ui.nav.RootTab
import com.lpdf.reader.ui.nav.RootTabHost
import com.lpdf.reader.ui.page.discover.DiscoverEntry
import com.lpdf.reader.ui.page.discover.DiscoverPage
import com.lpdf.reader.ui.page.home.HomePage
import com.lpdf.reader.ui.page.list.PdfListPage
import com.lpdf.reader.ui.page.marks.BookmarksPage
import com.lpdf.reader.ui.page.reader.PdfReaderPage
import com.lpdf.reader.ui.page.settings.SettingsPage
import com.lpdf.reader.ui.theme.LPdfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object Routes {
    /** 五 Tab 宿主。Tab 之间的切换不经过导航栈，只切宿主内部状态 */
    const val ROOT = "root"
    const val READER = "reader"
    const val TOOLS = "tools"

    /**
     * @param modified 文件修改时间。**必须传** —— 标注/书签/阅读进度的存储键是
     *   `hash(uri|mtime)`，阅读页若拿到 0 就会读写另一套文件，
     *   表现为「标注画了但重开没了」「书签页看不到刚加的书签」。
     *   v1.5.x 之前传 0 是既有缺陷，v1.6.0 一并修正。
     */
    fun reader(uri: String, name: String, page: Int = -1, modified: Long = 0L) =
        "$READER?uri=${android.net.Uri.encode(uri)}" +
            "&name=${android.net.Uri.encode(name)}" +
            "&mtime=$modified" +
            (if (page >= 0) "&page=$page" else "")

    fun tools(path: String, name: String, pages: Int) =
        "$TOOLS?path=${android.net.Uri.encode(path)}&name=${android.net.Uri.encode(name)}&pages=$pages"
}

/**
 * 应用根：主题 + 导航（v1.6.0 改为五 Tab 结构）。
 *
 * ```
 * NavHost
 *  ├─ root      ← RootTabHost：首页/文档/书签/发现/我的，共用一条玻璃底栏
 *  ├─ reader    ← 全屏沉浸，无底栏
 *  └─ tools     ← 全屏沉浸，无底栏
 * ```
 *
 * 阅读页必须留在 NavHost 里而不是做成 Tab 内页：它有自绘的顶栏与工具岛，
 * 底部要放页码胶囊和编辑岛 —— 再叠一条 Tab 底栏会打架。
 *
 * 玻璃采样：root 由 [RootTabHost] 自己开一个
 * [com.lpdf.reader.ui.glass.LiquidGlassScope]，阅读页与工具页各自也是一个，
 * 保证玻璃只采样本页内容。
 */
@Composable
fun LPdfApp(
    container: AppContainer,
    settingsFlow: StateFlow<AppSettings>,
    navController: NavHostController = rememberNavController(),
    /** 界面层致命异常回调：由 Activity 展示错误页，避免用户看到空白屏 */
    onFatal: (Throwable) -> Unit = {}
) {
    val settings by settingsFlow.collectAsStateWithLifecycle()
    val dark = when (settings.themeMode) {
        com.lpdf.reader.data.local.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        com.lpdf.reader.data.local.ThemeMode.LIGHT -> false
        com.lpdf.reader.data.local.ThemeMode.DARK -> true
    }

    // 玻璃能力探测：整个应用共用一个实例。
    // 任一组件在绘制期降级，全局同步降级，避免「一半折射一半不折射」的割裂感。
    val glassCapability = rememberGlassCapability()

    // 「高亮色取自文档」：打开时用最近打开的那份文档的封面主色做强调色。
    // 关掉（或不取色成功）时传 null，走主题默认蓝。
    val contentAccent = rememberContentAccent(container, settings.accentFromContent)

    LPdfTheme(
        dark = dark,
        // 把探测结果并进 GlassSpec：设备不支持 AGSL 时整体强度归零，
        // 玻璃退化为纯模糊 + 底色，而不是靠每个组件自己再判断一次
        glass = settings.toGlassSpec().let { spec ->
            if (glassCapability.hasRefraction) spec else spec.copy(intensity = 0f)
        },
        accentOverride = contentAccent
    ) {
        CompositionLocalProvider(LocalGlassCapability provides glassCapability) {
            NavHost(navController = navController, startDestination = Routes.ROOT) {
                composable(Routes.ROOT) {
                    RootTabs(
                        container = container,
                        settings = settings,
                        onOpenReader = { uri, name, modified, page ->
                            navController.navigate(Routes.reader(uri, name, page, modified))
                        },
                        onOpenTools = { path, name, pages ->
                            navController.navigate(Routes.tools(path, name, pages))
                        }
                    )
                }
                composable(
                    route = "${Routes.READER}?uri={uri}&name={name}&mtime={mtime}&page={page}",
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("mtime") { type = NavType.LongType; defaultValue = 0L },
                        // 从书签 / 继续阅读进来时要直接跳到指定页；
                        // -1 表示「按上次进度」（从文档列表普通打开）
                        navArgument("page") { type = NavType.IntType; defaultValue = -1 }
                    )
                ) { entry ->
                    PdfReaderPage(
                        container = container,
                        settings = settings,
                        uri = entry.arguments?.getString("uri").orEmpty(),
                        displayName = entry.arguments?.getString("name").orEmpty(),
                        lastModified = entry.arguments?.getLong("mtime") ?: 0L,
                        startPage = entry.arguments?.getInt("page") ?: -1,
                        onClose = { navController.popBackStack() }
                    )
                }
                composable(
                    route = "${Routes.TOOLS}?path={path}&name={name}&pages={pages}",
                    arguments = listOf(
                        navArgument("path") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("pages") { type = NavType.IntType; defaultValue = 0 }
                    )
                ) { entry ->
                    com.lpdf.reader.ui.page.tools.PdfToolsPage(
                        container = container,
                        uri = entry.arguments?.getString("path").orEmpty(),
                        displayName = entry.arguments?.getString("name").orEmpty(),
                        pageCount = entry.arguments?.getInt("pages") ?: 0,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/**
 * 五 Tab 的内容装配。
 *
 * 和 [RootTabHost] 的分工：宿主只管「长什么样、怎么切」，
 * 这里管「每个 Tab 放什么、点了去哪」。拆开是因为宿主完全不关心业务，
 * 而这里的每个分支都要拿 container / settings / 导航回调。
 */
@Composable
private fun RootTabs(
    container: AppContainer,
    settings: AppSettings,
    onOpenReader: (uri: String, name: String, modified: Long, page: Int) -> Unit,
    onOpenTools: (path: String, name: String, pages: Int) -> Unit
) {
    var selected by remember { mutableStateOf(RootTab.HOME) }

    RootTabHost(
        selected = selected,
        onSelect = { selected = it },
        // 重复点当前 Tab = 回到顶部。各页的滚动状态现在还在各自内部，
        // 这里先留空实现；等 v1.7 把 listState 提到宿主再启用。
        onReselect = { },
    ) { tab ->
        when (tab) {
            RootTab.HOME -> HomeTab(
                container = container,
                onOpenReader = onOpenReader,
                onGoDocs = { selected = RootTab.DOCS }
            )
            RootTab.DOCS -> PdfListPage(
                container = container,
                settings = settings,
                onOpenReader = { uri, name, modified -> onOpenReader(uri, name, modified, -1) },
                onOpenTools = onOpenTools
            )
            RootTab.BOOKMARKS -> BookmarksPage(
                container = container,
                onOpenAt = { uri, name, modified, page ->
                    onOpenReader(uri, name, modified, page)
                }
            )
            RootTab.DISCOVER -> DiscoverPage(
                onEntry = { key ->
                    when (key) {
                        // 工具类统一走工具页（已支持合并/拆分/压缩/水印/签名）
                        DiscoverEntry.Key.MERGE,
                        DiscoverEntry.Key.SPLIT,
                        DiscoverEntry.Key.COMPRESS,
                        DiscoverEntry.Key.WATERMARK,
                        DiscoverEntry.Key.SIGNATURE -> onOpenTools("", "", 0)
                        // 导入与添加目录回到文档页，由列表页自己的系统选择器处理
                        DiscoverEntry.Key.IMPORT,
                        DiscoverEntry.Key.SCAN_DIR -> selected = RootTab.DOCS
                        // 更新检查在「我的」里（设置页已有该入口）
                        DiscoverEntry.Key.UPDATE -> selected = RootTab.PROFILE
                    }
                }
            )
            // 设置作为「我的」Tab 内嵌：onBack = null 时它不画返回按钮
            RootTab.PROFILE -> SettingsPage(
                container = container,
                settings = settings,
                onBack = null
            )
        }
    }
}

/**
 * 首页 Tab：把「最近在读」从存储里捞出来。
 *
 * 单独抽出来是因为它需要自己的刷新时机 —— 用户刚在阅读页翻了 20 页，
 * 切回首页看到的不能还是旧进度。用 [Lifecycle] 的 ON_RESUME 作 key，
 * 每次页面重新可见就重读一次。
 */
@Composable
private fun HomeTab(
    container: AppContainer,
    onOpenReader: (String, String, Long, Int) -> Unit,
    onGoDocs: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var docs by remember { mutableStateOf<List<PdfItem>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 每次回到前台自增，作为下面 LaunchedEffect 的 key。
    // 只用 lifecycle 对象作 key 是不够的：它的实例在整个 Activity 生命周期里
    // 不变，所以从阅读页返回时 Effect 不会重跑，首页会一直显示进去之前的旧进度。
    var resumeTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) {
        val hidden = container.prefs.hiddenIds.first()
        docs = withContext(Dispatchers.IO) {
            runCatching { container.mediaStore.scanAll().filterNot { hidden.contains(it.id) } }
                .getOrDefault(emptyList())
        }
        progress = container.prefs.progressMap.first()
    }

    val recent = LibraryAggregator.reading(docs, progress) { item ->
        container.annotations.keyOf(item.uri, item.lastModified)
    }
    val recentImports = remember(docs) { docs.sortedByDescending { it.lastModified }.take(4) }

    HomePage(
        recent = recent,
        recentImports = recentImports,
        totalDocs = docs.size,
        totalPages = docs.sumOf { it.pageCount ?: 0 },
        onContinue = { hit ->
            onOpenReader(hit.doc.uri, hit.doc.displayName, hit.doc.lastModified, hit.page)
        },
        onOpen = { item: PdfItem ->
            onOpenReader(item.uri, item.displayName, item.lastModified, -1)
        },
        onImport = onGoDocs,
        onSeeAll = onGoDocs
    )
}

/**
 * 「高亮色取自文档」的实现（v1.6.1）。
 *
 * 取**最近修改过的那份文档**封面的主色。选它而不是让用户挑一份，
 * 是因为这个开关的意图是「让界面跟着我最近在看的东西变」，不需要额外交互。
 *
 * 只在缩略图已经存在时取色 —— 不为了一个装饰性的颜色去渲染一份 PDF，
 * 那会让首页首帧多等几百毫秒，代价远大于收益。没有缩略图就退回默认色。
 */
@Composable
private fun rememberContentAccent(
    container: AppContainer,
    enabled: Boolean
): Color? {
    var accent by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            accent = null
            return@LaunchedEffect
        }
        accent = withContext(Dispatchers.IO) {
            runCatching {
                val hidden = container.prefs.hiddenIds.first()
                val doc = container.mediaStore.scanAll()
                    .filterNot { hidden.contains(it.id) }
                    .maxByOrNull { it.lastModified }
                    ?: return@runCatching null
                val key = container.thumbnails.cacheKey(doc.id, doc.lastModified)
                // 没缩略图就不取色：宁可回默认色，也不为装饰去渲染一整页 PDF
                if (!container.thumbnails.has(key)) return@runCatching null
                container.thumbnails.sampleColor(key)?.let { Color(it) }
            }.getOrNull()
        }
    }
    return accent
}
