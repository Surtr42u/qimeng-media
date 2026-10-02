package media.qimeng.app.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.model.ThemeColorPreset
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
/**
 * 搜索历史实现：DataStore 单键 JSON 数组（org.json 平台内置，零新依赖），
 * 去重最新在前、上限 [DataStoreSearchHistoryRepository.SEARCH_HISTORY_MAX]。
 */
@Singleton
class DataStoreSearchHistoryRepository @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : SearchHistoryRepository {

    override val history: Flow<List<String>> = dataStore.data.map { prefs ->
        decode(prefs[KEY_SEARCH_HISTORY])
    }

    override suspend fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val next = buildList {
                add(trimmed)
                addAll(decode(prefs[KEY_SEARCH_HISTORY]).filterNot { it == trimmed })
            }.take(SEARCH_HISTORY_MAX)
            prefs[KEY_SEARCH_HISTORY] = JSONArray(next).toString()
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_SEARCH_HISTORY) }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** 搜索历史上限（旧版 §搜索页：最多 20 条，去重后最新在前） */
        const val SEARCH_HISTORY_MAX = 20

        private val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
    }
}

/** 网格列数持久化实现（与搜索历史同一个 client_prefs 文件，不同键） */
@Singleton
class DataStoreGridPrefsRepository @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : GridPrefsRepository {

    override val homeColumns: Flow<Int> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_HOME_COLUMNS] ?: DEFAULT_HOME_COLUMNS
        stored.coerceIn(MIN_HOME_COLUMNS, MAX_HOME_COLUMNS)
    }

    override val albumColumns: Flow<Int> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_ALBUM_COLUMNS] ?: DEFAULT_ALBUM_COLUMNS
        stored.coerceIn(MIN_ALBUM_COLUMNS, MAX_ALBUM_COLUMNS)
    }

    override suspend fun setHomeColumns(columns: Int) {
        dataStore.edit { it[KEY_HOME_COLUMNS] = columns.coerceIn(MIN_HOME_COLUMNS, MAX_HOME_COLUMNS) }
    }

    override suspend fun setAlbumColumns(columns: Int) {
        dataStore.edit { it[KEY_ALBUM_COLUMNS] = columns.coerceIn(MIN_ALBUM_COLUMNS, MAX_ALBUM_COLUMNS) }
    }

    companion object {
        /** 列数档位（旧版 GUIDE_UI §全部页/§首页：首页 1~2 列、其余页 2~5 列） */
        const val MIN_HOME_COLUMNS = 1
        const val MAX_HOME_COLUMNS = 2
        const val MIN_ALBUM_COLUMNS = 2
        const val MAX_ALBUM_COLUMNS = 5

        /** 缺省列数（首页双列；其余页 2026-09-09 用户拍板默认 3 列对齐旧版，推翻此前「双列起步」口径） */
        const val DEFAULT_HOME_COLUMNS = 2
        const val DEFAULT_ALBUM_COLUMNS = 3

        private val KEY_HOME_COLUMNS = intPreferencesKey("grid_columns_home")
        private val KEY_ALBUM_COLUMNS = intPreferencesKey("grid_columns_all")
    }
}

/** 外观偏好持久化实现（与搜索历史/网格列数同一个 client_prefs 文件，不同键） */
@Singleton
class DataStoreAppearancePrefsRepository @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : AppearancePrefsRepository {

    override val appearanceMode: Flow<AppearanceMode> = dataStore.data.map { prefs ->
        // 非法/缺失值在 fromStored 内回落默认档（枚举增删/旧版本回滚安全）
        AppearanceMode.fromStored(prefs[KEY_APPEARANCE_MODE])
    }

    override val tabBarMaterial: Flow<TabBarMaterial> = dataStore.data.map { prefs ->
        TabBarMaterial.fromStored(prefs[KEY_TAB_BAR_MATERIAL])
    }

    override val themeColorPreset: Flow<ThemeColorPreset> = dataStore.data.map { prefs ->
        ThemeColorPreset.fromStored(prefs[KEY_THEME_COLOR_PRESET])
    }

    override suspend fun setAppearanceMode(mode: AppearanceMode) {
        dataStore.edit { it[KEY_APPEARANCE_MODE] = mode.name }
    }

    override suspend fun setTabBarMaterial(material: TabBarMaterial) {
        dataStore.edit { it[KEY_TAB_BAR_MATERIAL] = material.name }
    }

    override suspend fun setThemeColorPreset(preset: ThemeColorPreset) {
        dataStore.edit { it[KEY_THEME_COLOR_PRESET] = preset.name }
    }

    companion object {
        // 键值存枚举名（稳定 id）：显示名变化不影响已持久化数据（文案进 strings.xml）
        private val KEY_APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        private val KEY_TAB_BAR_MATERIAL = stringPreferencesKey("tab_bar_material")
        private val KEY_THEME_COLOR_PRESET = stringPreferencesKey("theme_color_preset")
    }
}
