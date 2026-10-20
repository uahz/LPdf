package com.lpdf.reader.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字体令牌（视觉规范 §4）。系统无衬线，数字等宽。
 */
object LType {
    private val family = FontFamily.SansSerif

    val TitleL = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"
    )
    val TitleM = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp
    )
    val Body = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 22.sp
    )
    val BodyStrong = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp
    )
    val Caption = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    )
    val Micro: TextStyle = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum"
    )

    fun number(size: TextUnit = 11.sp, weight: FontWeight = FontWeight.Medium) = TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size,
        fontFeatureSettings = "tnum"
    )

    val Material = Typography(
        titleLarge = TitleL, titleMedium = TitleM, bodyLarge = Body,
        bodyMedium = Body, bodySmall = Caption, labelSmall = Micro
    )
}

/**
 * 形状令牌（视觉规范 §2.6）。全部为连续曲率圆角的近似实现。
 */
object LShape {
    /** XL：Dock / 悬浮框 / 卡片 / 工具岛 / 设置分组 */
    val XL = RoundedCornerShape(28.dp)
    /** L：按钮、页码胶囊、分段控件指示块 */
    val L = RoundedCornerShape(20.dp)
    /** M：列表缩略图、小图标按钮 */
    val M = RoundedCornerShape(14.dp)
    /** S：键帽 */
    val S = RoundedCornerShape(12.dp)

    fun pill(height: androidx.compose.ui.unit.Dp) = RoundedCornerShape(CornerSize(height / 2))
}

/**
 * 间距令牌（视觉规范 §5）。
 */
object LSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val section = 28.dp
    val huge = 48.dp

    val pageHorizontal = 20.dp
    val listItemGap = 10.dp
    val listItemPaddingH = 16.dp
    val listItemPaddingV = 14.dp
    val settingsRowHeight = 56.dp
    val dockHeight = 56.dp
    val dockBottom = 24.dp
}
