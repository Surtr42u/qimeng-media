package media.qimeng.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.filterFollowed
import media.qimeng.app.core.model.matchPreset
import media.qimeng.app.core.model.toPrefsValues

/** 我的页 UI 状态（C4 资料/关注列表/推荐偏好 + C5 缓存 + C6 版本） */
data class MineUiState(
    val serverUrl: String = "",
    val followedAuthors: List<AuthorSummary> = emptyList(),
    val followedLoading: Boolean = true,
    val prefsValues: RecommendPrefsValues? = null,
    /** 当前命中的预设（四档都不匹配 = null，BottomSheet 不高亮任何行） */
    val appliedPreset: RecommendPreset? = null,
    val prefsSheetOpen: Boolean = false,
    val prefsApplying: Boolean = false,
    val cacheQuota: DiskCacheQuota = DiskCacheQuota.DEFAULT,
    /** 磁盘缓存当前已用字节（null = 未就绪）；清空后归零供核对 */
    val cacheSizeBytes: Long? = null,
    val serverVersion: String? = null,
    /**
     * 写操作失败反馈（自审 P2-3：applyPreset/unfollow/setCacheQuota 失败原实现静默吞错）。
     * 非空时 UI 横幅展示，点按消除（[SettingsViewModel.dismissWriteError]）；成功路径永不产生。
     * 2026-09-06 起同时承载关注列表**读**失败（refreshFollowed 静默失败修整，同族口径）。
     */
    val writeError: String? = null,
)

/**
 * 我的页 ViewModel（M4-6）：关注列表（客户端过滤 C4）、推荐偏好四预设（BottomSheet 应用）、
 * 缓存档位持久化（重启生效）与清空归零、服务端版本展示（C6）。
 * 业务规则一律走 :core:model 纯函数与 :core:data 仓库，本层只做状态编排。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authorRepository: AuthorRepository,
    private val prefsRepository: RecommendPrefsRepository,
    private val systemInfoRepository: SystemInfoRepository,
    private val diskCachePrefsRepository: DiskCachePrefsRepository,
    private val coilCacheManager: CoilCacheManager,
    @IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MineUiState())
    val uiState: StateFlow<MineUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { authRepository.serverUrl.collect { url -> _uiState.update { it.copy(serverUrl = url) } } }
        refreshFollowed()
        loadPrefs()
        loadServerVersion()
        loadCacheState()
    }

    /**
     * 关注列表（C4：GET /authors 全量 → followed==true 客户端过滤，纯函数在 :core:model）。
     * 读失败不清空既有列表（getOrDefault(emptyList()) 会把网络抖动伪装成「没有关注」——
     * 2026-09-06 审查卫生项）：保持原列表 + 反馈横幅（writeError 家族口径，见 P2-3）。
     */
    fun refreshFollowed() {
        viewModelScope.launch {
            runCatching { authorRepository.authors().filterFollowed() }
                .onSuccess { followed ->
                    _uiState.update { it.copy(followedAuthors = followed, followedLoading = false) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(followedLoading = false, writeError = LOAD_FOLLOWED_FAILED_MESSAGE)
                    }
                }
        }
    }

    /** 取关（PUT /authors/{id}/follow followed=false）后刷新，行即消失；失败给反馈且列表保持原状（行不乐观移除=天然回滚） */
    fun unfollow(authorId: String) {
        viewModelScope.launch {
            runCatching { authorRepository.setFollowed(authorId, false) }
                .onSuccess { refreshFollowed() }
                .onFailure { _uiState.update { it.copy(writeError = UNFOLLOW_FAILED_MESSAGE) } }
        }
    }

    private fun loadPrefs() {
        viewModelScope.launch {
            val values = runCatching { prefsRepository.prefs() }.getOrNull()
            _uiState.update { it.copy(prefsValues = values, appliedPreset = values?.matchPreset()) }
        }
    }

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
                    )
                }
            }
        }
    }

    /**
     * 切换缓存档位（C5）：写 DataStore 持久化；**重启生效**——Coil 官方明言同一目录
     * 多个 DiskCache 实例并发会损坏缓存，运行中重建 ImageLoader 不做（UI 同步注明）。
     * 失败给反馈且档位不动（UI 跟随 DataStore 流原值=回滚）。
     */
    fun setCacheQuota(quota: DiskCacheQuota) {
        viewModelScope.launch {
            runCatching { diskCachePrefsRepository.setQuota(quota) }
                .onSuccess { _uiState.update { it.copy(cacheQuota = quota) } }
                .onFailure { _uiState.update { it.copy(writeError = SAVE_FAILED_MESSAGE) } }
        }
    }

    /** 写失败横幅点按消除（一次性反馈语义：不自动消失，避免用户错过） */
    fun dismissWriteError() {
        _uiState.update { it.copy(writeError = null) }
    }

    /** 清空磁盘缓存（C5：清后容量归零核对）；IO 线程执行，完成后重读已用字节 */
    fun clearCache() {
        viewModelScope.launch {
            withContext(ioDispatcher) { coilCacheManager.clear() }
            _uiState.update { it.copy(cacheSizeBytes = readCacheSize()) }
        }
    }

    private fun loadServerVersion() {
        viewModelScope.launch {
            val version = runCatching { systemInfoRepository.serverVersion() }.getOrNull()
            _uiState.update { it.copy(serverVersion = version) }
        }
    }

    private fun loadCacheState() {
        // 档位持续跟随 DataStore（含其他入口改档后的回填）；已用字节进页读一次、清空后重读
        viewModelScope.launch {
            diskCachePrefsRepository.quota.collect { q -> _uiState.update { it.copy(cacheQuota = q) } }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(cacheSizeBytes = readCacheSize()) }
        }
    }

    private suspend fun readCacheSize(): Long? = runCatching {
        // DiskCache.size 触发磁盘扫描（IO 性质），调用点已挂 IO 调度器
        withContext(ioDispatcher) { coilCacheManager.sizeBytes() }
    }.getOrNull()

    /** 退出登录（清 token 留地址；壳层登录态流自动回登录页） */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    private companion object {
        /** 写失败反馈文案（P2-3）：中文、可重试指向；成功路径永不产生 */
        const val SAVE_FAILED_MESSAGE = "保存失败，请重试"
        const val UNFOLLOW_FAILED_MESSAGE = "取关失败，请重试"

        /** 关注列表读失败反馈文案（2026-09-06 审查卫生项：refreshFollowed 静默失败修整） */
        const val LOAD_FOLLOWED_FAILED_MESSAGE = "关注列表加载失败，请重试"
    }
}
