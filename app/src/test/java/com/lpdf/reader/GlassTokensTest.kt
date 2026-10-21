package com.lpdf.reader

import com.lpdf.reader.ui.theme.DarkColors
import com.lpdf.reader.ui.theme.GlassSpec
import com.lpdf.reader.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃光学参数与配色的回归测试（v1.3.5）。
 *
 * 用户反馈：「底栏太透明了，看不清东西」。根因是玻璃只有一层半透明白，
 * 底下的 PDF 文字原样透上来，图标与正文糊成一片。
 *
 * 这些断言把「玻璃必须有实体感」钉死，防止以后为了「更通透」把参数改回去。
 */
class GlassTokensTest {

    @Test
    fun `浅色玻璃霜化层必须明显高于本底`() {
        // 霜化层是「磨砂」的来源，本底只是轻微着色。
        // 如果霜化层比本底还淡，玻璃就又变回透明塑料了。
        val frost = LightColors.glassFrost.alpha
        val base = LightColors.glassBase.alpha
        assertTrue(
            "霜化层 alpha=$frost 应显著高于本底 alpha=$base",
            frost > base + 0.25f
        )
    }

    @Test
    fun `浅色霜化层足够厚重`() {
        // 低于 40% 时，白底黑字的 PDF 透过玻璃仍然可辨，
        // 图标与文字会互相干扰。45% 是可读性的下限。
        assertTrue(
            "浅色霜化层 alpha=${LightColors.glassFrost.alpha} 过低，底栏会看不清",
            LightColors.glassFrost.alpha >= 0.45f
        )
    }

    @Test
    fun `深色模式下霜化层是压暗而非提亮`() {
        // 深色主题里如果还叠白雾，玻璃会变成发灰的脏块。
        // 正确的做法是叠黑，让底层内容沉下去。
        val frost = DarkColors.glassFrost
        assertTrue(
            "深色霜化层应是黑色系，实际 r=${frost.red} g=${frost.green} b=${frost.blue}",
            frost.red < 0.3f && frost.green < 0.3f && frost.blue < 0.3f
        )
        assertTrue("深色霜化层应有一定不透明度", frost.alpha >= 0.25f)
    }

    @Test
    fun `边缘描边提供上亮下暗的厚度感`() {
        // 上缘亮、下缘暗，玻璃才有厚度。两者反了会像凹进去的坑。
        val top = LightColors.glassStroke
        val bottom = LightColors.glassStrokeBottom
        assertTrue("上缘描边应偏白", top.red > 0.8f && top.blue > 0.8f)
        assertTrue("下缘描边应偏暗", bottom.red < 0.3f)
        assertTrue(
            "上缘亮线的对比度应远高于下缘暗线",
            top.alpha > bottom.alpha * 2f
        )
    }

    @Test
    fun `模糊半径足以糊掉底层文字`() {
        // 10dp 时 PDF 正文仍然清晰可辨，玻璃像透明塑料。
        // 参考图里底层内容已成为色块，对应 16dp 以上。
        assertTrue(
            "默认模糊半径 ${GlassSpec().blurDp}dp 偏小，底栏会透出正文",
            GlassSpec().blurDp >= 16f
        )
    }

    @Test
    fun `模糊半径不会大到失去玻璃质感`() {
        // 过大的模糊会变成纯色板，完全看不到底层内容，也就不是玻璃了。
        assertTrue(
            "默认模糊半径 ${GlassSpec().blurDp}dp 过大，玻璃会退化成实心块",
            GlassSpec().blurDp <= 32f
        )
    }

    @Test
    fun `视觉规范锁定值保持不变`() {
        // 圆角 / 折射 / 色散 / 高光 / 蒙版是 PRD 锁定值，改动需同步文档。
        val spec = GlassSpec()
        assertEquals(0.12f, spec.refraction, 0.0001f)
        assertEquals(0.04f, spec.dispersion, 0.0001f)
        assertEquals(0.15f, spec.highlightAlpha, 0.0001f)
        assertEquals(0.35f, spec.maskAlpha, 0.0001f)
    }
}
