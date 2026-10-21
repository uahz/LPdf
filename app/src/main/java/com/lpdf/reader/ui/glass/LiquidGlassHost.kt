package com.lpdf.reader.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * 采样根容器（对应《开发规范 §9 层级与采样实现》）。
 *
 * ⚠️ 当前**未被任何页面使用** —— 页面统一走 [LiquidGlassScope]。
 * 保留是因为它对「覆盖层必须撑满」这一约束实现得更完整：
 * 若将来把 [LiquidGlassScope] 与之合并，这里的写法是正确基线。
 * 若长期不用，迁移时一并删除，避免它误导成「overlay 约束已经处理过了」。
 *
 * L1 背景 + L2 内容 → 录制到 GraphicsLayer → 作为所有玻璃组件的 Backdrop 采样源。
 * L3 蒙版、L4/L5 玻璃浮层由 [overlay] 提供，不参与录制。
 *
 * 关键：采样源必须是 Compose 的 GraphicsLayer（GPU 侧直接引用），
 * 禁止 PixelCopy 全屏截屏。
 */
@Composable
fun LiquidGlassHost(
    modifier: Modifier = Modifier,
    /** L1 + L2 内容层 */
    content: @Composable () -> Unit,
    /** L3 以上覆盖层（蒙版 / 玻璃 / 提示） */
    overlay: @Composable (LiquidGlassState) -> Unit
) {
    val layer = rememberGraphicsLayer()
    val state = rememberLiquidGlassState(layer)

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) {
            content()
        }
        // 覆盖层必须显式撑满。
        //
        // Box 会以「宽松约束」测量子元素：子元素想要多大就给多大。
        // 若这里不加 fillMaxSize，overlay 根节点只会占内容高度并从**顶部**排布，
        // 表现为「底部 Dock 飞到顶部」（v1.3.2 真机截图）。
        // 页面里所有 overlay 都依赖这个满屏坐标系来做 alignment / padding。
        CompositionLocalProvider(LocalLiquidGlassScope provides state) {
            Box(Modifier.fillMaxSize()) {
                overlay(state)
            }
        }
    }
}

/**
 * 顶部高光带的采样偏移。
 * 供拖拽中的玻璃使用：光随手的移动方向流动。
 */
data class LightFollow(val dx: Float, val dy: Float, val strength: Float = 1f)

/** 记住拖拽光源（单位：像素/秒，已归一化） */
@Composable
fun rememberLightFollow(): LightFollow {
    return remember { LightFollow(0f, 0f, 0f) }
}
