package com.lpdf.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.lpdf.reader.AppContainer
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.glass.LocalGlassCapability
import com.lpdf.reader.ui.glass.rememberGlassCapability
import com.lpdf.reader.ui.page.list.PdfListPage
import com.lpdf.reader.ui.page.reader.PdfReaderPage
import com.lpdf.reader.ui.page.settings.SettingsPage
import com.lpdf.reader.ui.theme.LPdfTheme
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val LIST = "list"
    const val READER = "reader"
    const val SETTINGS = "settings"
    const val TOOLS = "tools"
    fun reader(uri: String, name: String) =
        "$READER?uri=${android.net.Uri.encode(uri)}&name=${android.net.Uri.encode(name)}"
    fun tools(path: String, name: String, pages: Int) =
        "$TOOLS?path=${android.net.Uri.encode(path)}&name=${android.net.Uri.encode(name)}&pages=$pages"
}

/**
 * 应用根：主题 + 三页导航。
 *
 * 玻璃采样：每个页面自身是 [com.lpdf.reader.ui.glass.LiquidGlassScope]，
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

    LPdfTheme(
        dark = dark,
        // 把探测结果并进 GlassSpec：设备不支持 AGSL 时整体强度归零，
        // 玻璃退化为纯模糊 + 底色，而不是靠每个组件自己再判断一次
        glass = settings.toGlassSpec().let { spec ->
            if (glassCapability.hasRefraction) spec else spec.copy(intensity = 0f)
        }
    ) {
        CompositionLocalProvider(LocalGlassCapability provides glassCapability) {
            NavHost(navController = navController, startDestination = Routes.LIST) {
                composable(Routes.LIST) {
                    PdfListPage(
                        container = container,
                        settings = settings,
                        onOpenReader = { uri, name -> navController.navigate(Routes.reader(uri, name)) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenTools = { path, name, pages ->
                            navController.navigate(Routes.tools(path, name, pages))
                        }
                    )
                }
                composable(
                    route = "${Routes.READER}?uri={uri}&name={name}",
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    val uri = entry.arguments?.getString("uri").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    PdfReaderPage(
                        container = container,
                        settings = settings,
                        uri = uri,
                        displayName = name,
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
                    val path = entry.arguments?.getString("path").orEmpty()
                    val pages = entry.arguments?.getInt("pages") ?: 0
                    com.lpdf.reader.ui.page.tools.PdfToolsPage(
                        container = container,
                        uri = path,
                        displayName = entry.arguments?.getString("name").orEmpty(),
                        pageCount = pages,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsPage(
                        container = container,
                        settings = settings,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
