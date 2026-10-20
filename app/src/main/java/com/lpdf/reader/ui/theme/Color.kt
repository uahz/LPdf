package com.lpdf.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 颜色令牌。严格对应《视觉规范 §3》。
 */
object LColors {
    // 文字
    val LightText = Color(0xFF1C1C1E)
    val LightSecondary = Color(0xFF6E6E73)
    val DarkText = Color(0xFFF5F5F7)
    val DarkSecondary = Color(0xFF8A8A8E)

    // 背景
    val PageBgLight = Color(0xFFF2F2F7)
    val PageBgDark = Color(0xFF000000)
    val PaperWhite = Color(0xFFFFFFFF)
    val PaperDark = Color(0xFF1A1A1C)

    // 语义
    val Danger = Color(0xFFFF453A)
    val DangerDark = Color(0xFFFF6961)
    val Success = Color(0xFF30D158)
    val AccentBlue = Color(0xFF0A84FF)

    // 标注色板（深色下不降亮度）
    val HighlightYellow = Color(0xFFFFD60A)
    val HighlightGreen = Color(0xFF30D158)
    val HighlightBlue = Color(0xFF0A84FF)
    val HighlightPink = Color(0xFFFF375F)
    val InkBlack = Color(0xFF1C1C1E)
    val InkRed = Color(0xFFFF453A)

    // 蒙版
    val ScrimLight = Color(0x59000000)   // 35%
    val ScrimDark = Color(0x73000000)    // 45%

    val AnnotationPalette = listOf(
        HighlightYellow, HighlightGreen, HighlightBlue, HighlightPink
    )
}
