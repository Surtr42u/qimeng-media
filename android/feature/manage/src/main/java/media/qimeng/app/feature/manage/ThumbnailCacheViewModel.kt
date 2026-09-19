package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchMonitor
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.ThumbnailProgressRepository
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.ThumbnailCacheProgress

/**
 * 缩略图缓存页 UI 状态（2026-09-16 用户反馈：缩略图生成进度 + 磁盘缓存上限合并一页；
 * 2026-09-18 拆两分区 + 预取；2026-09-19 批S4 重排为「本地缩略图缓存/服务器缓存」
 * 两条目并补占用口径：本地侧增文件数，服务器侧拆「占用=缓存文件数」与「资产数」
 * 两个独立口径——排查结论见 CHANGELOG 第三百四十九笔，分子文件数含多档与孤儿，
 * 与资产数不同口径，不再组 X/Y 分数式误导）。
 * 进度与容量两块读路径独立：进度是服务端状态（可刷新），容量/条目数是本机 Coil
 * 磁盘缓存（进页读一次、清空后重读归零核对）。预取状态不经本类转写，直接透出
 * 监视端口的 [PrefetchUiState]（预取自批S4 起为纯默认自动行为，本页只读展示）。
 */
data class ThumbnailCacheUiState(
    /** 当前缓存档位（持续跟随 DataStore 流，含其他入口改档后的回填） */
    val quota: DiskCacheQuota = DiskCacheQuota.DEFAULT,
    /** 磁盘缓存当前已用字节（null = 未就绪/读失败，UI 显「—」）；清空后归零供核对 */
    val cacheSizeBytes: Long? = null,
    /** 磁盘缓存当前条目数 = 已缓存图片张数（null = 未就绪/读失败，UI 显「—」） */
    val cacheFileCount: Int? = null,
    /** 服务端缓存进度（GET /thumbnails/progress；null=未就绪或读失败，UI 显「—」降级不崩） */
    val progress: ThumbnailCacheProgress? = null,
    /** 进度读取进行中（「刷新」按钮防重） */
    val progressLoading: Boolean = false,
    /**
     * 写操作失败反馈（SettingsViewModel P2-3 同口径：setQuota 失败原实现静默吞错）。
     * 非空时 UI 横幅展示，点按消除；成功路径永不产生。
     */
    val writeError: String? = null,
)

/**
 * 缩略图缓存页 ViewModel（2026-09-16 用户反馈；2026-09-19 批S4 重排：删手动预取
 * 启停——预取为纯默认自动行为，本类只经 [ThumbnailPrefetchMonitor] 只读透出状态；
 * 容量读改为字节+条目数双口径）：缩略图生成进度（服务端 /thumbnails/progress 手动
 * 刷新）+ 磁盘缓存档位持久化与清空归零核对。缓存三件套逻辑 = SettingsViewModel
 * 现口径原样搬运（语义不变：档位写 DataStore 重启生效、清空 IO 线程执行后重读）；
 * 进度读失败降级 null 不弹横幅（装饰性计数失败不构成操作反馈）。
 * 业务规则一律走 :core:model 纯函数与 :core:data 仓库，本层只做状态编排（铁律 7）。
 */
@HiltViewModel
class ThumbnailCacheViewModel @Inject constructor(
    private val diskCachePrefsRepository: DiskCachePrefsRepository,
    private val coilCacheManager: CoilCacheManager,
    private val thumbnailProgressRepository: ThumbnailProgressRepository,
    /** 预取状态只读监视端口（预取生命周期属于 App 全局而非本页，本类不干预） */
    prefetchMonitor: ThumbnailPrefetchMonitor,
    /** DiskCache.size/文件遍历触发磁盘扫描（IO 性质），调用点统一挂 IO 调度器 */
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThumbnailCacheUiState())
    val uiState: StateFlow<ThumbnailCacheUiState> = _uiState.asStateFlow()

    /** 预取轮状态（监视端口只读透出；本页只是其众多潜在观察者之一） */
    val prefetchState: StateFlow<PrefetchUiState> = prefetchMonitor.state

    init {
        loadCacheState()
        refresh()
    }

    /**
     * 刷新（「刷新」按钮与进页共用链路）：重读缩略图进度 + 已用字节/条目数。加载中防重；
     * 进度读失败降级 null（UI 显「—」），本机容量两口径读失败同口径。
     */
    fun refresh() {
        if (_uiState.value.progressLoading) return
        _uiState.update { it.copy(progressLoading = true) }
        viewModelScope.launch {
            val progress = runCatching { thumbnailProgressRepository.progress() }.getOrNull()
            _uiState.update { it.copy(progress = progress, progressLoading = false) }
            applyLocalCacheUsage()
        }
    }

    /**
     * 切换缓存档位（SettingsViewModel 同口径）：写 DataStore 持久化；**重启生效**——
     * Coil 官方明言同一目录多个 DiskCache 实例并发会损坏缓存，运行中重建 ImageLoader
     * 不做（UI 同步注明）。失败给反馈且档位不动（UI 跟随 DataStore 流原值=回滚）。
     */
    fun setQuota(quota: DiskCacheQuota) {
        viewModelScope.launch {
            runCatching { diskCachePrefsRepository.setQuota(quota) }
                .onSuccess { _uiState.update { it.copy(quota = quota) } }
                .onFailure { _uiState.update { it.copy(writeError = SAVE_FAILED_MESSAGE) } }
        }
    }

    /** 写失败横幅点按消除（一次性反馈语义：不自动消失，避免用户错过） */
    fun dismissWriteError() {
        _uiState.update { it.copy(writeError = null) }
    }

    /** 清空磁盘缓存（SettingsViewModel 同口径：清后容量归零核对）；IO 线程执行，完成后重读本机两口径 */
    fun clearCache() {
        viewModelScope.launch {
            withContext(ioDispatcher) { coilCacheManager.clear() }
            applyLocalCacheUsage()
        }
    }

    private fun loadCacheState() {
        // 档位持续跟随 DataStore（含其他入口改档后的回填）；本机两口径进页读一次、清空后重读
        viewModelScope.launch {
            diskCachePrefsRepository.quota.collect { q -> _uiState.update { it.copy(quota = q) } }
        }
        viewModelScope.launch { applyLocalCacheUsage() }
    }

    /** 本机缓存两口径（字节 + 条目数）：磁盘扫描属 IO，读失败降级 null（UI 显「—」） */
    private suspend fun readLocalCacheUsage(): Pair<Long?, Int?> = withContext(ioDispatcher) {
        runCatching { coilCacheManager.sizeBytes() }.getOrNull() to
            runCatching { coilCacheManager.fileCount() }.getOrNull()
    }

    /** 把刚读到的本机两口径合入状态（只覆盖这两个字段，其余不动） */
    private suspend fun applyLocalCacheUsage() {
        val (bytes, count) = readLocalCacheUsage()
        _uiState.update { it.copy(cacheSizeBytes = bytes, cacheFileCount = count) }
    }

    private companion object {
        /** 写失败反馈文案（SettingsViewModel P2-3 同口径）：中文、可重试指向；成功路径永不产生 */
        const val SAVE_FAILED_MESSAGE = "保存失败，请重试"
    }
}
