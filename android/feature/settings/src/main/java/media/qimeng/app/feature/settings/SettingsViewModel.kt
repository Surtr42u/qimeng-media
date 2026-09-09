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
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.AuthorOverview
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.matchPreset
import media.qimeng.app.core.model.toAuthorOverview
import media.qimeng.app.core.model.toPrefsValues

/** 我的页 UI 状态（C4 资料/推荐偏好 + C5 缓存 + C6 版本 + G2 作者总览卡 + I4 数量卡） */
data class MineUiState(
    val serverUrl: String = "",
    /**
     * 页首数量卡（I4，GUIDE_UI §我的页 L252：图片/视频两卡；数据源 GET /stats/overview）。
     * null = 未就绪或读失败（数字卡显示「—」降级，不崩、不弹横幅——纯计数装饰卡，
     * 失败不构成需要用户介入的操作反馈，与 writeError 操作反馈族不同口径）。
     */
    val imageCount: Int? = null,
    val videoCount: Int? = null,
    /**
     * 作者总览卡数据（G2：Web DataPage 作者总览形态——「N 位作者 · 已关注 M」+ 文件数 Top5）；
     * null=未就绪（首次加载中或读失败，失败反馈走 [writeError] 横幅，卡内不重复报错）。
     */
    val authorOverview: AuthorOverview? = null,
    /** 作者总览加载中（读失败置 false 且既有总览保持原状，见 [SettingsViewModel.refreshAuthorOverview]） */
    val authorsLoading: Boolean = true,
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
     * 写操作失败反馈（自审 P2-3：applyPreset/setCacheQuota 失败原实现静默吞错；C4 的 unfollow 已随 G2 总览卡下线）。
     * 非空时 UI 横幅展示，点按消除（[SettingsViewModel.dismissWriteError]）；成功路径永不产生。
     * 2026-09-06 起同时承载作者列表**读**失败（refreshAuthorOverview 静默失败修整，同族口径）。
     */
    val writeError: String? = null,
    /**
     * 浏览数据同步（任务L L5）：待上传事件条数（null=未就绪）、同步进行中、
     * 同步/导出的一次性结果提示（非空时行内展示，下次操作覆盖或点按消除）。
     */
    val pendingEvents: Int? = null,
    val eventSyncing: Boolean = false,
    val eventSyncNote: String? = null,
)

/**
 * 我的页 ViewModel（M4-6）：页首数量卡（I4：/stats/overview 图片/视频计数）、作者总览卡
 * （G2：Web DataPage 形态——计数+文件数 Top5，替换 C4 关注列表展示形态）、
 * 推荐偏好四预设（BottomSheet 应用）、缓存档位持久化（重启生效）与清空归零、
 * 服务端版本展示（C6）。
 * 业务规则一律走 :core:model 纯函数与 :core:data 仓库，本层只做状态编排。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authorRepository: AuthorRepository,
    private val statsRepository: StatsRepository,
    private val prefsRepository: RecommendPrefsRepository,
    private val systemInfoRepository: SystemInfoRepository,
    private val diskCachePrefsRepository: DiskCachePrefsRepository,
    private val coilCacheManager: CoilCacheManager,
    /** 浏览打点离线队列（任务L L5：立即同步/导出未上传的执行体） */
    private val viewEventQueue: ViewEventQueue,
    @IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MineUiState())
    val uiState: StateFlow<MineUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { authRepository.serverUrl.collect { url -> _uiState.update { it.copy(serverUrl = url) } } }
        loadLibraryCounts()
        refreshAuthorOverview()
        loadPrefs()
        loadServerVersion()
        loadCacheState()
        loadPendingEvents()
    }

    /**
     * 页首数量卡（I4，GUIDE_UI L252 + 实录 mine.txt 两卡「图片 N」「视频 N」）：
     * GET /stats/overview 的 imageCount/videoCount 纯计数（不触拍板⑤容量豁免）。
     * 读失败降级为 null（数字卡显「—」），静默不弹横幅——装饰性计数卡失败
     * 不构成操作反馈（writeError 族保留给写操作与作者总览读失败）。
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
     * 作者总览（G2：GET /authors 全量 → :core:model 纯函数 [toAuthorOverview] 聚合——
     * 「N 位作者 · 已关注 M」双计数 + 文件数 Top5，替换 C4 关注列表展示形态）。
     * 读失败不清空既有总览（网络抖动不伪装成「没有作者」，与 C4 读失败同族口径）：
     * 保持原数据 + 反馈横幅（writeError 家族口径，见 P2-3）。
     * 取关不再由本页承担（总览卡无取关行），关注 toggle 走作者管理页（feature:author）。
     */
    fun refreshAuthorOverview() {
        viewModelScope.launch {
            runCatching { authorRepository.authors().toAuthorOverview() }
                .onSuccess { overview ->
                    _uiState.update { it.copy(authorOverview = overview, authorsLoading = false) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(authorsLoading = false, writeError = LOAD_AUTHORS_FAILED_MESSAGE)
                    }
                }
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

    // ---------- 浏览数据同步（任务L L5：本地优先队列的手动入口，最小 UI） ----------

    /** 待上传条数（进页读一次；同步/导出后随结果刷新） */
    private fun loadPendingEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingEvents = runCatching { viewEventQueue.pendingCount() }.getOrNull()) }
        }
    }

    /**
     * 立即同步：手动触发一轮 drain（与三通道自动补传同一执行体、同一 Mutex 串行）。
     * 退避中的行不到期不会被本次 drain 取走（防「连点立即同步狂打故障端点」），
     * 结果提示按摘要语义给出。
     */
    fun syncEventsNow() {
        if (_uiState.value.eventSyncing) return
        _uiState.update { it.copy(eventSyncing = true, eventSyncNote = null) }
        viewModelScope.launch {
            val summary = runCatching { viewEventQueue.drain() }.getOrNull()
            val pending = runCatching { viewEventQueue.pendingCount() }.getOrNull()
            _uiState.update {
                it.copy(
                    eventSyncing = false,
                    pendingEvents = pending,
                    eventSyncNote = when {
                        summary == null -> EVENT_SYNC_FAILED_MESSAGE
                        summary.keptForRetry > 0 -> "网络不通，${summary.keptForRetry} 条稍后自动重试"
                        summary.dropped > 0 -> "同步完成；${summary.dropped} 条发送失败已保留（可导出）"
                        else -> "同步完成"
                    },
                )
            }
        }
    }

    /**
     * 导出未上传 JSON 的取数端（用户原话 #25「可以把安卓本地的直接传给 nas 合并」）：
     * 只取数据不碰平台 IO——SAF 写文件是平台胶水，留在屏幕层（LocalContext +
     * CreateDocument），VM 不持有 Context。写完调 [onExported] 回填结果提示。
     * null = 读队列失败（队列 DB 层异常）。
     */
    suspend fun exportPending(): ViewEventQueue.PendingExport? =
        runCatching { viewEventQueue.exportPending() }.getOrNull()

    /** 导出结果回填：count 非空 = 成功导出条数；null = 写文件失败 */
    fun onExported(count: Int?) {
        _uiState.update {
            it.copy(
                eventSyncNote = if (count != null) "已导出 $count 条未上传事件" else EVENT_EXPORT_FAILED_MESSAGE,
            )
        }
    }

    /** 同步/导出结果提示点按消除 */
    fun dismissEventSyncNote() {
        _uiState.update { it.copy(eventSyncNote = null) }
    }

    private companion object {
        /** 写失败反馈文案（P2-3）：中文、可重试指向；成功路径永不产生 */
        const val SAVE_FAILED_MESSAGE = "保存失败，请重试"

        /** 作者总览读失败反馈文案（C4 关注列表读失败同族口径，G2 随形态更名） */
        const val LOAD_AUTHORS_FAILED_MESSAGE = "作者列表加载失败，请重试"

        /** 浏览数据同步失败反馈文案（任务L L5）：drain 抛出（本地 DB 层）时给出 */
        const val EVENT_SYNC_FAILED_MESSAGE = "同步失败，请重试"

        /** 浏览数据导出失败反馈文案（任务L L5）：写文件失败时给出 */
        const val EVENT_EXPORT_FAILED_MESSAGE = "导出失败，请重试"
    }
}
