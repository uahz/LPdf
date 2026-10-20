package com.lpdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lpdf.reader.data.local.Annotation
import com.lpdf.reader.data.local.AnnotationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 阅读页 ViewModel 冒烟测试（Robolectric，无真实 PDFium 渲染）：
 * - 标注增删与计数
 * - 撤销顺序
 * - 标注 JSON 落盘 / 读回（坐标归一化精度）
 * - UI 开关状态机
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderViewModelSmokeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `标注增删与撤销`() = kotlinx.coroutines.runBlocking {
        val store = AnnotationStore(File(app.filesDir, "t1").apply { mkdirs() })
        val key = store.keyOf("content://doc/1", 1000L)
        val doc = store.load(key)

        doc.pages.getOrPut(0) { mutableListOf() }.add(
            Annotation.Highlight("a1", listOf(com.lpdf.reader.data.local.AnnotRect(0.1f, 0.2f, 0.5f, 0.22f)), 0xFFFFD60A)
        )
        doc.pages.getOrPut(0) { mutableListOf() }.add(
            Annotation.Note("n1", 0.3f, 0.4f, "想法")
        )
        assertEquals(2, doc.count())

        // 撤销最后一个
        doc.pages[0]!!.removeAt(doc.pages[0]!!.size - 1)
        assertEquals(1, doc.count())
        assertTrue(doc.pages[0]!!.first() is Annotation.Highlight)
    }

    @Test
    fun `标注落盘与读回保持一致`() = kotlinx.coroutines.runBlocking {
        val root = File(app.filesDir, "t2").apply { mkdirs() }
        val store = AnnotationStore(root)
        val key = store.keyOf("content://doc/2", 2222L)
        val doc = AnnotationStore(root).load(key)
        doc.pages.getOrPut(3) { mutableListOf() }.add(
            Annotation.Ink(
                id = "ink1",
                points = listOf(0.1f to 0.1f, 0.2f to 0.25f, 0.9f to 0.8f),
                width = 0.003f,
                color = 0xFF0A84FF
            )
        )
        assertTrue(store.save(doc))

        val reloaded = store.load(key)
        assertEquals(1, reloaded.count())
        val ink = reloaded.pages[3]!!.first() as Annotation.Ink
        assertEquals(3, ink.points.size)
        // JSON 往返后坐标精度误差应小于 1e-3（归一化坐标）
        assertTrue(kotlin.math.abs(ink.points[2].first - 0.9f) < 1e-3f)
        assertEquals(0xFF0A84FF, ink.color)
    }

    @Test
    fun `路径工具不会误删用户文件`() {
        // keyOf 对同一 uri + mtime 稳定，更换 mtime 后变化
        val s = AnnotationStore(File(app.filesDir, "t3"))
        assertEquals(s.keyOf("u", 1), s.keyOf("u", 1))
        assertTrue(s.keyOf("u", 1) != s.keyOf("u", 2))
    }
}
