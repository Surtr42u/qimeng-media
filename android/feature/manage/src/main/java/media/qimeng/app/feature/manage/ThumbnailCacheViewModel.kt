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
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.prefetch.PrefetchRevisionStore
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchMonitor
import media.qimeng.app.core.data.repository.CoilCacheManager

/**
 * 缩略图缓存页 UI 状态（2026-09-16 新建页；批S4 两口径重排；**批S5 2026-09-19 按端
 * 分池重写**——用户拍板冻结：缓存 = 手机 App 侧缓存，按连接来源分两池，两条目 UI 一致、
 * 各显示文件数 + 实际占用，原「缓存上限」行删除改为实际占用，档位四选/服务端生成进度
 * 随两卡口径重写一并退场）。四口径两两一组：NAS 池（连服务器时缓存的图）与本地池
 * （连本地端时缓存的图）；读失败/未就绪为 null，UI 显「—」降级不崩。
 */
data class ThumbnailCacheUiState(
    /** 服务器（NAS）池当前条目数 = 已缓存图片张数（null = 读失败/未就绪，UI 显「—」） */
    val nasFileCount: Int? = null,
    /** 服务器（NAS）池当前实际占用字节（null = 读失败/未就绪，UI 显「—」） */
    val nasSizeBytes: Long? = null,
    /** 本地端池当前条目数（null = 读失败/未就绪，UI 显「—」） */
    val localFileCount: Int? = null,
    /** 本地端池当前实际占用字节（null = 读失败/未就绪，UI 显「—」） */
    val localSizeBytes: Long? = null,
)

/**
 * 缩略图缓存页 ViewModel（批S5 分池重写）：两池「文件数 + 实际占用」只读统计 + 分池
 * 清空。数据获取全走 [CoilCacheManager] 只读端口 + [ThumbnailPrefetchMonitor] 只读
 * 状态（预取为纯默认自动行为，本类无启停意图面——批S4 起），本层只做状态编排（铁律 7）。
 * 磁盘扫描/清空属 IO 性质，统一挂 IO 调度器；读失败降级 null 不弹横幅（统计失败不构成
 * 操作反馈）。
 */
@HiltViewModel
class ThumbnailCacheViewModel @Inject constructor(
    private val coilCacheManager: CoilCacheManager,
    /** 预取状态只读监视端口（预取生命周期属于 App 全局而非本页，本类不干预） */
    prefetchMonitor: ThumbnailPrefetchMonitor,
    /** 预取修订号仓（清空缓存池后失效 last_prefetch_revision，防下轮误跳过——P1） */
    private val prefetchRevisionStore: PrefetchRevisionStore,
    /** DiskCache.size/文件遍历触发磁盘扫描（IO 性质），调用点统一挂 IO 调度器 */
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThumbnailCacheUiState())
    val uiState: StateFlow<ThumbnailCacheUiState> = _uiState.asStateFlow()

    /** 预取轮状态（监视端口只读透出；本页只是其众多潜在观察者之一） */
    val prefetchState: StateFlow<PrefetchUiState> = prefetchMonitor.state

    init {
        refreshUsage()
        // 数字跟着进度条走（第三百六十六笔，用户反馈「文件数/实际占用不跟着进度条动」）：
        // 预取进行中按步长重采样两池统计（每轮约 50 次，不做逐条磁盘扫描）；轮终/失败/复位
        // 各终采一次收口。磁盘遍历挂 IO 调度器，扫描成本与进页面时同源。
        var resampleCursor = 0
        viewModelScope.launch {
            prefetchMonitor.state.collect { state ->
                when (state) {
                    is PrefetchUiState.Running -> {
                        resampleCursor++
                        val step = maxOf(1, state.total / USAGE_RESAMPLE_STEPS)
                        if (resampleCursor % step == 0) refreshUsage()
                    }
                    is PrefetchUiState.Done, is PrefetchUiState.Failed -> refreshUsage()
                    // Skipped（revision 未变整轮跳过）：无下载落盘，两池口径不变，无需重采样
                    PrefetchUiState.Idle, PrefetchUiState.WaitingNetwork, PrefetchUiState.Skipped -> Unit
                }
            }
        }
    }

    /** 清空服务器（NAS）池（清后重读归零核对；IO 线程执行） */
    fun clearNasCache() = clearPool(CachePool.NAS)

    /** 清空本地端池（清后重读归零核对；IO 线程执行） */
    fun clearLocalCache() = clearPool(CachePool.LOCAL)

    private fun clearPool(pool: CachePool) {
        viewModelScope.launch {
            withContext(ioDispatcher) { runCatching { coilCacheManager.clearPool(pool) } }
            // P1（reviewer 2026-09-30）：清池后同步失效预取修订号——池内缩略图已删，
            // 若保留 last_prefetch_revision，库静态时下轮 revision 相等会误 SKIP，
            // 已清空的缓存永不补拉且缓存页「已是最新」与事实相反。修订号全局不分池，
            // NAS/本地任一池清空都作废（读回 null → 下轮 FULL 补拉）。写失败不构成
            // 清空失败（最坏只回到改动前「重启才补」的行为），静默降级与读侧同款。
            // 有意不在清空后即时触发新预取轮（预取无手动入口，批S4 拍板）：重启/重登录
            // 才补，与失效引入前的行为一致。
            runCatching { prefetchRevisionStore.clear() }
            refreshUsage()
        }
    }

    /** 进页读一次两池四口径（init）；清空后重读核对归零 */
    private fun refreshUsage() {
        viewModelScope.launch {
            val nas = readPoolUsage(CachePool.NAS)
            val local = readPoolUsage(CachePool.LOCAL)
            _uiState.update {
                it.copy(
                    nasSizeBytes = nas.first,
                    nasFileCount = nas.second,
                    localSizeBytes = local.first,
                    localFileCount = local.second,
                )
            }
        }
    }

    /** 单池两口径（字节 + 条目数）：各口径独立容错——一池读失败不影响另一池展示 */
    private suspend fun readPoolUsage(pool: CachePool): Pair<Long?, Int?> = withContext(ioDispatcher) {
        runCatching { coilCacheManager.poolSizeBytes(pool) }.getOrNull() to
            runCatching { coilCacheManager.poolFileCount(pool) }.getOrNull()
    }

    private companion object {
        /**
         * 预取进行中的统计重采样步数（第三百六十六笔）：每轮重采样约 [USAGE_RESAMPLE_STEPS]
         * 次（按 done 均匀分布），文件数/实际占用随进度条同节奏刷新；逐条扫描磁盘成本不可接受，
         * 全程只扫 50 次是「数字跟手感」与 IO 成本的折中。
         */
        const val USAGE_RESAMPLE_STEPS = 50
    }
}
