package com.lpdf.reader

import com.lpdf.reader.data.local.AppSettings
import com.lpdf.reader.ui.theme.DarkColors
import com.lpdf.reader.ui.theme.GlassSpec
import com.lpdf.reader.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃光学参数与配色的回归测试。
 *
 * ## 演进史（这些数值反复踩过坑，所以每一条都有理由）
 *
 * - **v1.3.5**：用户反馈「底栏太透明，看不清东西」→ 把霜化层从 6% 提到 55%。
 * - **v1.5.0**：用户反馈「液态玻璃效果太烂了，我要的是跟 Apple Liquid Glass 一样」
 *   → 55% 白雾正是"烂"的原因之一：它把折射糊成乳白，玻璃看起来像磨砂塑料板。
 *
 * 所以本文件的断言方向**反过来了**：不再锁"厚重"，而是锁"像 Apple 的玻璃"。
 * 具体锁四件事：
 *
 * 1. 雾化必须克制（靠 blur + colorControls 提亮，不靠盖白）
 * 2. 折射与色散开着（玻璃的标志性特征）
 * 3. 折射量级是"像素级"的，不能大到把采样点推出控件
 * 4. 设置项不能把规范默认值覆盖掉（v1.5.0 修掉的数据源漂移）
 */
class GlassTokensTest {

    // ------------------------------------------------------------------
    // 一、雾化要克制
    // ------------------------------------------------------------------

    @Test
    fun `浅色霜化层必须是轻雾而不是白板`() {
        // 上限 30%：超过这个量，底层折射的影像就被白雾盖住了，
        // 玻璃退化成「半透明白色卡片」。Apple 的 regular 变体只做
        // "blur + adjust luminosity"，不会大面积盖白。
        val frost = LightColors.glassFrost.alpha
        assertTrue(
            "浅色霜化层 alpha=$frost 过重，折射会被糊成乳白（v1.5.0 的教训）",
            frost <= 0.30f
        )
        // 下限 8%：完全不要也不行，PDF 正文会原样透上来，图标与文字互相干扰
        assertTrue(
            "浅色霜化层 alpha=$frost 过轻，底栏会透出 PDF 正文",
            frost >= 0.08f
        )
    }

    @Test
    fun `深色模式下霜化层是压暗而非提亮`() {
        // 深色主题里叠白雾，玻璃会变成发灰的脏块；正确做法是叠黑让底层沉下去
        val frost = DarkColors.glassFrost
        assertTrue(
            "深色霜化层应是黑色系，实际 r=${frost.red} g=${frost.green} b=${frost.blue}",
            frost.red < 0.3f && frost.green < 0.3f && frost.blue < 0.3f
        )
        assertTrue("深色霜化层要有存在感", frost.alpha >= 0.08f)
        assertTrue("深色霜化层不能黑成实心块", frost.alpha <= 0.30f)
    }

    @Test
    fun `本底着色比霜化层更淡`() {
        // 本底只负责极轻微的实体感，重量在霜化层和 blur 上。
        // 两者反过来时玻璃会「底色发脏、雾又不够」，观感最差。
        assertTrue(
            "浅色本底 alpha=${LightColors.glassBase.alpha} 不应重于霜化层",
            LightColors.glassBase.alpha < LightColors.glassFrost.alpha
        )
        assertTrue(
            "深色本底 alpha=${DarkColors.glassBase.alpha} 不应重于霜化层",
            DarkColors.glassBase.alpha < DarkColors.glassFrost.alpha
        )
    }

    // ------------------------------------------------------------------
    // 二、玻璃的核心特征必须在
    // ------------------------------------------------------------------

    @Test
    fun `色散默认开启`() {
        // 这是 v1.5.0 最重要的一条：旧版调用 lens(..., true, false) 把色散关了，
        // 玻璃边缘是纯灰边，与「半透明塑料」在视觉上无法区分。
        assertTrue(
            "色散必须默认开启 —— 它是玻璃与半透明塑料最直接的区别",
            GlassSpec().chromaticAberration
        )
    }

    @Test
    fun `折射带与折射位移在像素级`() {
        val spec = GlassSpec()
        // 折射位移超过控件厚度的量级时，边缘采样会采到控件外的背景，
        // 表现为"边缘一圈背景色"。12px 是经验上限（一般玻璃控件高 40~60dp）。
        assertTrue(
            "折射位移 ${spec.refractionAmountPx}px 过大，边缘会采到控件外",
            spec.refractionAmountPx in 4f..20f
        )
        // 折射带应略大于位移，否则弯曲被截断成硬边
        assertTrue(
            "折射带高度 ${spec.refractionHeightPx}px 应不小于位移",
            spec.refractionHeightPx >= spec.refractionAmountPx
        )
        assertTrue(
            "折射带高度 ${spec.refractionHeightPx}px 过大，整个控件都会变形",
            spec.refractionHeightPx <= 32f
        )
    }

    @Test
    fun `边缘高光走 HighlightStyle 而不是自绘白雾`() {
        val spec = GlassSpec()
        // 强度在合理区间：太低看不出亮边，太高变成描白边
        assertTrue(
            "高光强度 ${spec.highlightIntensity} 应在 0.2~0.9",
            spec.highlightIntensity in 0.2f..0.9f
        )
        // falloff 必须 > 0，否则高光会平铺成一层白纱
        assertTrue("高光衰减 ${spec.highlightFalloff} 必须为正", spec.highlightFalloff > 0f)
    }

    // ------------------------------------------------------------------
    // 三、模糊量级
    // ------------------------------------------------------------------

    @Test
    fun `模糊半径足以糊掉底层文字`() {
        // 低于 12dp 时 PDF 正文仍可辨认，玻璃像透明塑料
        assertTrue(
            "默认模糊半径 ${GlassSpec().blurDp}dp 偏小，底栏会透出正文",
            GlassSpec().blurDp >= 12f
        )
    }

    @Test
    fun `模糊半径不会大到失去玻璃质感`() {
        // 过大的模糊变成纯色板，完全看不到底层，也就不是玻璃了
        assertTrue(
            "默认模糊半径 ${GlassSpec().blurDp}dp 过大，玻璃会退化成实心块",
            GlassSpec().blurDp <= 32f
        )
    }

    // ------------------------------------------------------------------
    // 四、设置项不能覆盖规范默认值（v1.5.0 修掉的真 bug）
    // ------------------------------------------------------------------

    @Test
    fun `默认设置与规范默认值一致`() {
        // 旧版 AppSettings.glassBlurDp 默认 10f 把 GlassSpec 的 18f 完全覆盖，
        // 「模糊提到 18dp」这个修复在真机上从未生效 —— 而当时的测试断言的
        // 正是 GlassSpec() 的默认值，于是测试全绿、体验照旧。
        //
        // 这条断言把两个数据源钉在一起：只要改了一边没改另一边，测试就红。
        val spec = AppSettings().toGlassSpec()
        assertEquals(
            "AppSettings 默认值必须与 GlassSpec 规范值一致，否则规范值永远不会生效",
            GlassSpec().blurDp,
            spec.blurDp,
            0.001f
        )
        assertEquals("默认折射强度倍率应为 1.0", 1f, spec.intensity, 0.001f)
    }

    @Test
    fun `折射强度与模糊解耦`() {
        // 旧版 intensity = glassBlurDp / 10f，把「模糊」和「折射」两个
        // 毫不相干的物理量耦合：用户一调模糊，折射跟着变。
        val blurChanged = AppSettings(glassBlurDp = 6f).toGlassSpec()
        assertEquals(
            "改模糊不应影响折射倍率",
            1f,
            blurChanged.intensity,
            0.001f
        )
        val refrChanged = AppSettings(glassRefraction = 0.24f).toGlassSpec()
        assertEquals(
            "折射设置翻倍时倍率应翻倍",
            2f,
            refrChanged.intensity,
            0.01f
        )
        assertEquals(
            "改折射不应影响模糊",
            GlassSpec().blurDp,
            refrChanged.blurDp,
            0.001f
        )
    }

    @Test
    fun `折射调到 0 时倍率归零`() {
        // 设置页把折射拉到 0，应该真的只剩模糊，不能残留一点弯曲
        assertEquals(0f, AppSettings(glassRefraction = 0f).toGlassSpec().intensity, 0.0001f)
    }

    @Test
    fun `强度倍率不会失控`() {
        // 上限 2.5：再大折射位移会超出控件尺寸，边缘出现一圈背景色
        val max = AppSettings(glassRefraction = 5f).toGlassSpec()
        assertTrue("倍率上限应受控，实际 ${max.intensity}", max.intensity <= 2.5f)
        assertFalse(max.intensity.isNaN())
    }

    // ------------------------------------------------------------------
    // 五、边缘厚度感
    // ------------------------------------------------------------------

    @Test
    fun `边缘描边提供上亮下暗的厚度感`() {
        // 上缘亮、下缘暗，玻璃才有厚度。反了会像凹进去的坑。
        val top = LightColors.glassStroke
        val bottom = LightColors.glassStrokeBottom
        assertTrue("上缘描边应偏白", top.red > 0.8f && top.blue > 0.8f)
        assertTrue("下缘描边应偏暗", bottom.red < 0.3f)
        assertTrue("上缘亮线的对比度应远高于下缘暗线", top.alpha > bottom.alpha * 2f)
    }
}
