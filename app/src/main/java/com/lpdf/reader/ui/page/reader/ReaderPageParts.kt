package com.lpdf.reader.ui.page.reader

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer

/**
 * 阅读页 ViewModel 工厂。文档身份由 [ReaderViewModel.bind] 在组合期绑定。
 *
 * ## 为什么必须按文档传 key（v1.6.1 修复）
 *
 * 原来这里是一句 `viewModel { ReaderViewModel(container) }`，不传 key ——
 * 而 [viewModel] 的默认 key 取自调用点，所有阅读页会共用同一个实例。
 * 后果是「打开 A → 返回 → 打开 B」时 `onCleared` 迟迟不触发，
 * [ReaderViewModel.flush] 也就跑不起来；而「自动保存标注」被用户关掉时，
 * 落盘完全依赖 flush，**标注会丢**。
 *
 * 另一层影响是会话级状态串台：`highlightTouched` / `inkWidthTouched`
 * 存在同一个实例里，会让上一本文档里手动选过的颜色继续压制下一本的默认色。
 *
 * 用文档 uri 作 key：同一本书来回进出复用同一个实例（会话与位图缓存都还在），
 * 不同书各自独立，`onCleared` 也才会在真正离开时触发。
 */
@Composable
fun rememberReaderViewModel(container: AppContainer, docId: String): ReaderViewModel {
    return viewModel(
        key = "reader:$docId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(container) as T
        }
    )
}
