package media.qimeng.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AppearancePrefsRepository
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.model.matchPreset
import media.qimeng.app.core.model.toPrefsValues

/**
 * 我的页 UI 状态（C4 推荐/偏好 + C6 版本 + I4 数量卡；原 C5 缓存字段
 * cacheQuota/cacheSizeBytes 随「缓存区」2026-09-16 用户反馈迁往数据管理→缩略图缓存页）。
 * U10-4：serverUrl 字段随「服务器地址」卡迁出本页（地址展示/修改移入
 * ServerSettingsViewModel，同包），本状态不再收集。
 */
data class MineUiState(
    /**
     * 页首数量卡（I4，GUIDE_UI §我的页 L252：图片/视频两卡；数据源 GET /stats/overview）。
     * null = 未就绪或读失败（数字卡显示「—」降级，不崩、不弹横幅——纯计数装饰卡，
     * 失败不构成需要用户介入的操作反馈，与 writeError 操作反馈族不同口径）。
     */
    val imageCount: Int? = null,
    val videoCount: Int? = null,
    val prefsValues: RecommendPrefsValues? = null,
    /** 当前命中的预设（四档都不匹配 = null，BottomSheet 不高亮任何行） */
    val appliedPreset: RecommendPreset? = null,
    /**
     * 推荐偏好 GET 进行中（2026-09-13「点击无反应」修复批）：Sheet 内重试按钮防重 + 加载态。
     * 与写侧 prefsApplying 分开——读/写是两条互不相干的生命周期。
     */
    val prefsLoading: Boolean = false,
    /**
     * 推荐偏好 GET 失败（2026-09-13 修复批）：为 true 时 Sheet 显「加载失败/重试」态。
     * 四预设行仍恒渲染可点击（应用走 PUT 不依赖本次 GET 结果），仅当前项高亮缺席。
     */
    val prefsLoadFailed: Boolean = false,
    val prefsSheetOpen: Boolean = false,
    val prefsApplying: Boolean = false,
    val serverVersion: String? = null,
    /**
     * 写操作失败反馈（自审 P2-3：applyPreset 失败原实现静默吞错；原 setCacheQuota 一并
     * 计入本口径，随 C5 缓存区 2026-09-16 迁出退役；C4 的 unfollow 已随 G2 总览卡下线）。
     * 非空时 UI 横幅展示，点按消除（[SettingsViewModel.dismissWriteError]）；成功路径永不产生。
     */
    val writeError: String? = null,
)

/**
 * 我的页 ViewModel（M4-6）：页首数量卡（I4：/stats/overview 图片/视频计数）、
 * 推荐偏好四预设（BottomSheet 应用）、服务端版本展示（C6）。
 * 缓存档位持久化与清空（C5）2026-09-16 用户反馈迁往数据管理→缩略图缓存页
 * （feature:manage 单页承接进度+上限），本类不再依赖 DiskCachePrefsRepository/
 * CoilCacheManager，也不再持有 IO 调度器位（原仅缓存清空/读字节的磁盘扫描在用）。
 * X5 批 2026-09-12：作者总览卡退役（用户问题8，我的页改收藏同款入口行，经壳层
 * onOpenAuthors 进全部作者页），本页不再预取作者数据、不依赖 AuthorRepository。
 * U10-4：服务器地址/本机模式入口迁 ServerSettingsViewModel（同包），本类不再收集
 * serverUrl、不再承担 fillLocalModeForNextLogin。
 * 业务规则一律走 :core:model 纯函数与 :core:data 仓库，本层只做状态编排。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val statsRepository: StatsRepository,
    private val prefsRepository: RecommendPrefsRepository,
    private val systemInfoRepository: SystemInfoRepository,
    // 2026-10-03 悬浮玻璃坞批：外观偏好端口直读（与壳层 AppearanceViewModel 同一份
    // DataStore 流——写入落盘后双方各自收集的流自动回流，无第二份状态）
    private val appearancePrefsRepository: AppearancePrefsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MineUiState())
    val uiState: StateFlow<MineUiState> = _uiState.asStateFlow()

    /** 外观模式三选（跟随系统/浅色/深色）；选择器 UI 与壳层 Theme 同源 */
    val appearanceMode: StateFlow<AppearanceMode> = appearancePrefsRepository.appearanceMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppearanceMode.DEFAULT,
        )

    /** 底栏材质四选（液态玻璃/磨砂玻璃/纯色坞/经典）；选择器 UI 与壳层悬浮坞同源 */
    val tabBarMaterial: StateFlow<TabBarMaterial> = appearancePrefsRepository.tabBarMaterial
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TabBarMaterial.DEFAULT,
        )

    init {
        loadLibraryCounts()
        loadPrefs()
        loadServerVersion()
    }

    /** 外观模式写入（DataStore 落盘后壳层 Theme 与坞经流自动跟随） */
    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { appearancePrefsRepository.setAppearanceMode(mode) }
    }

    /** 底栏材质写入 */
    fun setTabBarMaterial(material: TabBarMaterial) {
        viewModelScope.launch { appearancePrefsRepository.setTabBarMaterial(material) }
    }

    /**
     * 页首数量卡（I4，GUIDE_UI L252 + 实录 mine.txt 两卡「图片 N」「视频 N」）：
     * GET /stats/overview 的 imageCount/videoCount 纯计数（不触拍板⑤容量豁免）。
     * 读失败降级为 null（数字卡显「—」），静默不弹横幅——装饰性计数卡失败
     * 不构成操作反馈（writeError 族保留给写操作）。
     */
    private fun loadLibraryCounts() {
        viewModelScope.launch {
            val overview = runCatching { statsRepository.overview() }.getOrNull()
            _uiState.update {
                it.copy(
                    imageCount = overview?.imageCount,
                    videoCount = overview?.videoCount,
                )
            }
        }
    }

    /**
     * 拉推荐偏好（2026-09-13「点击无反应」修复批改造）：GET 结果只有两种终态——
     * 成功（prefsValues 非空、失败态清除）或失败（prefsValues=null、prefsLoadFailed=true，
     * UI 进「加载失败/重试」态）；不再静默停在无数据的中间态。加载中防重（在途不重复发）。
     */
    private fun loadPrefs() {
        if (_uiState.value.prefsLoading) return
        _uiState.update { it.copy(prefsLoading = true) }
        viewModelScope.launch {
            val values = runCatching { prefsRepository.prefs() }.getOrNull()
            _uiState.update {
                it.copy(
                    prefsValues = values,
                    appliedPreset = values?.matchPreset(),
                    prefsLoading = false,
                    prefsLoadFailed = values == null,
                )
            }
        }
    }

    /** 推荐偏好加载失败后的重试入口（Sheet 内「重试」按钮）：走同一 [loadPrefs] 防重链路 */
    fun retryLoadPrefs() = loadPrefs()

    fun openPrefsSheet() = _uiState.update { it.copy(prefsSheetOpen = true) }

    fun closePrefsSheet() = _uiState.update { it.copy(prefsSheetOpen = false) }

    /** 整行点击应用预设（C4）：PUT 9 维载荷后本地推导高亮态（预设值即载荷值，无需重拉）；失败给反馈且高亮保持原项 */
    fun applyPreset(preset: RecommendPreset) {
        if (_uiState.value.prefsApplying) return
        _uiState.update { it.copy(prefsApplying = true) }
        viewModelScope.launch {
            val updated = runCatching {
                prefsRepository.putPrefs(preset.toPrefsValues())
                preset.toPrefsValues()
            }.getOrNull()
            _uiState.update {
                if (updated == null) {
                    // 失败：prefValues/appliedPreset 均不动（高亮保持原项=回滚），给出反馈
                    it.copy(prefsApplying = false, writeError = SAVE_FAILED_MESSAGE)
                } else {
                    it.copy(
                        prefsApplying = false,
                        prefsValues = updated,
                        appliedPreset = preset,
                        // PUT 成功即持有权威值，此前的 GET 失败态随之消除（Sheet 撤重试态）
                        prefsLoadFailed = false,
                    )
                }
            }
        }
    }

    /** 写失败横幅点按消除（一次性反馈语义：不自动消失，避免用户错过） */
    fun dismissWriteError() {
        _uiState.update { it.copy(writeError = null) }
    }

    private fun loadServerVersion() {
        viewModelScope.launch {
            val version = runCatching { systemInfoRepository.serverVersion() }.getOrNull()
            _uiState.update { it.copy(serverVersion = version) }
        }
    }

    /** 退出登录（清 token 留地址；壳层登录态流自动回登录页） */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    private companion object {
        /** 写失败反馈文案（P2-3）：中文、可重试指向；成功路径永不产生 */
        const val SAVE_FAILED_MESSAGE = "保存失败，请重试"

        /** WhileSubscribed 停收集宽限（5s，配置变更/短暂离屏不重置上游） */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
