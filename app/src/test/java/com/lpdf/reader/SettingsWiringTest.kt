package com.lpdf.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置项是否真的生效（v1.6.1）。
 *
 * ## 这批缺陷的性质
 *
 * 设置页里有 7 个开关可以点、可以存进 DataStore，但**代码里从来没人读**。
 * 用户改完、重启、发现毫无变化 —— 这比「功能没做」更伤信任。
 *
 * 而且它极难被常规测试发现：
 * - 编译不报错（字段存在，只是没人用）
 * - UI 测试不报错（开关真的会翻转，也确实存盘了）
 * - 只有「改了之后行为应该变」这条因果链断了，而没有任何断言覆盖它
 *
 * ## 为什么用「消费点」而不是「字段引用数」
 *
 * 仅仅搜 `settings.rememberPosition` 出现几次是不够的 ——
 * 它可能只出现在 `copy(rememberPosition = v)` 这种写操作里。
 * 所以这里断言的是**具体的消费语句**：读取分支写了没有、
 * 传给下层写了没有。
 */
class SettingsWiringTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/lpdf/reader/$rel")
        assertTrue("源码文件不存在：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** 去掉注释行，避免「把代码注释掉测试还是绿的」这种假绿 */
    private fun code(s: String): String =
        s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    // ---------- rememberPosition ----------

    @Test
    fun `记忆阅读位置 必须同时控制写入与读取`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue(
            "onPageSettled 里没有按 rememberPosition 决定是否保存进度 —— " +
                "开关关掉后进度照样在写",
            s.contains("if (!rememberPosition) return")
        )
        assertTrue(
            "open() 里没有按 rememberPosition 决定是否读取上次进度 —— " +
                "开关关掉后打开仍会跳到上次那页",
            s.contains("if (rememberPosition)")
        )
    }

    // ---------- defaultHighlightColor ----------

    @Test
    fun `默认高亮色 必须被应用且不覆盖用户手选`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue(
            "applySettings 没有应用 defaultHighlightColor",
            s.contains("highlightColor = defaultHighlightColor")
        )
        assertTrue(
            "没有 highlightTouched 保护，改设置会盖掉用户刚选的颜色",
            s.contains("if (!highlightTouched)")
        )
        assertTrue(
            "setHighlightColor 没有标记 highlightTouched",
            s.contains("highlightTouched = true")
        )
    }

    // ---------- defaultInkWidth ----------

    @Test
    fun `墨迹粗细 必须真的传入绘制而不是写死`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "墨迹宽度仍然写死 2f —— 「墨迹粗细」设置形同虚设",
            !page.contains("width = 2f / w")
        )
        assertTrue(
            "墨迹宽度没有使用 inkWidth 参数",
            page.contains("width = inkWidth / w")
        )
        // 参数要从阅读页一路贯通到绘制层，任何一环断了都等于没接
        assertTrue("PageItem 没有 inkWidth 参数", page.contains("inkWidth: Float,"))
        assertTrue(
            "PageItem 调用处没有传 inkWidth",
            page.contains("inkWidth = vm.inkWidth")
        )
        assertTrue(
            "AnnotationLayer 调用处没有传 inkWidth",
            page.contains("inkWidth = inkWidth,")
        )
    }

    @Test
    fun `墨迹粗细 必须在 ViewModel 里可设置`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue("ViewModel 没有 setInkWidth", s.contains("fun setInkWidth("))
        assertTrue(
            "setInkWidth 没有标记 inkWidthTouched",
            s.contains("inkWidthTouched = true")
        )
    }

    @Test
    fun `墨迹粗细 必须有能在阅读页调的地方`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        // v1.6.1 自查补的：只把宽度传给绘制层是不够的 ——
        // 没有控件就调不动，等于这个设置只能去设置页改一次再回来
        assertTrue(
            "阅读页工具岛没有粗细控件接到 setInkWidth",
            page.contains("onWidth = vm::setInkWidth")
        )
        assertTrue(
            "ToolIsland 没有 onWidth 参数，控件无处可挂",
            page.contains("onWidth: (Float) -> Unit,")
        )
    }

    @Test
    fun `墨迹粗细滑杆的范围必须包含默认值`() {
        val s = code(src("ui/page/settings/SettingsPage.kt"))
        val row = s.substringAfter("\"墨迹粗细\"")
        assertTrue("设置页里找不到墨迹粗细这一行", row.isNotEmpty() && row != s)
        val rangeLine = row.lines().firstOrNull { it.contains("range = ") }
        assertTrue("墨迹粗细没有设置 range", rangeLine != null)
        // 默认值是 2f。range 若从 1f..8f 开始还算包含；但曾出现 1f 起、值 0.5f 被吸附的情况，
        // 这里直接守住「下限不高于 1f、上限不低于 8f」，与 ViewModel 的 0.5~24 对齐不冲突
        val lo = Regex("""range\s*=\s*([0-9.]+)f""").find(rangeLine!!)?.groupValues?.get(1)?.toFloat()
        val hi = Regex("""\.\.\s*([0-9.]+)f""").find(rangeLine)?.groupValues?.get(1)?.toFloat()
        assertTrue("解析不出 range：$rangeLine", lo != null && hi != null)
        assertTrue("墨迹粗细下限 $lo 高于默认值 2f", lo!! <= 2f)
        assertTrue("墨迹粗细上限 $hi 太小，调到 8 以上就没法再调粗", hi!! >= 8f)
    }

    // ---------- undoLimit ----------

    @Test
    fun `撤销步数 必须限制单页标注数量`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue(
            "addAnnotation 没有按 undoLimit 限制保留条数",
            s.contains("list.size - undoLimit")
        )
    }

    @Test
    fun `撤销步数 调小时必须立即裁剪而不是等下次绘制`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        // 自查补的：只在 addAnnotation 里裁剪，用户把 100 改成 20 后
        // 那一页旧的 80 条会一直留着，撤销键却只能撤到第 20 条 —— 中间那些既撤不掉也删不掉
        assertTrue(
            "applySettings 没有在上限变小时触发裁剪",
            s.contains("if (this.undoLimit < prevUndoLimit)")
        )
        assertTrue("没有 trimToLimit 实现", s.contains("private fun trimToLimit("))
    }

    // ---------- autoSaveAnnotations ----------

    @Test
    fun `自动保存标注 关掉后不能再自动落盘`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue(
            "scheduleSave 没有检查 autoSaveAnnotations —— 开关关不掉",
            s.contains("if (!autoSaveAnnotations) return")
        )
        assertTrue(
            "flush() 没有按 dirty 提前返回，会每次返回都白写一遍",
            s.contains("if (!_state.value.dirty) return")
        )
        assertTrue(
            "flush() 没有用 NonCancellable，页面销毁时写入会被取消导致标注丢失",
            s.contains("NonCancellable")
        )
    }

    @Test
    fun `关掉自动保存后 退出阅读页必须真的把标注写下去`() {
        val s = code(src("ui/page/reader/ReaderViewModel.kt"))
        // 自查补的：onCleared 里 flush() 若只是 viewModelScope.launch，
        // 作用域已经被 cancel，那段 NonCancellable 根本不会执行 —— 标注静默丢失。
        // 关掉自动保存时这是唯一的落盘路径，所以必须是同步写完。
        val flushFn = s.substringAfter("fun flush() {").substringBefore("fun flushAsync()")
        assertTrue("找不到 flush()", flushFn.isNotEmpty())
        assertTrue(
            "flush() 里没有 runBlocking，onCleared 时协程来不及跑就被取消了",
            flushFn.contains("runBlocking")
        )
        // 反过来，UI 上不能调阻塞版：返回键会卡一帧
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "阅读页仍在主线程调用阻塞版 flush()，返回键会卡顿",
            !page.contains("vm.flush()")
        )
        assertTrue(
            "阅读页没有改用 flushAsync()",
            page.contains("vm.flushAsync()")
        )
    }

    // ---------- accentFromContent ----------

    @Test
    fun `高亮色取自文档 必须接到主题上`() {
        val app = code(src("ui/LPdfApp.kt"))
        assertTrue(
            "LPdfApp 没有使用 settings.accentFromContent",
            app.contains("settings.accentFromContent")
        )
        assertTrue(
            "LPdfApp 没有把取到的颜色交给主题",
            app.contains("accentOverride = contentAccent")
        )

        val theme = code(src("ui/theme/Theme.kt"))
        assertTrue(
            "LPdfTheme 没有 accentOverride 参数，传进来的颜色无处可用",
            theme.contains("accentOverride: Color? = null")
        )
        assertTrue(
            "accentOverride 没有真正作用到 colors.accent",
            theme.contains("base.copy(accent = accentOverride)")
        )
    }

    @Test
    fun `取色不应为了装饰而渲染 PDF`() {
        val app = code(src("ui/LPdfApp.kt"))
        val fn = app.substringAfter("private fun rememberContentAccent")
        assertTrue("找不到 rememberContentAccent", fn != app)
        assertTrue(
            "取色前没有检查缩略图是否存在 —— 会为了一个颜色渲染整页 PDF，" +
                "首页首帧白白多等几百毫秒",
            fn.contains("container.thumbnails.has(key)")
        )
    }

    // ---------- saveMode ----------

    @Test
    fun `保存方式 必须影响导出路径`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "导出没有读 settings.saveMode —— 「默认保存方式」开关不起作用",
            page.contains("settings.saveMode == SaveMode.OVERWRITE")
        )
        assertTrue(
            "覆盖模式没有检查文件是否真的可写",
            page.contains("vm.canOverwriteSource()")
        )
        assertTrue(
            "不可写时没有回落另存为，用户会以为点了没反应",
            page.contains("改用另存为")
        )

        val vm = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue("ViewModel 没有 canOverwriteSource", vm.contains("fun canOverwriteSource("))
        assertTrue(
            "canOverwriteSource 对 content:// 应当一律返回 false（只读授权），" +
                "这里没看到对应分支",
            vm.contains("else -> false")
        )
    }

    @Test
    fun `覆盖原文件 设置页必须说明会自动回落`() {
        val s = code(src("ui/page/settings/SettingsPage.kt"))
        val row = s.substringAfter("\"默认保存方式\"")
        assertTrue("设置页找不到默认保存方式", row != s)
        // 系统媒体库的 PDF 是只读的。用户选了「覆盖」却发现每次都弹另存为，
        // 会以为功能坏了。文案必须提前说清楚。
        //
        // 注意断言范围：不能在这一整段里搜「另存为」——
        // 下面的 options 里本来就有 "另存为" 这个选项，那样永远为真（假绿）。
        // 所以只取 subtitle 起、到 options 之前的这两三行。
        val lines = row.lines()
        val startIdx = lines.indexOfFirst { it.contains("subtitle =") }
        assertTrue("这一行没有 subtitle", startIdx >= 0)
        val endIdx = lines.indexOfFirst { it.contains("options =") }
        val subtitle = lines
            .subList(startIdx, if (endIdx > startIdx) endIdx else startIdx + 1)
            .joinToString(" ")
        assertTrue(
            "「覆盖原文件」的说明里没有提到不可写时会自动另存为 —— " +
                "用户选了覆盖却总弹另存为，会以为功能坏了。实际文案：$subtitle",
            subtitle.contains("只读") || subtitle.contains("另存为")
        )
    }

    @Test
    fun `阅读页 ViewModel 必须按文档隔离`() {
        val parts = code(src("ui/page/reader/ReaderPageParts.kt"))
        // 自查补的：默认 key 取自调用点，所有阅读页会共用同一个实例 ——
        // 上一本书的 highlightTouched 会压制下一本的默认色，onCleared 也迟迟不触发，
        // 关掉「自动保存标注」时标注会真的丢
        assertTrue(
            "rememberReaderViewModel 没有按文档传 key，多个文档共用同一个 ViewModel",
            parts.contains("key = \"reader:")
        )
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "阅读页创建 ViewModel 时没有传文档标识",
            page.contains("rememberReaderViewModel(container, uri)")
        )
    }

    // ---------- wifiOnly ----------

    @Test
    fun `仅 Wi-Fi 下载 必须真的拦住流量下载`() {
        val s = code(src("data/network/LinkImporter.kt"))
        // 这是排查时新发现的一个假开关：设置页能点能存，但全项目没有任何地方读它
        assertTrue(
            "LinkImporter 没有读 settings.wifiOnly —— 「仅 Wi-Fi 下载」是个假开关",
            s.contains("settings.wifiOnly")
        )
        assertTrue(
            "没有真正判断当前网络类型",
            s.contains("fun isOnWifi(") && s.contains("TRANSPORT_WIFI")
        )
        // 必须在开始下载前就拦，而不是下到一半才停
        val checkIdx = s.indexOf("settings.wifiOnly")
        val downloadIdx = s.indexOf("client.newCall(request)")
        assertTrue(
            "Wi-Fi 判定必须在发起请求之前 —— 否则流量已经花出去了",
            checkIdx in 0 until downloadIdx
        )
    }

    // ---------- 未实现字段 ----------

    @Test
    fun `未实现的两个设置必须保持无入口，不能悄悄暴露给用户`() {
        val page = code(src("ui/page/settings/SettingsPage.kt"))
        // autoRotate / language 只是数据模型里的字段，功能没做。
        // 一旦有人给它们加了开关却没接线，就又多两个假开关。
        // 这条测试的作用是：加 UI 的那一刻必须同步改这里，逼着实现。
        assertTrue(
            "设置页出现了 autoRotate 入口，但自动旋转功能尚未实现 —— " +
                "要么接上实现，要么不要暴露开关",
            !page.contains("copy(autoRotate")
        )
        assertTrue(
            "设置页出现了 language 入口，但多语言尚未实现",
            !page.contains("copy(language")
        )
    }

    // ---------- 参数贯通 ----------

    @Test
    fun `阅读页必须把设置推给 ViewModel`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "阅读页没有调用 vm.applySettings",
            page.contains("vm.applySettings(")
        )
        // 顺序很重要：open() 里读 rememberPosition，晚了就用默认值走错分支
        val applyIdx = page.indexOf("vm.applySettings(")
        val bindIdx = page.indexOf("vm.bind(uri")
        assertTrue(
            "applySettings 必须出现在 bind 之前（open 依赖 rememberPosition）",
            applyIdx in 0 until bindIdx
        )
    }
}
