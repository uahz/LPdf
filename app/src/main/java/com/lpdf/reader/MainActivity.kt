package com.lpdf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.LPdfApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 唯一 Activity：所有页面均为 Compose 路由。
 */
class MainActivity : ComponentActivity() {

    private val settingsState = MutableStateFlow(AppSettings())
    private val settingsFlow: StateFlow<AppSettings> = settingsState

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏 + 透明系统栏（状态栏/导航栏内容随主题变化）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        val container = (application as LpdfApplication).container

        lifecycleScope.launch {
            container.prefs.settings.collect { settingsState.value = it }
        }

        setContent {
            LPdfApp(container = container, settingsFlow = settingsFlow)
        }
    }
}
