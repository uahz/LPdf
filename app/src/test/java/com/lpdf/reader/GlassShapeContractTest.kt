package com.lpdf.reader

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.dp
import com.lpdf.reader.ui.glass.Shapes2
import com.lpdf.reader.ui.glass.SquircleShape
import com.lpdf.reader.ui.glass.pillShape
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃形状类型约束测试（v1.3.1）。
 *
 * ## 为什么存在
 *
 * `SquircleShape` 最初只实现 `Shape` 接口并返回 `Outline.Generic`，
 * 而 Kyant0 液态玻璃库的 `lens()` 折射效果只接受 `CornerBasedShape`：
 *
 * ```
 * if (shape is CornerBasedShape) 取四角半径 → AGSL
 * else throw UnsupportedOperationException("Only CornerBasedShape is supported")
 * ```
 *
 * 结果：**每个玻璃组件在首帧渲染时崩溃**，用户看到的是「点开应用立刻消失」。
 *
 * ## 为什么用反射而不是 `is`
 *
 * 直接写 `shape is CornerBasedShape`，编译器会把它优化成恒真并警告，
 * 一旦将来有人把 `SquircleShape` 改回只实现 `Shape`，
 * 这种断言反而在编译期就过不去、或者被优化掉。用反射比较父类链，
 * 才能真正锁住「运行时类型」这个约束。
 */
class GlassShapeContractTest {

    private fun superclassesOf(obj: Any): Set<String> {
        val names = mutableSetOf<String>()
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            names += c.name
            c = c.superclass
        }
        return names
    }

    private fun assertCornerBased(label: String, obj: Any) {
        val chain = superclassesOf(obj)
        assertTrue(
            "$label 的运行时父类链里必须包含 CornerBasedShape，否则 lens() 折射会在首帧抛异常：$chain",
            chain.contains("androidx.compose.foundation.shape.CornerBasedShape")
        )
    }

    @Test
    fun `SquircleShape 的运行时类型继承 CornerBasedShape`() {
        assertCornerBased("SquircleShape(28f)", SquircleShape(28f))
        assertCornerBased("SquircleShape(12f)", SquircleShape(12f))
    }

    @Test
    fun `预制形状全部满足 lens 约束`() {
        assertCornerBased("Shapes2.XL", Shapes2.XL)
        assertCornerBased("Shapes2.L", Shapes2.L)
        assertCornerBased("Shapes2.M", Shapes2.M)
        assertCornerBased("Shapes2.S", Shapes2.S)
    }

    @Test
    fun `pillShape 满足 lens 约束`() {
        assertCornerBased("pillShape(40f)", pillShape(40f))
        assertCornerBased("pillShape(28f)", pillShape(28f))
        assertCornerBased("Shapes2.pill(44f)", Shapes2.pill(44f))
    }

    @Test
    fun `四角半径一致且为正`() {
        val s = SquircleShape(28f)
        val density = androidx.compose.ui.unit.Density(2f)
        val size = androidx.compose.ui.geometry.Size(100f, 100f)
        val px = s.topStart.toPx(size, density)
        assertTrue("28dp @2x 应为 56px，实际 $px", px > 55f && px < 57f)
        assertTrue(s.topStart == s.topEnd)
        assertTrue(s.topEnd == s.bottomEnd)
        assertTrue(s.bottomEnd == s.bottomStart)
    }

    @Test
    fun `copy 后运行时类型仍是 CornerBasedShape`() {
        val copied = SquircleShape(20f).copy(androidx.compose.foundation.shape.CornerSize(8.dp))
        assertCornerBased("copy() 结果", copied)
    }
}
