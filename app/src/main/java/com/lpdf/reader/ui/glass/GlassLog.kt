package com.lpdf.reader.ui.glass

import android.util.Log

/**
 * 玻璃链路的日志出口。
 *
 * 不用 [android.util.Log#w] 直接刷屏：玻璃的绘制每秒几十次，
 * 如果降级失败反复触发会瞬间淹没 logcat。这里做一次性去重 ——
 * 同一 tag 只打第一条，附带上报给 [com.lpdf.reader.core.util.CrashReporter] 所需的信息。
 */
internal object GlassLog {

    private const val TAG = "LPdfGlass"

    /** 已记录过的 tag，避免绘制循环里刷爆 */
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 降级轨迹，用户反馈时可随崩溃报告一起带出 */
    private val trail = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun warn(where: String, error: Throwable) {
        val key = "$where:${error.javaClass.name}"
        if (!seen.add(key)) return
        val msg = "$where 失败 → ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
        trail.add(msg)
        runCatching { Log.w(TAG, msg, error) }
    }

    fun info(msg: String) {
        if (!seen.add("info:$msg")) return
        trail.add(msg)
        runCatching { Log.i(TAG, msg) }
    }

    /** 导出降级轨迹（诊断用） */
    fun trail(): List<String> = synchronized(trail) { trail.toList() }

    /** 诊断文本：写进崩溃报告，方便定位是哪台机器上哪一步退化了 */
    fun diagnostic(): String = buildString {
        appendLine("玻璃能力：${GlassProbe.detectAll().level}")
        val t = trail()
        if (t.isEmpty()) {
            append("无降级记录")
        } else {
            t.forEach { appendLine("· $it") }
        }
    }
}
