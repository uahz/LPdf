package com.lpdf.reader.ui.nav

import com.lpdf.reader.ui.glass.LIcon

/**
 * 五个顶层 Tab（v1.6.0）。
 *
 * ## 为什么用枚举 + 顺序而不是字符串路由
 *
 * 底栏的玻璃药丸位置由 `index * itemWidth` 算出来，枚举的声明顺序
 * 就是底栏的视觉顺序 —— 两者是同一份事实，不该各写一份。
 * 加 Tab 只改这里一处，底栏和指示器自动跟上。
 *
 * ## 为什么 Tab 之外还有独立路由
 *
 * 阅读页、文件工具页是**全屏沉浸**的，不该有底栏。它们仍然走 NavHost
 * 的普通 destination，只在 [TAB_HOST] 这一个宿主路由里渲染底栏。
 * 这样「沉浸页」和「带底栏页」在结构上就是分开的，不用靠 `if (isReader) hideBar`
 * 这种条件判断去猜。
 */
enum class RootTab(
    val route: String,
    val icon: LIcon,
    val label: String
) {
    HOME("tab_home", LIcon.Folder, "首页"),
    DOCS("tab_docs", LIcon.List, "文档"),
    BOOKMARKS("tab_marks", LIcon.Bookmark, "书签"),
    DISCOVER("tab_discover", LIcon.Search, "发现"),
    PROFILE("tab_profile", LIcon.Gear, "我的");

    companion object {
        fun fromRoute(route: String?): RootTab? =
            entries.firstOrNull { it.route == route }

        /** 底栏顺序（= 枚举声明顺序），单独暴露出来避免调用方再建一次 List */
        val bar: List<RootTab> = entries.toList()
    }
}
