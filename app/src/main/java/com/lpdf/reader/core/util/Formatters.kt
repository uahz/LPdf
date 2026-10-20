package com.lpdf.reader.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 展示用格式化工具。全部为纯函数，便于单元测试。
 */
object Formatters {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val monthDayFmt = SimpleDateFormat("M月d日", Locale.getDefault())
    private val fullDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 2.4 MB / 812 KB / 1.2 GB */
    fun fileSize(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = 1024.0
        return when {
            bytes < kb -> "$bytes B"
            bytes < kb * kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            bytes < kb * kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
            else -> String.format(Locale.US, "%.2f GB", bytes / (kb * kb * kb))
        }
    }

    /**
     * 智能时间：今天显示 `今天 14:32`，今年显示 `10月12日`，更早显示 `2025-08-04`。
     */
    fun smartTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return "—"
        val d = Date(millis)
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天 ${timeFmt.format(d)}"

        // 昨天
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "昨天 ${timeFmt.format(d)}"

        return if (today.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            monthDayFmt.format(d)
        } else {
            fullDateFmt.format(d)
        }
    }

    /** 去掉扩展名，用于大标题展示 */
    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    /** 中间省略：保留开头与扩展名，如 `很长的文件名….pdf` */
    fun middleEllipsis(name: String, max: Int = 28): String {
        if (name.length <= max) return name
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val keep = (max - suffix.length - 1).coerceAtLeast(4)
        return name.take(keep) + "…" + suffix
    }

    /** 页码范围解析：`1-3,7` -> [1,2,3,7]；越界由调用方过滤 */
    fun parsePageRanges(input: String, pageCount: Int): List<Int> {
        if (input.isBlank()) return emptyList()
        val result = LinkedHashSet<Int>()
        input.split(',', '，', ' ').filter { it.isNotBlank() }.forEach { part ->
            val p = part.trim()
            when {
                p.contains('-') || p.contains('~') -> {
                    val sep = if (p.contains('-')) '-' else '~'
                    val (a, b) = p.split(sep, limit = 2)
                    val start = a.trim().toIntOrNull() ?: return@forEach
                    val end = b.trim().toIntOrNull() ?: return@forEach
                    if (start <= end) {
                        for (i in start..minOf(end, pageCount)) if (i >= 1) result += i
                    } else {
                        for (i in end..minOf(start, pageCount)) if (i >= 1) result += i
                    }
                }

                else -> p.toIntOrNull()?.let { if (it in 1..pageCount) result += it }
            }
        }
        return result.toList().sorted()
    }

    /** 页码输入校验 */
    fun isValidPage(input: String, pageCount: Int): Int? {
        val v = input.trim().toIntOrNull() ?: return null
        return if (v in 1..pageCount) v else null
    }

    /** 归一化缩略图/采样主色：保证不会过亮或过暗，用于玻璃高亮 */
    fun softenColor(argb: Long): Long {
        val a = 0xFFL
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        fun boost(c: Long): Long = (c * 0.75 + 255 * 0.25).toLong().coerceIn(0, 255)
        return (a shl 24) or (boost(r) shl 16) or (boost(g) shl 8) or boost(b)
    }

    /** 亮度（0~1） */
    fun luminance(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** 浮点近似相等，供测试使用 */
    fun approxEquals(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
}
