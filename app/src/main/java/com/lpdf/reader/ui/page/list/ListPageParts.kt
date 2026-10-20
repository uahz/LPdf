package com.lpdf.reader.ui.page.list

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer

/**
 * 列表页 ViewModel 工厂。显式传入 [AppContainer]，避免全局可变单例。
 */
@Composable
fun rememberListViewModel(container: AppContainer): ListViewModel {
    return viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            ListViewModel(container) as T
    })
}
