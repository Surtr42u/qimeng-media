package media.qimeng.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.LikeFingerprint
import media.qimeng.app.core.data.repository.LikeMutationTracker
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.RecommendPaging
import media.qimeng.app.core.model.SortOrder

/** 首页三 tab（GUIDE_UI §首页：推荐 / COS / 排行榜，左右横滑切换）；展示文案在 feature strings.xml（tabLabelRes 映射），不进状态层 */
enum class HomeTab {
    RECOMMEND,
    COS,
    RANK,
}

/** 推荐流状态：一次拉满 200、本地分批揭示、滚到底换 seed 追加（拍板口径） */
data class RecommendState(
    val seed: Long = INITIAL_SEED,
    val pulled: List<MediaAsset> = emptyList(),
    val revealed: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
) {
    companion object {
        /** seed 起始值：协议 seed>0 才打散（openapi /recommendations seed 注释），从 1 起步 */
        const val INITIAL_SEED = 1L
    }
}

/** COS 流状态（独立入口 GET /assets?cosOnly=1，cursor 分页；拍板 A3：非推荐算法） */
data class CosState(
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val exhausted: Boolean = false,
    val loaded: Boolean = false,
)

/** 排行榜状态（缺省日榜显式传 period=day；周期四档） */
data class RankState(
    val period: RankingPeriod = RankingPeriod.DAY,
    val items: List<MediaAsset> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
)

/** 首页聚合 UI 状态（三 tab 缓存独立：切 tab 不刷新、熄屏不刷新——排序缓存留在状态里） */
data class HomeUiState(
    val currentTab: HomeTab = HomeTab.RECOMMEND,
    val recommend: RecommendState = RecommendState(),
    val cos: CosState = CosState(),
    val rank: RankState = RankState(),
    val errorMessage: String? = null,
)

/**
 * 首页推荐流 ViewModel：后处理（混合/打散/惩罚）全在服务端，这里只管
 * 一次拉满→分批揭示→触底换 seed 的翻页机（[RecommendPaging] 纯函数，单测锁定）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    private val batchIndex: MediaBatchIndex,
    private val likeMutationTracker: LikeMutationTracker,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * 上次留存（采纳基线）的点赞变更指纹：[onHomeResumed] 每次回调快照对比，
     * 仅 Main 线程读写（生命周期回调驱动），无需原子化。
     */
    private var lastLikeFingerprint: LikeFingerprint? = null

    /**
     * 排行榜周期代际号：切周期即递增。弱网下仓库响应可能乱序归位（与相册页同族
     * 缺陷，2026-09-06 审查清偿补齐），请求发起时快照代际、响应落地前校验——
     * 旧代响应（含失败）一律丢弃，不再覆盖新周期。读写都在 Main，无需原子类。
     */
    private var rankGeneration = 0

    /** 首页网格列数（1~2 列持久化，LEGACY §F） */
    val homeColumns: StateFlow<Int> = gridPrefs.homeColumns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DataStoreGridPrefsRepository.DEFAULT_HOME_COLUMNS,
        )

    init {
        // 首屏只加载当前 tab；其余 tab 首次切换时懒加载（tab 缓存独立）
        loadRecommend(isInitial = true)
    }

    fun switchTab(tab: HomeTab) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
        when (tab) {
            HomeTab.RECOMMEND -> if (!_uiState.value.recommend.loaded) loadRecommend(isInitial = true)
            HomeTab.COS -> if (!_uiState.value.cos.loaded) loadCosPage(isInitial = true)
            HomeTab.RANK -> if (!_uiState.value.rank.loaded) loadRank(isInitial = true)
        }
    }

    fun selectPeriod(period: RankingPeriod) {
        val current = _uiState.value
        if (current.rank.period == period) return
        rankGeneration += 1 // 新周期=新代际：在途旧周期响应一律作废
        _uiState.value = current.copy(rank = current.rank.copy(period = period))
        loadRank(isInitial = true)
    }

    /**
     * 下拉刷新（GUIDE_UI §下拉刷新 L86「清空**所有 tab** 的排序缓存」）：当前 tab 立即重拉
     * （推荐=B 站式换 seed 全量重排；COS/排行=重拉当前页），另两 tab 数据缓存清空标脏
     * （items 清空 + loaded=false），切入时经 [switchTab] 懒重拉——不再残留刷新前的旧数据。
     * 当前 tab 不预清数据：刷新在途旧内容保持可见（防在途防重拦截后白屏），响应落地即整体替换。
     */
    fun refresh() {
        val current = _uiState.value
        _uiState.value = current.copy(
            recommend = if (current.currentTab == HomeTab.RECOMMEND) {
                current.recommend
            } else {
                current.recommend.copy(pulled = emptyList(), revealed = 0, loaded = false)
            },
            cos = if (current.currentTab == HomeTab.COS) {
                current.cos
            } else {
                current.cos.copy(items = emptyList(), nextCursor = null, exhausted = false, loaded = false)
            },
            rank = if (current.currentTab == HomeTab.RANK) {
                current.rank
            } else {
                current.rank.copy(items = emptyList(), loaded = false)
            },
        )
        when (current.currentTab) {
            HomeTab.RECOMMEND -> loadRecommend(isInitial = false, isRefresh = true)
            HomeTab.COS -> loadCosPage(isInitial = false, isRefresh = true)
            HomeTab.RANK -> loadRank(isInitial = false, isRefresh = true)
        }
    }

    /**
     * 返回/回前台（ON_RESUME，由 HomeScreen 生命周期观测驱动）：点赞变更指纹
     * （[LikeMutationTracker]，SSE 无 like 事件，本地感知是协议内唯一路径）与上次留存不一致时
     * 重拉当前 tab——详情页点赞后返回自动重排（GUIDE_UI §下拉刷新 L89；重排效果由服务端
     * 打分决定，客户端不做语义假设，只负责整页重拉；推荐走刷新路径换 seed，同 seed 服务端
     * 返回同一打散序、重排不可见）；无变更不重拉=「浏览退出保持原样」半边天然满足。
     * 首次回调只采纳基线（进页不误刷）。
     */
    fun onHomeResumed() {
        val snapshot = likeMutationTracker.fingerprint()
        val last = lastLikeFingerprint
        lastLikeFingerprint = snapshot
        if (last == null || last == snapshot) return
        when (_uiState.value.currentTab) {
            HomeTab.RECOMMEND -> loadRecommend(isInitial = false, isRefresh = true)
            HomeTab.COS -> loadCosPage(isInitial = false, isRefresh = true)
            HomeTab.RANK -> loadRank(isInitial = false, isRefresh = true)
        }
    }

    fun toggleHomeColumns() {
        viewModelScope.launch {
            val current = homeColumns.value
            val next = if (current >= DataStoreGridPrefsRepository.MAX_HOME_COLUMNS) {
                DataStoreGridPrefsRepository.MIN_HOME_COLUMNS
            } else {
                current + 1
            }
            gridPrefs.setHomeColumns(next)
        }
    }

    /** 距底触发：推荐流揭示下一批 / 批次尽则换 seed 追加；COS 流拉下一页；排行一次拉满无操作 */
    fun onNearBottom() {
        when (_uiState.value.currentTab) {
            HomeTab.RECOMMEND -> {
                val s = _uiState.value.recommend
                if (s.isLoading || s.isRefreshing) return
                val target = RecommendPaging.nextReveal(s.revealed, s.pulled.size)
                if (target != null) {
                    _uiState.value = _uiState.value.copy(recommend = s.copy(revealed = target))
                } else {
                    appendNextSeedRound()
                }
            }
            HomeTab.COS -> {
                val s = _uiState.value.cos
                if (!s.isLoading && !s.exhausted) loadCosPage(isInitial = false)
            }
            HomeTab.RANK -> Unit
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * 进详情前的批次上下文写入（详情页「i / N」序号与 3b 滑动切换的数据链）：
     * 「已加载 = 当前显示清单」口径——recommend=pulled.take(revealed)（分批揭示的可见部分）、
     * cos/rank=items（整页即显示）。快照式整体替换 [MediaBatchIndex.ids]。
     */
    fun enterDetail(assetId: String) {
        val current = _uiState.value
        batchIndex.ids = when (current.currentTab) {
            HomeTab.RECOMMEND -> current.recommend.pulled.take(current.recommend.revealed).map { it.id }
            HomeTab.COS -> current.cos.items.map { it.id }
            HomeTab.RANK -> current.rank.items.map { it.id }
        }
    }

    private fun loadRecommend(isInitial: Boolean, isRefresh: Boolean = false) {
        val current = _uiState.value
        if (current.recommend.isLoading) return
        val nextSeed = if (isRefresh || !isInitial) current.recommend.seed + 1 else current.recommend.seed
        _uiState.value = current.copy(
            recommend = current.recommend.copy(
                isLoading = true,
                isRefreshing = isRefresh,
                seed = nextSeed,
            ),
        )
        viewModelScope.launch {
            runCatching {
                mediaRepository.recommendations(
                    seed = nextSeed,
                    limit = RecommendPaging.PULL_LIMIT,
                    mediaType = null,
                )
            }.onSuccess { items ->
                _uiState.value = _uiState.value.copy(
                    recommend = _uiState.value.recommend.copy(
                        pulled = items,
                        revealed = minOf(items.size, RecommendPaging.BATCH_SIZE),
                        isLoading = false,
                        isRefreshing = false,
                        loaded = true,
                    ),
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                    recommend = _uiState.value.recommend.copy(isLoading = false, isRefreshing = false),
                )
            }
        }
    }

    /** 批次尽触底：换 seed 追加一轮（无限流；追加前去重，渲染顺序 = 服务端给出的打散序） */
    private fun appendNextSeedRound() {
        val current = _uiState.value
        val nextSeed = current.recommend.seed + 1
        _uiState.value = current.copy(
            recommend = current.recommend.copy(isLoading = true, seed = nextSeed),
        )
        viewModelScope.launch {
            runCatching {
                mediaRepository.recommendations(nextSeed, RecommendPaging.PULL_LIMIT, null)
            }.onSuccess { items ->
                val seen = _uiState.value.recommend.pulled.map { it.id }.toHashSet()
                val fresh = items.filterNot { seen.contains(it.id) }
                _uiState.value = _uiState.value.copy(
                    recommend = _uiState.value.recommend.copy(
                        pulled = _uiState.value.recommend.pulled + fresh,
                        isLoading = false,
                    ),
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                    recommend = _uiState.value.recommend.copy(isLoading = false),
                )
            }
        }
    }

    private fun loadCosPage(isInitial: Boolean, isRefresh: Boolean = false) {
        val current = _uiState.value
        if (current.cos.isLoading) return
        _uiState.value = current.copy(cos = current.cos.copy(isLoading = true, isRefreshing = isRefresh))
        viewModelScope.launch {
            runCatching {
                mediaRepository.assets(
                    AssetQuery(
                        cursor = if (isRefresh || isInitial) null else _uiState.value.cos.nextCursor,
                        limit = COS_PAGE_SIZE,
                        cosOnly = true,
                        sort = AssetSort.DEFAULT,
                        order = SortOrder.DESC,
                    ),
                )
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    cos = _uiState.value.cos.copy(
                        items = if (isRefresh || isInitial) {
                            page.items
                        } else {
                            _uiState.value.cos.items + page.items
                        },
                        nextCursor = page.nextCursor,
                        exhausted = page.nextCursor == null,
                        isLoading = false,
                        isRefreshing = false,
                        loaded = true,
                    ),
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                    cos = _uiState.value.cos.copy(isLoading = false, isRefreshing = false),
                )
            }
        }
    }

    private fun loadRank(isInitial: Boolean, isRefresh: Boolean = false) {
        val current = _uiState.value
        // 防重语义（与代际防乱序正交）：下拉刷新在途时照旧丢弃重复触发；
        // 切周期重载不受 isLoading 拦截——在途的是旧代请求，其响应会被代际校验丢弃
        if (isRefresh && current.rank.isLoading) return
        val gen = rankGeneration
        _uiState.value = current.copy(rank = current.rank.copy(isLoading = true, isRefreshing = isRefresh))
        viewModelScope.launch {
            runCatching {
                mediaRepository.rankings(
                    period = _uiState.value.rank.period,
                    limit = RANK_PULL_LIMIT,
                    offset = RANK_OFFSET_INITIAL,
                )
            }.onSuccess { items ->
                if (gen != rankGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    rank = _uiState.value.rank.copy(
                        items = items,
                        isLoading = false,
                        isRefreshing = false,
                        loaded = true,
                    ),
                )
            }.onFailure {
                if (gen != rankGeneration) return@onFailure // 旧代失败不污染新周期
                _uiState.value = _uiState.value.copy(
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                    rank = _uiState.value.rank.copy(isLoading = false, isRefreshing = false),
                )
            }
        }
    }

    companion object {
        /** COS 流分页大小（协议 /assets 缺省 60；同真 cosOnly 优先） */
        const val COS_PAGE_SIZE = 60

        /** 排行榜单次拉取量（协议 /rankings limit 缺省 50，榜单展示规模足够） */
        const val RANK_PULL_LIMIT = 50

        /** 排行榜首拉偏移 */
        const val RANK_OFFSET_INITIAL = 0

        // 失败文案不再本地定义：共享常量 core/model LIST_LOAD_FAILED_MESSAGE（下拉刷新
        // 列表通用款，2026-09-07 审查 P3 与相册/收藏/历史同款单源）。COS_PAGE_SIZE 为
        // COS 流专用语义命名，与通用列表分页非同款，保留本地。
    }
}
