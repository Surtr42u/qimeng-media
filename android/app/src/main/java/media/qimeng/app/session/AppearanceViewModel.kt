package media.qimeng.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AppearancePrefsRepository
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.model.ThemeColorPreset

/**
 * 外观状态（2026-10-03 悬浮玻璃坞批）：外观模式与底栏材质两项开关的壳层级状态单源。
 *
 * 为什么放 Activity 作用域的单例 VM：MainActivity 的 Theme 解析与 QimengNavHost 的坞渲染、
 * feature:settings 的选择器都要读同一份 DataStore 流——三方各自收集 [AppearancePrefsRepository]
 * 的同一 Flow 即天然一致（DataStore 写入自动回流），本类只做「Flow → StateFlow」的壳层收敛
 * 与写代理，不持有第二份状态。登录前（LoginScreen）也可用：不依赖会话态。
 *
 * WhileSubscribed(5s) + 初始 Loading 档：Theme 解析在首值到达前用 [AppearanceMode.DEFAULT]
 * （跟随系统）渲染首帧，持久化值回流后无缝切换（与网格列数等既有偏好件的冷启动口径一致）。
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val appearancePrefsRepository: AppearancePrefsRepository,
) : ViewModel() {

    /** 外观模式（跟随系统/浅色/深色）；首帧前用默认档（跟随系统）占位 */
    val appearanceMode: StateFlow<AppearanceMode> = appearancePrefsRepository.appearanceMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppearanceMode.DEFAULT,
        )

    /** 底栏材质（液态玻璃/磨砂玻璃/纯色坞/经典）；首帧前用默认档（液态玻璃）占位 */
    val tabBarMaterial: StateFlow<TabBarMaterial> = appearancePrefsRepository.tabBarMaterial
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TabBarMaterial.DEFAULT,
        )

    /** 主题色彩预设（极光/青碧/琥珀/绯樱/苍翠/动态取色）；首帧前用默认档（极光）占位 */
    val themeColorPreset: StateFlow<ThemeColorPreset> = appearancePrefsRepository.themeColorPreset
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ThemeColorPreset.DEFAULT,
        )

    /** 设置页写入外观模式（DataStore 落盘后经流自动回流到 Theme） */
    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { appearancePrefsRepository.setAppearanceMode(mode) }
    }

    /** 设置页写入底栏材质 */
    fun setTabBarMaterial(material: TabBarMaterial) {
        viewModelScope.launch { appearancePrefsRepository.setTabBarMaterial(material) }
    }

    /** 设置页写入主题色彩预设（DataStore 落盘后经流自动回流到 Theme） */
    fun setThemeColorPreset(preset: ThemeColorPreset) {
        viewModelScope.launch { appearancePrefsRepository.setThemeColorPreset(preset) }
    }

    companion object {
        /** WhileSubscribed 停收集宽限（5s，配置变更/短暂离屏不重置上游） */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
