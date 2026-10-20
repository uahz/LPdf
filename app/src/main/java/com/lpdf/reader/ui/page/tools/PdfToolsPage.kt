package com.lpdf.reader.ui.page.tools

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lpdf.reader.AppContainer
import com.lpdf.reader.core.di.AppDispatchers
import com.lpdf.reader.core.util.Formatters
import com.lpdf.reader.data.pdf.PdfWriterEngine
import com.lpdf.reader.ui.glass.GlassDivider
import com.lpdf.reader.ui.glass.GlassGroup
import com.lpdf.reader.ui.glass.GlassIconButton
import com.lpdf.reader.ui.glass.GlassPillButton
import com.lpdf.reader.ui.glass.GlassSegmented
import com.lpdf.reader.ui.glass.GlassSnackbarHost
import com.lpdf.reader.ui.glass.GlassToast
import com.lpdf.reader.ui.glass.LIcon
import com.lpdf.reader.ui.glass.LIconView
import com.lpdf.reader.ui.glass.LiquidGlassScope
import com.lpdf.reader.ui.theme.LSpace
import com.lpdf.reader.ui.theme.LType
import com.lpdf.reader.ui.theme.LocalColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF 工具箱状态。
 */
data class ToolsUiState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String? = null,
    val lastOutput: String? = null,
    val toast: GlassToast? = null
)

/**
 * 文件级编辑：压缩 / 拆分 / 导出图片 / 合并。
 *
 * 输出统一写到应用私有 exports 目录，避免申请额外的存储权限；
 * 需要分享时由系统分享面板交给用户决定落点。
 */
class ToolsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    fun compress(file: File, level: Int) = run("正在压缩…") { out, p ->
        PdfWriterEngine.compress(file, out, level, p)
    }

    fun extract(file: File, pages: List<Int>) = run("正在拆分…") { out, p ->
        PdfWriterEngine.extract(file, pages, out)
    }

    fun exportImage(file: File, page: Int, jpeg: Boolean) = run("正在导出图片…") { out, p ->
        PdfWriterEngine.exportImage(
            file, page,
            if (jpeg) File(out.parentFile, out.nameWithoutExtension + ".jpg") else out,
            jpeg = jpeg,
            progress = p
        )
    }

    fun merge(files: List<File>) = run("正在合并…") { out, p ->
        PdfWriterEngine.merge(files, out, p)
    }

    /** 全部页导出为 PNG，输出到 exports/<时间戳>/ 目录 */
    fun exportAllImages(file: File, totalPages: Int, jpeg: Boolean) {
        if (_state.value.running || totalPages <= 0) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, status = "正在导出 ${totalPages} 页…", progress = 0f)
            val dir = withContext(AppDispatchers.io) {
                File(File(container.appContext().filesDir, "exports"), "img_${System.currentTimeMillis()}")
                    .apply { mkdirs() }
            }
            var ok = 0
            for (i in 0 until totalPages) {
                val out = File(dir, "page_${(i + 1).toString().padStart(3, '0')}" + if (jpeg) ".jpg" else ".png")
                val res = PdfWriterEngine.exportImage(file, i, out, jpeg = jpeg)
                if (res is com.lpdf.reader.core.model.PdfResult.Success) ok++
                _state.value = _state.value.copy(progress = (i + 1) / totalPages.toFloat())
            }
            _state.value = _state.value.copy(
                running = false,
                progress = 1f,
                status = "已导出 $ok / $totalPages 页",
                lastOutput = dir.absolutePath
            )
        }
    }

    /** 图片转 PDF：先落盘到 staging，再交给写入引擎 */
    fun imagesToPdf(uris: List<String>, size: Pair<Int, Int>) {
        if (_state.value.running || uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, status = "正在导入图片…", progress = 0f)
            val files = withContext(AppDispatchers.io) {
                val dir = File(container.appContext().cacheDir, "staging").apply { mkdirs() }
                uris.mapIndexedNotNull { idx, u ->
                    runCatching {
                        val f = File(dir, "img_${System.currentTimeMillis()}_$idx")
                        container.resolver().openInputStream(Uri.parse(u))?.use { input ->
                            f.outputStream().use { input.copyTo(it, 64 * 1024) }
                        }
                        f.takeIf { it.length() > 0 }
                    }.getOrNull()
                }
            }
            if (files.isEmpty()) {
                _state.value = _state.value.copy(running = false, status = null, toast = GlassToast(text = "没有可用的图片"))
                return@launch
            }
            val out = withContext(AppDispatchers.io) {
                File(File(container.appContext().filesDir, "exports").apply { mkdirs() },
                    "LPdf_img_${System.currentTimeMillis()}.pdf")
            }
            val res = PdfWriterEngine.imagesToPdf(
                files, out, a4 = size.first > 0,
                progress = { p -> _state.value = _state.value.copy(progress = p) }
            )
            files.forEach { it.delete() }
            _state.value = when (res) {
                is com.lpdf.reader.core.model.PdfResult.Success -> _state.value.copy(
                    running = false, progress = 1f,
                    status = "已生成：${Formatters.fileSize(res.value.length())}",
                    lastOutput = res.value.absolutePath
                )

                is com.lpdf.reader.core.model.PdfResult.Failure ->
                    _state.value.copy(
                        running = false, status = null,
                        toast = GlassToast(text = res.error.message ?: "生成失败")
                    )
            }
        }
    }

    private fun run(
        label: String,
        work: suspend (File, (Float) -> Unit) -> com.lpdf.reader.core.model.PdfResult<File>
    ) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, status = label, progress = 0f)
            val out = withContext(AppDispatchers.io) {
                val dir = File(container.appContext().filesDir, "exports").apply { mkdirs() }
                File(dir, "LPdf_${System.currentTimeMillis()}.pdf")
            }
            val res = work(out) { p ->
                _state.value = _state.value.copy(progress = p)
            }
            _state.value = when (res) {
                is com.lpdf.reader.core.model.PdfResult.Success -> _state.value.copy(
                    running = false,
                    progress = 1f,
                    status = "完成：${Formatters.fileSize(res.value.length())}",
                    lastOutput = res.value.absolutePath
                )

                is com.lpdf.reader.core.model.PdfResult.Failure -> {
                    val msg = res.error.message ?: "操作失败"
                    _state.value.copy(running = false, status = null, toast = GlassToast(text = msg))
                }
            }
        }
    }

    fun dismissToast() = _state.value.let { _state.value = it.copy(toast = null) }
}

/**
 * 工具箱页。入口在列表页长按菜单 →「工具箱」。
 *
 * 源文件可能是 content:// （MediaStore）或 file://（导入副本），
 * 统一在进入时暂存为私有文件，保证写入通路只面对 File。
 */
@Composable
fun PdfToolsPage(
    container: AppContainer,
    uri: String,
    displayName: String,
    pageCount: Int,
    onBack: () -> Unit
) {
    var file by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<File?>(null) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(uri) {
        val direct = File(uri.removePrefix("file://"))
        if (direct.exists() && direct.canRead()) {
            file = direct
        } else {
            val res = PdfWriterEngine.stageCopy(
                container.resolver(),
                Uri.parse(uri),
                container.appContext().cacheDir,
                displayName.ifBlank { "document.pdf" }
            )
            when (res) {
                is com.lpdf.reader.core.model.PdfResult.Success -> file = res.value
                is com.lpdf.reader.core.model.PdfResult.Failure -> error = res.error.message
            }
        }
    }
    val f = file
    if (f == null) {
        ToolPreparing(error)
        return
    }
    PdfToolsContent(container, f, displayName, pageCount, onBack)
}

@Composable
private fun ToolPreparing(error: String?) {
    val colors = LocalColors.current
    LiquidGlassScope(
        modifier = Modifier.background(colors.pageBg),
        content = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "正在准备文件…",
                    style = LType.Caption,
                    color = if (error != null) colors.danger else colors.secondary
                )
            }
        }
    )
}

@Composable
private fun PdfToolsContent(
    container: AppContainer,
    file: File,
    displayName: String,
    pageCount: Int,
    onBack: () -> Unit
) {
    val vm = rememberToolsViewModel(container)
    val state by vm.state.collectAsState()
    val colors = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val totalPages = androidx.compose.runtime.remember(pageCount, file) {
        if (pageCount > 0) pageCount else PdfWriterEngine.pageCountOf(file)
    }

    var level by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var rangeText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("1") }
    var exportPage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var a4 by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    LiquidGlassScope(
        modifier = Modifier.background(colors.pageBg),
        content = {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LSpace.pageHorizontal,
                    end = LSpace.pageHorizontal,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
        ) {
            Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(LIcon.ChevronLeft, "返回", onBack, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("工具箱", style = LType.TitleL, color = colors.text)
                    Text(
                        Formatters.middleEllipsis(displayName.ifBlank { file.name }, 26) +
                            " · ${Formatters.fileSize(file.length())} · $totalPages 页",
                        style = LType.Micro,
                        color = colors.secondary
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            GlassGroup("压缩") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("质量", style = LType.Body, color = colors.text, modifier = Modifier.weight(1f))
                    GlassSegmented(
                        options = listOf("轻度", "标准", "强力"),
                        selectedIndex = level,
                        onSelect = { level = it },
                        modifier = Modifier.width(168.dp),
                        height = 32.dp
                    )
                }
                GlassDivider()
                Box(Modifier.padding(16.dp)) {
                    GlassPillButton(
                        text = "开始压缩",
                        onClick = { vm.compress(file, level) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.running
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassGroup("拆分 / 提取页") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "输入页码范围，如 1-3,7（共 $totalPages 页）",
                        style = LType.Micro,
                        color = colors.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = rangeText,
                        onValueChange = { rangeText = it },
                        singleLine = true,
                        textStyle = LType.Body.copy(color = colors.text),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassPillButton(
                        text = "提取为新 PDF",
                        onClick = {
                            val pages = Formatters.parsePageRanges(rangeText, totalPages).map { it - 1 }
                            if (pages.isEmpty()) {
                                vm.dismissToast()
                            } else vm.extract(file, pages)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.running
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassGroup("导出图片") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("页码", style = LType.Body, color = colors.text, modifier = Modifier.weight(1f))
                    GlassPillButton("-", { exportPage = (exportPage - 1).coerceAtLeast(0) }, height = 32.dp)
                    Text(
                        "  ${exportPage + 1} / $totalPages  ",
                        style = LType.Caption,
                        color = colors.text
                    )
                    GlassPillButton(
                        "+",
                        { exportPage = (exportPage + 1).coerceAtMost((totalPages - 1).coerceAtLeast(0)) },
                        height = 32.dp
                    )
                }
                GlassDivider()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassPillButton(
                        "本页 PNG",
                        { vm.exportImage(file, exportPage, false) },
                        Modifier.weight(1f),
                        height = 40.dp,
                        enabled = !state.running
                    )
                    GlassPillButton(
                        "本页 JPG",
                        { vm.exportImage(file, exportPage, true) },
                        Modifier.weight(1f),
                        height = 40.dp,
                        enabled = !state.running
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassPillButton(
                        "全部页导出 PNG",
                        { vm.exportAllImages(file, totalPages, false) },
                        Modifier.weight(1f),
                        height = 40.dp,
                        enabled = !state.running
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))

            GlassGroup("图片转 PDF") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "从相册选择 1~50 张图片，按顺序生成 A4 版式 PDF",
                        style = LType.Micro,
                        color = colors.secondary
                    )
                    Spacer(Modifier.height(10.dp))
                    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(50)
                    ) { uris ->
                        if (uris.isNotEmpty()) {
                            vm.imagesToPdf(
                                uris.map { it.toString() },
                                if (a4) 595 to 842 else 0 to 0
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            "选择图片",
                            {
                                runCatching {
                                    pickImages.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            },
                            Modifier.weight(1f),
                            height = 40.dp,
                            enabled = !state.running
                        )
                        GlassPillButton(
                            if (a4) "A4" else "原尺寸",
                            { a4 = !a4 },
                            Modifier.width(96.dp),
                            height = 40.dp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassGroup("结果") {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LIconView(
                        if (state.running) LIcon.Rotate else LIcon.Check,
                        size = 18.dp,
                        color = if (state.running) colors.secondary else colors.success
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.status ?: "尚未执行操作",
                            style = LType.Caption,
                            color = colors.text
                        )
                        if (state.running) {
                            Text(
                                "${(state.progress * 100).toInt()}%",
                                style = LType.Micro,
                                color = colors.secondary
                            )
                        } else if (state.lastOutput != null) {
                            Text("已保存到应用私有目录 exports/", style = LType.Micro, color = colors.secondary)
                        }
                    }
                    if (state.lastOutput != null && !state.running) {
                        GlassPillButton(
                            "分享",
                            {
                                val f = File(state.lastOutput!!)
                                val uri = Uri.fromFile(f)
                                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = if (f.extension == "jpg") "image/jpeg" else "application/pdf"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(i, "导出到…")
                                    )
                                }
                            },
                            height = 34.dp
                        )
                    }
                }
            }
        }

        },

        overlay = {
            GlassSnackbarHost(
                toast = state.toast,
                onDismiss = vm::dismissToast,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            )
        }
    )
}

@Composable
private fun rememberToolsViewModel(container: AppContainer): ToolsViewModel =
    viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ToolsViewModel(container) as T
    })
