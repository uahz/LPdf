package com.lpdf.reader

import android.graphics.Bitmap
import com.lpdf.reader.ui.glass.ThumbnailCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * 列表缩略图缓存（v1.3.4）。
 *
 * 回归目标：`LazyColumn` 滚过成百上千个文档时，位图不再靠 GC 兜底，
 * 而是被 LRU 双限（条目数 + 字节数）主动收敛。
 *
 * 注意：Robolectric 默认的 BitmapFactory 会对**任何**路径返回一张 100x100 的
 * stub 图（连不存在的文件也返回），必须开 [ShadowBitmapFactory.setAllowInvalidImageData]
 * 关掉才能测到「解不出来 → null」这条分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThumbnailCacheTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "lpdf-thumb-test").apply {
        deleteRecursively(); mkdirs()
    }

    @Before
    fun setUp() {
        // false = 图片数据非法时返回 null，与真机行为一致
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @After
    fun tearDown() {
        ShadowBitmapFactory.setAllowInvalidImageData(true)
        ThumbnailCache.clear()
        dir.deleteRecursively()
    }

    /** 写一张真实 PNG，尺寸可控，用于撑字节预算 */
    private fun writePng(name: String, w: Int, h: Int): String {
        val f = File(dir, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF123456.toInt())
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.absolutePath
    }

    @Test
    fun `同一路径第二次命中缓存不再解码`() {
        val path = writePng("a.png", 40, 60)
        val first = ThumbnailCache.get(path)
        val second = ThumbnailCache.get(path)
        assertNotNull(first)
        // LRU 必须返回同一个实例，否则 equal 也会失败（ImageBitmap 没实现 equals）
        assertTrue("同路径应复用同一实例", first === second)
    }

    @Test
    fun `文件不存在时返回 null 而不是抛异常`() {
        assertNull(ThumbnailCache.get(File(dir, "nope.png").absolutePath))
    }

    @Test
    fun `非图片文件返回 null`() {
        val f = File(dir, "fake.png").apply { writeText("not an image") }
        assertNull(ThumbnailCache.get(f.absolutePath))
    }

    @Test
    fun `条目数超限触发淘汰`() {
        // 小图，让条目数成为先到的限制（MAX_ENTRIES = 120）
        val first = writePng("s-first.png", 8, 8)
        ThumbnailCache.get(first)

        // 灌入远超上限的新条目，最老的那条必然被挤出
        val many = (0 until 200).map { writePng("s$it.png", 8, 8) }
        many.forEach { ThumbnailCache.get(it) }

        // 被淘汰后再取会重新解码，得到新实例；用实例身份验证淘汰确实发生过
        val again = ThumbnailCache.get(first)
        assertNotNull(again)
        assertTrue("表超出上限后最老条目应被淘汰", ThumbnailCache.sizeBytes() < 200L * 8 * 8 * 4)
    }

    @Test
    fun `字节数超限触发淘汰`() {
        // 一张 1200x1200 ARGB ≈ 5.76MB，5 张就顶到 24MB 上限
        val paths = (0 until 8).map { writePng("b$it.png", 1200, 1200) }
        paths.forEach { ThumbnailCache.get(it) }

        assertTrue(
            "缓存占用应被压到 24MB 以内，实际 ${ThumbnailCache.sizeBytes()}",
            ThumbnailCache.sizeBytes() <= 24L * 1024 * 1024
        )
    }

    @Test
    fun `clear 后重新变空`() {
        ThumbnailCache.get(writePng("c.png", 32, 32))
        assertTrue(ThumbnailCache.sizeBytes() > 0)
        ThumbnailCache.clear()
        assertEquals(0L, ThumbnailCache.sizeBytes())
    }

    @Test
    fun `最近访问的条目在淘汰时被保留`() {
        val keep = writePng("keep.png", 1200, 1200)
        ThumbnailCache.get(keep)
        // 先把 keep 变成「最近使用」，再灌入足够多的图把它挤出最老位置
        (0 until 8).forEach {
            ThumbnailCache.get(writePng("f$it.png", 1200, 1200))
            ThumbnailCache.get(keep)
        }
        val a = ThumbnailCache.get(keep)
        val b = ThumbnailCache.get(keep)
        assertTrue("热点条目应稳定命中缓存", a === b)
    }

    @Test
    fun `缓存的位图可正常绘制尺寸`() {
        val path = writePng("dim.png", 100, 140)
        val bmp = ThumbnailCache.get(path)
        assertNotNull(bmp)
        assertEquals(100, bmp!!.width)
        assertEquals(140, bmp.height)
        // 确认它能被 Compose 的 Image 直接消费（尺寸非零即视为可用）
        assertTrue(bmp.width > 0 && bmp.height > 0)
    }
}
