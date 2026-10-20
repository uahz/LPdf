package com.lpdf.reader.ui.page.reader

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer

/**
 * 阅读页 ViewModel 工厂。文档身份由 [ReaderViewModel.bind] 在组合期绑定，
 * 因此同一个 ViewModel 实例可复用于不同文档（导航回退时自动切换）。
 */
@Composable
fun rememberReaderViewModel(container: AppContainer): ReaderViewModel {
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(container) as T
    })
}
