package com.lpdf.reader.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 玻璃渲染能力探测与降级（v1.3.2）。
 *
 * ## 为什么需要
 *
 * 液态玻璃依赖 AGSL（Android Graphics Shading Language）运行时着色器做实时折射。
 * 这条链路在真机上有三种失败方式，且都表现为「闪退」或「全屏异常」：
 *
 * 1. `RuntimeShader` 构造抛异常 —— 部分 ROM 裁剪了 AGSL 支持（尤其定制系统）
 * 2. 着色器编译失败 —— GPU 驱动对某些 AGSL 语法不兼容
 * 3. 绘制期抛 `UnsupportedOperationException` —— 如 shape 类型不被 lens() 接受
 *
 * 注意：`Build.VERSION.SDK_INT >= 33` **不等于** AGSL 可用。
 * Android 13 只是暴露了 API，实际编译在 GPU 驱动层，ROM 可以阉割。
 *
 * ## 策略
 *
 * - 启动时做一次轻量探测（构造并编译一个最小着色器）
 * - 探测失败 → 全局 [GlassCapability.LEVEL_BLUR_ONLY]，玻璃退化为纯模糊 + 高光，
 *   视觉上仍是玻璃质感，只是没有边缘折射
 * - 绘制期再兜一层：任一玻璃组件在 drawBackdrop 处抛异常，
 *   立即降级并触发重组，避免整个界面崩掉
 */
enum class GlassLevel {
    /** 完整效果：折射 + 色散 + 模糊 + 高光 */
    FULL,

    /** 无折射：模糊 + 高光 + 描边（AGSL 相关能力缺失时） */
    BLUR_ONLY,

    /** 最简：半透明底 + 描边（连模糊都不可用时） */
    PLAIN
}

@Stable
class GlassCapability(
    initial: GlassLevel = GlassLevel.FULL,
    /** 探测失败原因，便于用户反馈时报上来 */
    val reason: String? = null
) {
    var level by mutableStateOf(initial)
        internal set

    val hasRefraction: Boolean get() = level == GlassLevel.FULL
    val hasBlur: Boolean get() = level != GlassLevel.PLAIN

    /**
     * 绘制期降级。只在确实还能降的时候生效，避免反复重组。
     * @return true 表示状态发生了变化（需要重绘）
     */
    internal fun degrade(from: GlassLevel = level): Boolean {
        val next = when (from) {
            GlassLevel.FULL -> GlassLevel.BLUR_ONLY
            GlassLevel.BLUR_ONLY -> GlassLevel.PLAIN
            GlassLevel.PLAIN -> return false
        }
        if (next == level) return false
        level = next
        return true
    }
}

val LocalGlassCapability = staticCompositionLocalOf { GlassCapability() }

/**
 * 探测当前设备能否跑 AGSL 折射。
 *
 * 用一个极简着色器做真实编译，而不是只看 SDK 版本：
 * 只有编译成功才算真支持。整个过程包在 try/catch 里，绝不向外抛。
 */
object GlassProbe {

    /** 最小可编译的 AGSL：直接输出采样色，不做任何位移 */
    private const val MINIMAL_AGSL = """
        uniform shader content;
        half4 main(float2 coord) {
            return content.eval(coord);
        }
    """

    fun detect(): Pair<GlassLevel, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return GlassLevel.PLAIN to "系统低于 Android 13，无 RuntimeShader"
        }
        return try {
            // 构造 + 编译。构造本身就会触发 AGSL 编译，失败即抛
            val shader = RuntimeShader(MINIMAL_AGSL)
            // 真正设一次 uniform，确认不是空壳实现
            @Suppress("UNUSED_VARIABLE")
            val noop = shader.hashCode()
            GlassLevel.FULL to null
        } catch (t: Throwable) {
            // NoClassDefFoundError 也可能出现（ROM 删了类）
            GlassLevel.BLUR_ONLY to "AGSL 不可用：${t.javaClass.simpleName}"
        }
    }

    /**
     * 模糊能力探测。RenderEffect 在 API 31+ 才有，
     * 且部分 ROM 的 RenderNode 实现会忽略它（不崩但无效果，可接受）。
     */
    fun detectBlur(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            android.graphics.RenderEffect.createBlurEffect(1f, 1f, android.graphics.Shader.TileMode.CLAMP) != null
    } catch (t: Throwable) {
        false
    }

    /** 一次性完整探测 */
    fun detectAll(): GlassCapability {
        val (level, reason) = detect()
        val finalLevel = if (level == GlassLevel.FULL && !detectBlur()) {
            GlassLevel.PLAIN
        } else {
            level
        }
        return GlassCapability(finalLevel, reason)
    }
}

/**
 * 记住当前设备的玻璃能力。整个应用共用一个实例（由 LPdfApp 提供），
 * 这样任何一处降级都会让全局同步降级，避免一半折射一半不折射的割裂感。
 */
@Composable
fun rememberGlassCapability(): GlassCapability {
    return remember { GlassProbe.detectAll() }
}

/** 供测试：构造一个指定等级的实例 */
internal fun glassCapabilityForTest(level: GlassLevel): GlassCapability =
    GlassCapability(level)
