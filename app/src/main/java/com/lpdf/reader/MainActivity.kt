package com.lpdf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.LPdfApp
import com.lpdf.reader.ui.glass.FatalErrorScreen
import com.lpdf.reader.ui.theme.LPdfTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 唯一 Activity：所有页面均为 Compose 路由。
 */
class MainActivity : ComponentActivity() {

    private val settingsState = MutableStateFlow(AppSettings())
    private val settingsFlow: StateFlow<AppSettings> = settingsState

    /** 界面层不可恢复错误：由 [LPdfApp] 的 AnimatedContent 之外兜底捕获后置入 */
    private var fatal by mutableStateOf<Throwable?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须先 super.onCreate：Activity 的 window 在 super 之前尚未 attach，
        // 而 enableEdgeToEdge 内部第一步就是 window.decorView。
        // 顺序写反 = 每次冷启动必崩（Android 12+ 必现）。
        super.onCreate(savedInstanceState)

        // 全屏 + 透明系统栏（状态栏/导航栏内容随主题变化）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )

        val container = (application as LpdfApplication).container

        lifecycleScope.launch {
            container.prefs.settings.collect { settingsState.value = it }
        }

        setContent {
            val err = fatal
            if (err != null) {
                // 出错时用浅色主题，保证错误页在任何设置下都看得清
                LPdfTheme(dark = false) {
                    FatalErrorScreen(err) { fatal = null }
                }
            } else {
                LPdfApp(
                    container = container,
                    settingsFlow = settingsFlow,
                    onFatal = { fatal = it }
                )
            }
        }
    }
}
