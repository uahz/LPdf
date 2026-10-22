package com.lpdf.reader

import com.lpdf.reader.data.pdf.CharBox
import com.lpdf.reader.data.pdf.TextLayer
import com.lpdf.reader.data.pdf.selectByDrag
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7.0 四项阅读体验增强的守卫（源码级）。
 *
 * 与 [TextSelectionTest] 的分工：
 * - 那个测的是**算法对不对**（坐标 → 字符范围）
 * - 这个测的是**接线有没有接上**（功能实现完了但是没人调，等于没做）
 *
 * 后者是这一类改动的常见失败模式：写了一个漂亮的反色滤镜，
 * 但渲染路径没传参，用户看到的就是「切了夜间没反应」。
 */
class ReaderExperienceWiringTest {

    private fun src(rel: String): String {
        val f = File("src/main/java/com/lpdf/reader/$rel")
        assertTrue("源码文件不存在：${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun code(s: String): String =
        s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    // ---------- 双指缩放锚点 ----------

    @Test
    fun `缩放必须以手指为中心而不是左上角`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue(
            "缩放没有使用 centroid（手指位置），会以左上角为原点放大",
            page.contains("detectTransformGestures { centroid")
        )
        assertTrue(
            "没有围绕焦点重算平移 —— 捏合时想看的地方会从手指下滑走",
            page.contains("offset = c - (c - offset) * ratio + pan")
        )
        assertTrue(
            "平移没有边界约束，可以把页面拖出屏幕且找不回来",
            page.contains("clampOffset(")
        )
    }

    // ---------- 夜间反色 ----------

    @Test
    fun `夜间模式必须是反色而不是叠一层黑`() {
        val engine = code(src("data/pdf/PdfiumEngine.kt"))
        assertTrue(
            "renderTiles 没有 invert 参数，反色无处可做",
            engine.contains("invert: Boolean = false")
        )
        assertTrue(
            "渲染后没有调用反色滤镜",
            engine.contains("if (invert)") && engine.contains("InvertFilter.apply(bmp)")
        )

        val filter = code(src("data/pdf/InvertFilter.kt"))
        assertTrue(
            "反色矩阵没有 -1 的 R 通道，等于没反色",
            filter.contains("-1f, 0f, 0f, 0f, 255f")
        )
    }

    @Test
    fun `切换夜间模式必须让旧缓存失效`() {
        val vm = code(src("ui/page/reader/ReaderViewModel.kt"))
        assertTrue("ViewModel 没有保存反色状态", vm.contains("private var pageInvert"))
        assertTrue(
            "ensureRendered 没有核对缓存的 inverted 标记 —— " +
                "切换夜间后当前页仍会显示白底旧图，看起来就是开关失灵",
            vm.contains("cached.inverted == invert")
        )
        // 开关本身要清缓存：位图是渲染时就反好色的，缓存里存的是结果不是参数
        val setter = vm.substringAfter("fun setPageInvert(").substringBefore("fun toggleChrome")
        assertTrue("找不到 setPageInvert", setter.isNotEmpty())
        assertTrue(
            "setPageInvert 没有清缓存，切了夜间当前页还是旧图",
            setter.contains("clearPageCache()")
        )
    }

    // ---------- 文本选择 ----------

    @Test
    fun `文本选择必须接到阅读页而且不能和编辑抢手势`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        assertTrue("阅读页没有使用 TextSelectionLayer", page.contains("TextSelectionLayer("))
        assertTrue(
            "选择层没有接 ViewModel 的选区计算",
            page.contains("vm.selectionBetween(")
        )
        assertTrue(
            "选择层没有接复制",
            page.contains("vm.copySelection(")
        )
        // 编辑模式下长按应该是画笔，不是选字
        assertTrue(
            "选择层没有按 tool 隔离，编辑时会和绘制抢手势",
            page.contains("if (tool == EditorTool.NONE)")
        )
    }

    @Test
    fun `文字层必须懒加载而不是每次翻页都解析`() {
        val page = code(src("ui/page/reader/PdfReaderPage.kt"))
        // 翻页时解析文字层会堵住渲染队列，快滑时明显卡顿
        val hasEager = Regex("""LaunchedEffect\(\s*page\s*\)\s*\{\s*vm\.textLayer""")
            .containsMatchIn(page)
        assertTrue("翻页时就去解析文字层，会拖慢滑动", !hasEager)
        assertTrue(
            "长按时没有取文字层",
            page.contains("prepare = { p -> vm.textLayer(p) }")
        )
    }

    @Test
    fun `换文档必须清掉文字层缓存`() {
        val vm = code(src("ui/page/reader/ReaderViewModel.kt"))
        val clear = vm.substringAfter("private fun clearPageCache()").substringBefore("private var uri")
        assertTrue(
            "清页缓存时没有清文字层 —— 换书后长按会选中上一本的文字",
            clear.contains("textLayerCache = null")
        )
    }

    // ---------- 缩略图预生成 ----------

    @Test
    fun `缩略图必须提前生成而不是滑到才渲染`() {
        val list = code(src("ui/page/list/PdfListPage.kt"))
        assertTrue(
            "列表页没有调用 prewarm",
            list.contains("vm.prewarm(")
        )
        val vm = code(src("ui/page/list/ListViewModel.kt"))
        assertTrue("ListViewModel 没有 prewarm", vm.contains("fun prewarm("))
        assertTrue(
            "ViewModel 释放时没有取消预热队列，会继续为已关闭的页面渲染",
            vm.contains("prewarmer.cancel()")
        )
    }

    @Test
    fun `预生成必须去重且与列表页同宽度`() {
        val pre = code(src("data/local/ThumbnailPrewarmer.kt"))
        assertTrue(
            "预生成器没有按 id 去重 —— 滚动时每帧都投递，会瞬间排爆队列",
            pre.contains("queued.add(")
        )
        // 两处宽度不一致会为同一本书存两份不同尺寸的缓存
        val list = code(src("ui/page/list/ListViewModel.kt"))
        val listW = Regex("""THUMB_WIDTH\s*=\s*(\d+)""").find(list)?.groupValues?.get(1)
        val preW = Regex("""THUMB_WIDTH\s*=\s*(\d+)""").find(pre)?.groupValues?.get(1)
        assertEquals(
            "预生成器与列表页的缩略图宽度不一致（$preW vs $listW），" +
                "会为同一本书存两份不同尺寸",
            listW, preW
        )
    }

    // ---------- 选区算法的回归用例（放在这里便于一起看） ----------

    @Test
    fun `跨行选区的起点行取到行尾而不是从起点字之后开始`() {
        // 这是本次开发中真被写错过的一处：用索引区间会得到 "DE\nFGHIJ"
        val chars = mutableListOf<CharBox>()
        "ABCDE".forEachIndexed { i, c ->
            chars += CharBox(c, 0, 0.10f + i * 0.05f, 0.20f, 0.15f + i * 0.05f, 0.24f)
        }
        "FGHIJ".forEachIndexed { i, c ->
            chars += CharBox(c, 1, 0.10f + i * 0.05f, 0.30f, 0.15f + i * 0.05f, 0.34f)
        }
        val sel = selectByDrag(TextLayer(0, chars, 2), 0.22f, 0.22f, 0.22f, 0.32f)
        assertEquals("CDE\nFGH", sel.text)
    }
}
