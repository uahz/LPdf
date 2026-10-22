package com.lpdf.reader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lpdf.reader.ui.theme.GlassSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lpdf_prefs")

/** 阅读模式 */
enum class ReadingMode { CONTINUOUS, SINGLE_PAGE }

/** 主题模式 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 动效等级 */
enum class MotionLevel { FULL, REDUCED, OFF }

/** 默认保存方式 */
enum class SaveMode { SAVE_AS, OVERWRITE }

/** 排序 */
enum class SortOrder { MODIFIED_DESC, NAME_ASC, SIZE_DESC, RECENT }

/** 筛选 */
enum class ListFilter { ALL, RECENT, ANNOTATED, ENCRYPTED, SAF }

/** 所有用户可配置项 */
data class AppSettings(
    // 阅读
    val readingMode: ReadingMode = ReadingMode.CONTINUOUS,
    val zoomLimit: Int = 5,
    /**
     * 自动旋转（**当前未实现**，设置页也没有入口）。
     *
     * v1.6.1 核对时确认它不是「假开关」—— 界面上根本没有这一项，
     * 只是数据模型里留了字段。保留是为了不破坏已写盘的数据结构，
     * 等真做横屏阅读时直接接这条线，不必再做一次迁移。
     */
    val autoRotate: Boolean = true,
    val keepScreenOn: Boolean = false,
    val rememberPosition: Boolean = true,
    val autoHideToolbar: Boolean = true,
    val hapticFeedback: Boolean = true,
    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val glassBlurDp: Float = 22f,
    val glassRefraction: Float = 0.12f,
    val motionLevel: MotionLevel = MotionLevel.FULL,
    val accentFromContent: Boolean = true,
    val compactList: Boolean = false,
    // 编辑
    val saveMode: SaveMode = SaveMode.SAVE_AS,
    val autoSaveAnnotations: Boolean = true,
    val defaultHighlightColor: Long = 0xFFFFD60A,
    val defaultInkWidth: Float = 2f,
    val undoLimit: Int = 50,
    // 隐私与网络
    val offlineMode: Boolean = false,
    val wifiOnly: Boolean = true,
    val allowLinkPreview: Boolean = true,
    // 通用
    /**
     * 界面语言（**当前未实现**，设置页没有入口）。
     *
     * 现在只有中文一套文案，硬接这个字段只会给出「切了没变」的错觉。
     * 保留字段的理由同 [autoRotate]：不做破坏性迁移。
     */
    val language: String = "system",
    // 列表
    val sortOrder: SortOrder = SortOrder.MODIFIED_DESC,
    val listFilter: ListFilter = ListFilter.ALL
) {
    /**
     * 把设置映射为玻璃光学参数。
     *
     * ## v1.5.0 修掉的数据源漂移
     *
     * 旧版这里把 `intensity` 算成 `glassBlurDp / 10f` —— 把「模糊」和
     * 「折射」两个完全不相干的物理量强行耦合。结果是用户一调模糊，
     * 折射也跟着变；而 `GlassSpec` 里精心定的默认值（当时是 18dp）
     * 被 `AppSettings` 的默认值 10f 完全覆盖，**从未生效过**。
     *
     * 现在两者独立：
     * - `blurDp` 直接取用户设置（默认已改为 22f）
     * - `intensity` 只跟折射强度设置挂钩，1.0 = 规范基准
     */
    fun toGlassSpec(): GlassSpec {
        val base = GlassSpec()
        return base.copy(
            blurDp = glassBlurDp,
            // 用户可调 0~0.30，规范基准 0.12 → 倍率 0~2.5
            intensity = (glassRefraction / 0.12f).coerceIn(0f, 2.5f),
            motionScale = when (motionLevel) {
                MotionLevel.FULL -> 1f
                MotionLevel.REDUCED -> 0.4f
                MotionLevel.OFF -> 0f
            }
        )
    }
}

/**
 * 设置与本地状态持久化。仅存于应用私有目录，不上传。
 */
class PrefsRepository(private val context: Context) {

    private object Keys {
        val readingMode = stringPreferencesKey("reading_mode")
        val zoomLimit = intPreferencesKey("zoom_limit")
        val autoRotate = booleanPreferencesKey("auto_rotate")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val rememberPosition = booleanPreferencesKey("remember_position")
        val autoHideToolbar = booleanPreferencesKey("auto_hide_toolbar")
        val haptic = booleanPreferencesKey("haptic")
        val themeMode = stringPreferencesKey("theme_mode")
        val glassBlur = intPreferencesKey("glass_blur")
        val glassRefraction = intPreferencesKey("glass_refraction_x100")
        val motionLevel = stringPreferencesKey("motion_level")
        val accentFromContent = booleanPreferencesKey("accent_from_content")
        val compactList = booleanPreferencesKey("compact_list")
        val saveMode = stringPreferencesKey("save_mode")
        val autoSave = booleanPreferencesKey("auto_save_annotations")
        val highlightColor = stringPreferencesKey("highlight_color")
        val inkWidth = intPreferencesKey("ink_width_x10")
        val undoLimit = intPreferencesKey("undo_limit")
        val offlineMode = booleanPreferencesKey("offline_mode")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val allowLink = booleanPreferencesKey("allow_link_preview")
        val language = stringPreferencesKey("language")
        val sortOrder = stringPreferencesKey("sort_order")
        val listFilter = stringPreferencesKey("list_filter")
        val hiddenIds = stringSetPreferencesKey("hidden_ids")
        val safTrees = stringSetPreferencesKey("saf_trees")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    /** 覆盖写入用户改动：先读快照 → 计算新值 → 全量写回 */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        val next = block(settingsSnapshot())
        context.dataStore.edit { p ->
            p[Keys.readingMode] = next.readingMode.name
            p[Keys.zoomLimit] = next.zoomLimit
            p[Keys.autoRotate] = next.autoRotate
            p[Keys.keepScreenOn] = next.keepScreenOn
            p[Keys.rememberPosition] = next.rememberPosition
            p[Keys.autoHideToolbar] = next.autoHideToolbar
            p[Keys.haptic] = next.hapticFeedback
            p[Keys.themeMode] = next.themeMode.name
            p[Keys.glassBlur] = next.glassBlurDp.toInt()
            p[Keys.glassRefraction] = (next.glassRefraction * 100).toInt()
            p[Keys.motionLevel] = next.motionLevel.name
            p[Keys.accentFromContent] = next.accentFromContent
            p[Keys.compactList] = next.compactList
            p[Keys.saveMode] = next.saveMode.name
            p[Keys.autoSave] = next.autoSaveAnnotations
            p[Keys.highlightColor] = next.defaultHighlightColor.toString()
            p[Keys.inkWidth] = (next.defaultInkWidth * 10).toInt()
            p[Keys.undoLimit] = next.undoLimit
            p[Keys.offlineMode] = next.offlineMode
            p[Keys.wifiOnly] = next.wifiOnly
            p[Keys.allowLink] = next.allowLinkPreview
            p[Keys.language] = next.language
            p[Keys.sortOrder] = next.sortOrder.name
            p[Keys.listFilter] = next.listFilter.name
        }
    }

    private suspend fun settingsSnapshot(): AppSettings =
        context.dataStore.data.first().toSettings()

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        readingMode = this[Keys.readingMode]?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
            ?: ReadingMode.CONTINUOUS,
        zoomLimit = this[Keys.zoomLimit] ?: 5,
        autoRotate = this[Keys.autoRotate] ?: true,
        keepScreenOn = this[Keys.keepScreenOn] ?: false,
        rememberPosition = this[Keys.rememberPosition] ?: true,
        autoHideToolbar = this[Keys.autoHideToolbar] ?: true,
        hapticFeedback = this[Keys.haptic] ?: true,
        themeMode = this[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        glassBlurDp = (this[Keys.glassBlur] ?: 22).toFloat(),
        glassRefraction = (this[Keys.glassRefraction] ?: 12) / 100f,
        motionLevel = this[Keys.motionLevel]?.let { runCatching { MotionLevel.valueOf(it) }.getOrNull() }
            ?: MotionLevel.FULL,
        accentFromContent = this[Keys.accentFromContent] ?: true,
        compactList = this[Keys.compactList] ?: false,
        saveMode = this[Keys.saveMode]?.let { runCatching { SaveMode.valueOf(it) }.getOrNull() }
            ?: SaveMode.SAVE_AS,
        autoSaveAnnotations = this[Keys.autoSave] ?: true,
        defaultHighlightColor = this[Keys.highlightColor]?.toLongOrNull() ?: 0xFFFFD60A,
        defaultInkWidth = (this[Keys.inkWidth] ?: 20) / 10f,
        undoLimit = this[Keys.undoLimit] ?: 50,
        offlineMode = this[Keys.offlineMode] ?: false,
        wifiOnly = this[Keys.wifiOnly] ?: true,
        allowLinkPreview = this[Keys.allowLink] ?: true,
        language = this[Keys.language] ?: "system",
        sortOrder = this[Keys.sortOrder]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.MODIFIED_DESC,
        listFilter = this[Keys.listFilter]?.let { runCatching { ListFilter.valueOf(it) }.getOrNull() }
            ?: ListFilter.ALL
    )

    // ---- 隐藏列表 / SAF 目录 ----

    val hiddenIds: Flow<Set<String>> = context.dataStore.data.map { it[Keys.hiddenIds] ?: emptySet() }
    val safTrees: Flow<Set<String>> = context.dataStore.data.map { it[Keys.safTrees] ?: emptySet() }

    suspend fun hide(id: String) = context.dataStore.edit { it[Keys.hiddenIds] = (it[Keys.hiddenIds] ?: emptySet()) + id }
    suspend fun unhideAll() = context.dataStore.edit { it[Keys.hiddenIds] = emptySet() }
    suspend fun addSafTree(uri: String) = context.dataStore.edit { it[Keys.safTrees] = (it[Keys.safTrees] ?: emptySet()) + uri }
    suspend fun removeSafTree(uri: String) = context.dataStore.edit { it[Keys.safTrees] = (it[Keys.safTrees] ?: emptySet()) - uri }

    // ---- 阅读进度（每文件） ----

    private val progressKey = stringPreferencesKey("progress")
    val progressMap: Flow<Map<String, Int>> = context.dataStore.data.map { p ->
        (p[progressKey] ?: "").split(';').mapNotNull { seg ->
            val parts = seg.split(':')
            if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: return@mapNotNull null) else null
        }.toMap()
    }

    /** 同步快照（在 IO 线程调用）：读取某文档的上次阅读页 */
    suspend fun progressMapSnapshot(docKey: String): Int =
        context.dataStore.data.first()[progressKey]
            ?.split(';')
            ?.firstOrNull { it.startsWith("$docKey:") }
            ?.substringAfter(':')
            ?.toIntOrNull()
            ?: 0

    suspend fun saveProgress(docKey: String, page: Int) = context.dataStore.edit { p ->
        val map = (p[progressKey] ?: "").split(';').mapNotNull { seg ->
            val parts = seg.split(':')
            if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: return@mapNotNull null) else null
        }.toMap().toMutableMap()
        map[docKey] = page
        p[progressKey] = map.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    /** 清空阅读记录/隐藏记录（不删除用户 PDF） */
    suspend fun clearLocalData() = context.dataStore.edit { p ->
        p.remove(Keys.hiddenIds)
        p.remove(progressKey)
    }
}
