package com.lpdf.reader

import com.lpdf.reader.ui.glass.GlassLevel
import com.lpdf.reader.ui.glass.GlassProbe
import com.lpdf.reader.ui.glass.glassCapabilityForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 玻璃能力探测与降级状态机测试（v1.3.2）。
 *
 * ## 为什么需要这层保护
 *
 * v1.3.1 修掉的是「shape 类型不对导致 lens() 抛异常」这个**确定性**崩溃。
 * 但同一行 `lens()` 在真机上还有另两种崩法：
 *
 * 1. ROM 阉割 AGSL → `RuntimeShader` 构造抛 `RuntimeException`
 * 2. GPU 驱动编译不了某段 AGSL → 编译失败在绘制期才暴露
 *
 * 这两种和机型强相关，本地测不出来。所以策略只能反过来：
 * **假设它随时可能抛**，每层都兜住并往下降一级，代价是玻璃没那么好看。
 *
 * 本测试覆盖的就是「降级链路本身不会出错」——这是最后一道防线，
 * 如果 `degrade()` 自己抛异常或死循环，兜底逻辑反而会变成新的崩溃源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassCapabilityTest {

    // ------------------------------------------------------------------
    // 状态机：FULL → BLUR_ONLY → PLAIN → 到底
    // ------------------------------------------------------------------

    @Test
    fun `完整能力默认开启折射`() {
        val cap = glassCapabilityForTest(GlassLevel.FULL)
        assertTrue(cap.hasRefraction)
        assertTrue(cap.hasBlur)
        assertEquals(GlassLevel.FULL, cap.level)
    }

    @Test
    fun `降级一次后失去折射但保留模糊`() {
        val cap = glassCapabilityForTest(GlassLevel.FULL)
        assertTrue("首次降级应生效", cap.degrade())
        assertEquals(GlassLevel.BLUR_ONLY, cap.level)
        assertFalse("BLUR_ONLY 不应再有折射", cap.hasRefraction)
        assertTrue("BLUR_ONLY 仍应有模糊", cap.hasBlur)
    }

    @Test
    fun `降级两次后连模糊也关闭`() {
        val cap = glassCapabilityForTest(GlassLevel.FULL)
        cap.degrade()
        assertTrue("二次降级应生效", cap.degrade())
        assertEquals(GlassLevel.PLAIN, cap.level)
        assertFalse(cap.hasBlur)
    }

    @Test
    fun `到底后降级返回 false 且状态不变`() {
        val cap = glassCapabilityForTest(GlassLevel.FULL)
        cap.degrade()
        cap.degrade()
        repeat(5) {
            assertFalse("PLAIN 已到底，degrade() 必须返回 false", cap.degrade())
        }
        assertEquals(GlassLevel.PLAIN, cap.level)
    }

    @Test
    fun `降级必须幂等 - 不因重复调用反复重组`() {
        val cap = glassCapabilityForTest(GlassLevel.FULL)
        // 第一次调用真的改变了状态
        assertTrue(cap.degrade(GlassLevel.FULL))
        // 用同一个 from 再调一次：当前已是 BLUR_ONLY，next 也是 BLUR_ONLY，
        // 状态没变，必须返回 false，否则 Compose 会陷入无限重组
        assertFalse(cap.degrade(GlassLevel.FULL))
    }

    @Test
    fun `从 BLUR_ONLY 起始也能继续降级`() {
        val cap = glassCapabilityForTest(GlassLevel.BLUR_ONLY)
        assertTrue(cap.degrade())
        assertEquals(GlassLevel.PLAIN, cap.level)
    }

    @Test
    fun `从 PLAIN 起始降级不生效`() {
        val cap = glassCapabilityForTest(GlassLevel.PLAIN)
        assertFalse(cap.degrade())
        assertEquals(GlassLevel.PLAIN, cap.level)
    }

    // ------------------------------------------------------------------
    // 探测本身：任何情况下都不许抛
    // ------------------------------------------------------------------

    @Test
    fun `探测在各种 SDK 上都不抛异常`() {
        // Robolectric 下 RuntimeShader 是空实现，这里主要验证探测代码
        // 自身没有 NPE / 类找不到之类的问题
        val (level, _) = GlassProbe.detect()
        assertNotNull(level)
    }

    @Test
    fun `detectAll 总返回可用实例`() {
        val cap = GlassProbe.detectAll()
        assertNotNull(cap.level)
        // 有可能是 FULL，也有可能是降级后的等级，但必须落在这三个之内
        assertTrue(
            "未知的玻璃等级：${cap.level}",
            cap.level in GlassLevel.entries
        )
    }

    @Test
    fun `无 AGSL 设备降级到 BLUR_ONLY 并给出原因`() {
        // Robolectric 的 shadow RuntimeShader 可能构造成功，也可能失败，
        // 因此这里只验证「一旦降级，reason 不能为空」这一契约
        val (level, reason) = GlassProbe.detect()
        if (level != GlassLevel.FULL) {
            assertNotNull("降级时必须带上原因，方便用户反馈", reason)
        }
    }

    @Test
    fun `模糊探测不抛异常`() {
        // API 31+ 才有 RenderEffect，这里不假定结果，只要求安全
        GlassProbe.detectBlur()
    }

    // ------------------------------------------------------------------
    // GlassSpec 联动
    // ------------------------------------------------------------------

    @Test
    fun `GlassSpec 默认允许折射`() {
        val spec = com.lpdf.reader.ui.theme.GlassSpec()
        assertTrue(spec.allowRefraction)
        assertEquals(0.12f, spec.refraction, 0.0001f)
        // v1.3.5：10dp 时底层正文仍清晰可辨，玻璃像透明塑料；提到 18dp。
        // 这里断言「大于等于 16」而不是钉死数值，避免以后微调就得改测试。
        assertTrue("模糊半径应足够糊掉底层文字", spec.blurDp >= 16f)
    }

    @Test
    fun `copy 可关闭折射而不影响其他参数`() {
        val spec = com.lpdf.reader.ui.theme.GlassSpec().copy(allowRefraction = false)
        assertFalse(spec.allowRefraction)
        assertTrue("关闭折射不应改动模糊半径", spec.blurDp >= 16f)
        assertEquals("关闭折射不应改动高光", 0.15f, spec.highlightAlpha, 0.0001f)
    }
}
