package com.lpdf.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 代码审查发现并修复的缺陷的回归测试。
 *
 * ## 为什么又是源码级断言
 *
 * 这批缺陷有一个共同点：**它们都不会让代码编译失败、也不会让已有测试变红**。
 *
 * - 发现页点「合并」传空 uri → 运行时才炸，且只炸在那一条路径上
 * - 底栏 `remember{}` 住回调 → 表现为「重复点 Tab 没反应」，没有异常
 * - 首页只用 lifecycle 作 key → 表现为「进度不刷新」，没有异常
 * - `pendingStartPage` 不复位 → 表现为「换书后跳到莫名其妙的页码」
 *
 * 要真正端到端验证，需要能操纵 MediaStore、真实文件与多 Activity 往返，
 * 在 Robolectric 里成本很高且容易假绿。相比之下，把「这几行必须长这样」
 * 钉住，反而更可靠 —— 至少当有人把这些行改回去时，测试一定变红。
 */
class RegressionGuardTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/lpdf/reader/$rel")
        assertTrue("源码文件不存在：${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ---------- 缺陷 1：发现页工具入口 ----------

    @Test
    fun `工具页必须处理未指定文件的进入方式`() {
        val s = src("ui/page/tools/PdfToolsPage.kt")
        assertTrue(
            "PdfToolsPage 没有处理 uri 为空的情况 —— " +
                "从「发现」页进来会直接 stageCopy(Uri.parse(\"\"))，" +
                "停在「暂存文件失败」，等于那五个入口全是死的",
            s.contains("val needPick = uri.isBlank()")
        )
        assertTrue(
            "工具页没有让用户先选文件的引导流程",
            s.contains("ToolPickFile(")
        )
        // 确认真的接了系统选择器，而不是只画个按钮
        assertTrue(
            "工具页的选文件没有接 ActivityResultContracts.OpenDocument",
            s.contains("ActivityResultContracts.OpenDocument()")
        )
    }

    // ---------- 缺陷 2：底栏回调被 remember 住 ----------

    @Test
    fun `底栏 items 不能被 remember 住`() {
        val s = src("ui/nav/RootTabHost.kt")
        val dock = s.substringAfter("private fun TabDock")
        // 用正则匹配「任何形式」的 remember：带全限定名的
        // androidx.compose.runtime.remember 也要抓到，
        // 否则有人加个 import 绕过去测试还是绿的
        val remembered = Regex("""val\s+items\s*=\s*(\S*\.)?remember\s*\{""")
        assertTrue(
            "TabDock 的 items 用了 remember{}。GlassTab 里存的是 onReselect 闭包，" +
                "remember 住之后宿主重组传入的新回调永远进不去，" +
                "表现为「重复点 Tab 没反应」",
            !remembered.containsMatchIn(dock)
        )
    }

    // ---------- 缺陷 3：首页不刷新 ----------

    @Test
    fun `首页必须订阅 ON_RESUME 而不是只拿 lifecycle 当 key`() {
        val s = src("ui/LPdfApp.kt")
        val homeTab = s.substringAfter("private fun HomeTab")
        assertTrue("找不到 HomeTab 函数", homeTab != s)
        val code = homeTab.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "HomeTab 没有订阅 Lifecycle.Event.ON_RESUME。只把 lifecycle 对象当 " +
                "LaunchedEffect 的 key 时，从阅读页返回不会重跑，" +
                "首页一直显示进去之前的旧进度",
            code.contains("Lifecycle.Event.ON_RESUME")
        )
        assertTrue(
            "HomeTab 的 LaunchedEffect 仍然以 lifecycle 为 key",
            !code.contains("LaunchedEffect(lifecycle)")
        )
    }

    // ---------- 缺陷 4：书签页只在首帧加载 ----------

    @Test
    fun `书签页每次可见都要重新读取`() {
        val s = src("ui/page/marks/BookmarksPage.kt")
        val code = s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "书签页没有订阅 ON_RESUME，在阅读页新加的书签切回来看不到",
            code.contains("Lifecycle.Event.ON_RESUME")
        )
        assertTrue(
            "书签页仍然用 refreshIfNeeded 这种「只加载一次」的语义",
            !code.contains("fun refreshIfNeeded(")
        )
    }

    // ---------- 缺陷 5：allKeys 会收进非 json 文件 ----------

    @Test
    fun `书签键只从 json 文件解析`() {
        val s = src("data/local/BookmarkStore.kt")
        val fn = s.substringAfter("suspend fun allKeys()")
        assertTrue("找不到 allKeys 函数", fn != s)
        val code = fn.substringBefore("suspend fun clearAll")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "allKeys 没有过滤 .json 后缀。removeSuffix 对 \"abc.tmp\" 不生效，" +
                "会把整个文件名当成 key 混进结果，进而查出一堆空书签",
            code.contains("endsWith(\".json\")")
        )
    }

    // ---------- 缺陷 6：pendingStartPage 跨文档残留 ----------

    @Test
    fun `pendingStartPage 用完必须复位`() {
        val s = src("ui/page/reader/ReaderViewModel.kt")
        // 先剥掉注释行：否则把复位语句注释掉之后，
        // 文本里仍留着 "pendingStartPage = -1"，测试依旧是绿的（假绿）
        val code = s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "pendingStartPage 在 open() 使用后没有复位。" +
                "同一个 ViewModel 换文档时（bind 另一本书）会沿用上次的起始页，" +
                "直接跳到那本书的第 N 页",
            code.contains("pendingStartPage = -1")
        )
    }

    @Test
    fun `判断同一文档必须比对 mtime`() {
        val s = src("ui/page/reader/ReaderViewModel.kt")
        val bind = s.substringAfter("fun bind(").substringBefore("private fun open(")
        assertTrue(
            "bind 的早退判定只比了 uri，没比 mtime。" +
                "文件被重新导出时 uri 不变、内容变了，" +
                "早退会让用户继续看上一版（缓存位图是旧的）",
            bind.contains("container.annotations.keyOf(uriString, lastModified)")
        )
    }

    // ---------- 缺陷 7：Tab 切换丢滚动位置 ----------

    @Test
    fun `Tab 内容必须有状态保存容器`() {
        val s = src("ui/nav/RootTabHost.kt")
        assertTrue(
            "RootTabHost 没有 SaveableStateHolder。AnimatedContent 切换时会把旧页面" +
                "移出组合，remember 的滚动位置随之销毁 —— " +
                "表现为「切回文档页，列表滚回顶部」，搜索词也一起丢",
            s.contains("rememberSaveableStateHolder()")
        )
        assertTrue(
            "没有按 Tab 分别保存 / 恢复状态",
            s.contains("SaveableStateProvider(")
        )
    }
}
