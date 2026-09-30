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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.LikeFingerprint
import media.qimeng.app.core.data.repository.LikeMutationTracker
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.TagNameConflictException
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchThrottle
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FilterPanelUiState
import media.qimeng.app.core.model.APPEND_RETRY_DELAYS_MS
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_UP_TO_DATE_MESSAGE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.PanelFeedback
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.RecommendPaging
import media.qimeng.app.core.model.withPanelDraft
import media.qimeng.app.core.network.ServerReadinessProbe

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
    /** 换轮穷尽标记（问题A fresh==0 死锁修复，2026-09-28）：连续空轮达上限后置位，
     *  [onNearBottom] 据此停发换轮请求；刷新/首载成功即复位（新一轮重新计数） */
    val exhausted: Boolean = false,
    /** 列表加载成功结束信号（问题A 哨兵哑火修复，2026-09-28）：成功落地即自增，
     *  QimengMediaGrid 触底哨兵以它为重评估 key——钉底用户在「成功但条数不变」的加载
     *  （空页追加等）后哨兵不再哑火。失败不 bump：失败路径已由横幅+手动回滚兜底，
     *  bump 会与自动重试叠加成无限锤击循环（见 appendNextSeedRound 注释） */
    val reloadTick: Int = 0,
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
    /** 列表加载成功结束信号（问题A 哨兵哑火修复，2026-09-28，语义见 RecommendState.reloadTick） */
    val reloadTick: Int = 0,
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
    // ---- 万能筛选面板态（任务Y Y4b；作用域=COS tab——/recommendations、/rankings 协议无
    // 筛选参数，推荐/排行榜不受面板影响；顶栏入口恒显，对齐旧版顶栏实录） ----

    /** 面板开关/编辑草稿/标签候选/面板内反馈（类型单源 core/model，AlbumViewModel 同范式互指） */
    val filterPanel: FilterPanelUiState = FilterPanelUiState(),
    /** 已应用面板筛选（COS 流 AssetQuery 展开源；默认=全缺省档，查询行为与 Y4b 前一致） */
    val cosFilter: AlbumPanelDraft = AlbumPanelDraft(),
    val errorMessage: String? = null,

    /**
     * 刷新成功但内容与刷新前完全一致的轻提示（问题「COS 下拉无效」，2026-09-29）：
     * 刷新确实执行且成功、但服务端返回同页同序数据，界面零变化——用户无法区分
     * 「没反应」和「刷新了但没新的」。命中时短暂亮一次中性提示（自动消失，
     * [INFO_HINT_AUTO_CLEAR_MS]），内容有变化时清空。与 [errorMessage]（失败横幅）
     * 分流：这是成功路径的告知，不是错误。
     */
    val infoMessage: String? = null,
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
    // 2026-09-18 探针接线（单机形态冷启动空白修复）：首屏请求前先等服务端就绪（ADR-0015
    // 内嵌服务端有秒级未就绪窗口）。仅 init 首屏使用；switchTab 懒加载不加探针（见该处注释）。
    private val readinessProbe: ServerReadinessProbe,
    // 预取避让（问题B 下拉刷新响应慢修复，2026-09-28）：下拉刷新开始时让全库缩略图预取
    // 暂停抢带宽（窄接口，语义见 ThumbnailPrefetchThrottle KDoc）
    private val prefetchThrottle: ThumbnailPrefetchThrottle,
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

    /**
     * COS 流筛选代际号（任务Y Y4b，AlbumViewModel.filterGeneration 同范式互指）：面板筛选
     * 应用即递增。请求发起时快照代际、响应落地前校验——旧代响应（含失败）一律丢弃，
     * 不再覆盖新筛选态。读写都在 Main（viewModelScope 与状态更新同线程），无需原子类。
     * 问题B（2026-09-28）：下拉刷新也在此代际框架内受理——refresh 递增代际作废在途旧响应，
     * 刷新不再被 isLoading 拦截吞掉（受理路径改造，代际机制本身未动）。
     */
    private var cosGeneration = 0

    /**
     * 推荐流代际号（问题B 刷新不被吞，2026-09-28）：与 rankGeneration/cosGeneration 同范式。
     * 推荐流此前无代际（旧注释「推荐流无代际语义」自此作废）——刷新被在途拦截时要么静默
     * 丢弃刷新手势、要么放行后在途旧响应乱序覆盖新轮。现刷新路径递增本代际：在途旧响应
     * （含换 seed 追加轮）一律作废；首载/静默重试/追加路径不递增、照旧快照校验。
     */
    private var recommendGeneration = 0

    /**
     * 上次 tab 切换时间戳（哨兵抑制窗口判定，任务J J3a）：初值取极小让冷启动
     * 首布局不在窗口内（init 揭示后的哨兵触发是既有分批行为，不是本批要抑制的对象）。
     */
    private var lastTabSwitchAtMs = Long.MIN_VALUE

    /**
     * 时钟源（哨兵抑制窗口判定用；internal 可变=单测推进时间入口，生产恒默认墙钟）。
     * 为什么不是构造注入：Hilt @Inject constructor 无法提供函数类型绑定。
     */
    internal var clockMs: () -> Long = System::currentTimeMillis

    /** 首页网格列数（1~2 列持久化，LEGACY §F） */
    val homeColumns: StateFlow<Int> = gridPrefs.homeColumns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DataStoreGridPrefsRepository.DEFAULT_HOME_COLUMNS,
        )

    // ---------- 首屏失败自动重试（2026-09-15 用户反馈「刚进来不显示内容要点一下其他页面」）：
    // 冷启动本机模式时内嵌服务端尚在启动（exec+SQLite 迁移秒级），首屏请求必然失败且
    // loaded=false，UI 空白直到用户切 tab 触发懒加载。失败后按 tab 退避重试，服务端就绪
    // 即自愈；上限防无限循环掩盖真实故障。
    // 2026-09-17 用户反馈「每次进入首页都显示加载失败请下拉重试」修正亮牌时机：77435fb 起
    // 首枪失败即设 errorMessage（HomeScreen 见 errorMessage 非 null 无条件亮横幅），随后自动
    // 重试成功又不清横幅——冷启动必闪且残留假错误。改为：首屏路径（isInitial 且非刷新且尚未
    // loaded）失败若还有重试预算则**静默**调度重试（保持加载/空白态，不亮横幅），预算耗尽
    // 仍失败才亮横幅=真故障反馈；用户主动动作（下拉刷新/翻页追加/切周期）失败照旧立即亮。
    // 任意加载成功清陈旧横幅并归零该 tab 重试预算（自愈后不残留「加载失败」）。
    // 2026-09-18 演进（单机形态冷启动空白修复）：init 首拉前先经 [ServerReadinessProbe]
    // awaitReady 等服务端就绪（/healthz 300ms 粒度探活），替代此前「盲目首枪失败→固定间隔
    // 重试去撞就绪窗口」；探针超时（返回 false）也照常发起首拉——探针只负责「等服务端起来」，
    // 失败反馈仍走本节静默重试→预算耗尽亮横幅的既有路径。固定 1.5s 间隔改梯度退避
    // （300ms × 2^n 封顶 2000ms，取值理由见常量 KDoc）：「服务端未就绪」主场景已由探针
    // 覆盖，退避只兜瞬态失败，300ms 起步让真故障也能快速自愈。 ----------
    private val initialRetryAttempts = mutableMapOf<HomeTab, Int>()

    /** @return 是否成功调度了重试（false=预算已耗尽，调用方应亮真错误横幅） */
    private fun scheduleInitialRetry(tab: HomeTab): Boolean {
        val n = initialRetryAttempts.getOrDefault(tab, 0)
        if (n >= INITIAL_RETRY_MAX) return false
        initialRetryAttempts[tab] = n + 1
        // 梯度退避：第 n 次延迟 = min(300ms × 2^n, 2000ms)（为什么这么取值见常量 KDoc）
        val delayMs = minOf(INITIAL_RETRY_BASE_DELAY_MS * (1L shl n), INITIAL_RETRY_MAX_DELAY_MS)
        viewModelScope.launch {
            delay(delayMs)
            // 用户已切走该 tab 则放弃（切回时 switchTab 懒加载兜底）
            if (_uiState.value.currentTab != tab) return@launch
            when (tab) {
                HomeTab.RECOMMEND -> if (!_uiState.value.recommend.loaded) loadRecommend(isInitial = true)
                HomeTab.COS -> if (!_uiState.value.cos.loaded) loadCosPage(isInitial = true)
                HomeTab.RANK -> if (!_uiState.value.rank.loaded) loadRank(isInitial = true)
            }
        }
        return true
    }

    init {
        // 首屏只加载当前 tab；其余 tab 首次切换时懒加载（tab 缓存独立）。
        // 2026-09-18 演进（单机形态冷启动空白修复）：首拉前先等服务端就绪探针
        // （/healthz 300ms 粒度轮询，替代此前「首枪必失败→固定间隔重试撞就绪窗口」——
        // 服务端 200ms 就绪了也得白等满 1.5s 的根因）。awaitReady 返回 false（预算内
        // 未就绪/未配置地址）也照常发起：探针只负责「等服务端起来」，失败反馈仍走
        // scheduleInitialRetry 静默重试→预算耗尽亮横幅的既有路径。
        viewModelScope.launch {
            readinessProbe.awaitReady()
            loadRecommend(isInitial = true)
        }
    }

    fun switchTab(tab: HomeTab) {
        // 刷新哨兵抑制窗口起点（任务J J3a，台账 #35）：见 [onNearBottom] 窗口判定
        lastTabSwitchAtMs = clockMs()
        _uiState.value = _uiState.value.copy(currentTab = tab)
        // 懒加载不加探针：能切 tab 说明首屏已渲染、init 探活已过，服务端必已就绪；
        // 再探只徒增等待，真有瞬态失败也有静默退避重试兜底（2026-09-18 探针接线记档）
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
     * 问题B（2026-09-28）：刷新改为「立即受理」——在途加载不再吞掉刷新手势（各 load* 的
     * isLoading 拦截只保留翻页语义），在途旧响应由代际校验丢弃；入口处先让全库预取避让
     * （刷新首屏与预取抢 NAS 带宽是刷新慢的叠加因素）。
     */
    fun refresh() {
        prefetchThrottle.pauseForForegroundRefresh()
        val current = _uiState.value
        _uiState.value = current.copy(
            // 新一轮刷新受理即撤上一轮的「已是最新」提示（本轮结果落地后按新对比重判）
            infoMessage = null,
            recommend = if (current.currentTab == HomeTab.RECOMMEND) {
                current.recommend
            } else {
                // exhausted 一并复位：另两 tab 标脏重拉时换轮穷尽标记随缓存清空失去意义
                current.recommend.copy(
                    pulled = emptyList(),
                    revealed = 0,
                    loaded = false,
                    exhausted = false,
                )
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
     * TTL 兜底（2026-10-01 跳过门 TTL 化）：指纹只感知本进程本端变更，跨端（Web/另一设备）
     * 改动指纹永不变化——指纹未变但距上次成功拉取超过 [STALE_AFTER_MS] 时不再跳过，
     * 重拉当前 tab 自愈（跨端陈旧至多 5 分钟 + 一次返回即纠正；TanStack Query
     * refetchOnWindowFocus 同款 staleTime 语义，与 FavoriteViewModel.onResumed 同口径）。
     */
    fun onHomeResumed() {
        val snapshot = likeMutationTracker.fingerprint()
        val last = lastLikeFingerprint
        if (last == null) {
            // 首次回调：只采纳基线（进页不误刷）
            lastLikeFingerprint = snapshot
            return
        }
        if (last == snapshot && likeMutationTracker.isListFetchFresh()) {
            // 指纹无变化且未过 staleTime 上界（纯浏览返回）：不重拉；超过 STALE_AFTER_MS
            // 则放行到下方重拉（跨端改动无事件可感知，TTL 兜底见 KDoc 与 LikeMutationTracker）
            lastLikeFingerprint = snapshot
            return
        }
        // 在途（首载/上次重拉在跑）不叠加：不采纳指纹，下次 resume 重试——
        // FavoriteViewModel.onResumed 同口径（2026-09-20 全库审查对齐：此前先采纳
        // 指纹再进 load*，而 load* 的 isLoading 防重会静默吞掉本次重拉，指纹已
        // 消费、点赞触发的重排丢失到下次点赞才补）。
        val tabLoading = when (_uiState.value.currentTab) {
            HomeTab.RECOMMEND -> _uiState.value.recommend.isLoading
            HomeTab.COS -> _uiState.value.cos.isLoading
            HomeTab.RANK -> _uiState.value.rank.isLoading
        }
        if (tabLoading) return
        lastLikeFingerprint = snapshot
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

    // ---------- 万能筛选面板（任务Y Y4b） ----------
    // 五方法（open/dismiss/update/reset/apply）语义与 AlbumViewModel 面板方法组逐款同范式
    // （打开=拷贝已应用值、面板内只改草稿、重置=回默认+立即应用+关面板三合一、关闭=丢弃）。
    // 平行实现而非共享基类的裁决依据：已应用态模型不同——相册页写 AlbumFilterState 四维刷新链
    // （独立 panelState flow），首页写 [HomeUiState.cosFilter] 并只重拉 COS 流（面板态并入
    // HomeUiState 聚合流）；两者共用 core/model 单源的 FilterPanelUiState/PanelFeedback 类型与
    // AssetQuery.withPanelDraft 查询展开，防漂移靠单源类型不靠基类。

    /**
     * 打开面板：拷贝当前已应用面板值为草稿（编辑态语义，旧版 show(current) 同口径）
     * 并拉取标签候选流。关闭不回写——丢弃草稿。
     */
    fun openFilterSheet() {
        _uiState.value = _uiState.value.copy(
            filterPanel = _uiState.value.filterPanel.copy(
                visible = true,
                draft = _uiState.value.cosFilter,
                message = null, // 重开面板清上一轮操作反馈
            ),
        )
        loadTags()
    }

    /** 下滑/点外部关闭：丢弃草稿（BottomSheet 常规语义），反馈一并清空 */
    fun dismissFilterSheet() {
        _uiState.value = _uiState.value.copy(
            filterPanel = _uiState.value.filterPanel.copy(visible = false, message = null),
        )
    }

    /** 面板内每次点选：只改草稿，不触发刷新 */
    fun updatePanelDraft(draft: AlbumPanelDraft) {
        _uiState.value = _uiState.value.copy(filterPanel = _uiState.value.filterPanel.copy(draft = draft))
    }

    /**
     * 「重置」：草稿回默认值后走 [applyPanelDraft] 同一条应用链——写已应用态 + COS 重拉 + 关面板。
     * 旧版口径即三合一（旧仓库 MediaFilterSheet.kt L266-269 实读，AlbumViewModel.resetPanelDraft
     * 同款注释互指）。
     */
    fun resetPanelDraft() {
        updatePanelDraft(AlbumPanelDraft())
        applyPanelDraft()
    }

    /**
     * 「应用筛选」：草稿写入已应用态 [HomeUiState.cosFilter] + 递增 COS 代际（在途旧筛选响应
     * 作废）+ COS 流重拉第一页 + 关面板。不论当前 tab 都重拉（入口恒显）：筛选只作用于 COS
     * tab 数据，其余 tab 应用后切入 COS 时缓存已带上新筛选。
     */
    fun applyPanelDraft() {
        val draft = _uiState.value.filterPanel.draft
        cosGeneration += 1
        _uiState.value = _uiState.value.copy(
            filterPanel = _uiState.value.filterPanel.copy(visible = false),
            cosFilter = draft,
        )
        loadCosPage(isInitial = true)
    }

    /**
     * 新建标签（面板「+ 添加标签」）：成功后刷新候选流；空名不发出请求。重名分流两道
     * （候选预查重 + 服务端 409 领域化兜底），语义与 AlbumViewModel.addTag 同款
     * （状态容器不同故平行实现，注释互指防漂移）。
     */
    fun addTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (_uiState.value.filterPanel.tags.any { it.name == trimmed }) {
            setPanelFeedback(PanelFeedback.TagExists(trimmed))
            return
        }
        viewModelScope.launch {
            runCatching { mediaRepository.createTag(trimmed) }
                .onSuccess {
                    setPanelFeedback(null)
                    loadTags()
                }
                .onFailure {
                    setPanelFeedback(
                        if (it is TagNameConflictException) PanelFeedback.TagExists(trimmed) else PanelFeedback.OpFailed,
                    )
                }
        }
    }

    /**
     * 删除标签（面板长按确认后）：服务端删除 + 候选流刷新；若删除的是草稿已选标签则一并从
     * 草稿 tagIds 移除（否则应用时会引用已不存在的 id）。失败给中性操作失败反馈。
     */
    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            runCatching { mediaRepository.deleteTag(tagId) }
                .onSuccess {
                    val draft = _uiState.value.filterPanel.draft
                    if (tagId in draft.tagIds) {
                        _uiState.value = _uiState.value.copy(
                            filterPanel = _uiState.value.filterPanel.copy(
                                draft = draft.copy(tagIds = draft.tagIds - tagId),
                            ),
                        )
                    }
                    setPanelFeedback(null)
                    loadTags()
                }
                .onFailure { setPanelFeedback(PanelFeedback.OpFailed) }
        }
    }

    private fun loadTags() {
        viewModelScope.launch {
            runCatching { mediaRepository.tags() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        filterPanel = _uiState.value.filterPanel.copy(tags = it),
                    )
                }
                .onFailure {
                    // 候选流失败属列表级加载失败，沿用列表加载文案（与面板操作失败语义分流，P2-1）
                    _uiState.value = _uiState.value.copy(errorMessage = LIST_LOAD_FAILED_MESSAGE)
                }
        }
    }

    /** 面板操作反馈落位（null=清除；操作成功即清除上一条反馈） */
    private fun setPanelFeedback(feedback: PanelFeedback?) {
        _uiState.value = _uiState.value.copy(filterPanel = _uiState.value.filterPanel.copy(message = feedback))
    }

    /**
     * 距底触发：推荐流揭示下一批 / 批次尽则换 seed 追加；COS 流拉下一页；排行一次拉满无操作。
     *
     * 哨兵抑制窗口（任务J J3a，台账 #35 清偿）：tab 切换后 [SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS]
     * 窗口内的回调一律丢弃——切换瞬间 RANK 周期行显隐使 pager 视口高度突变（如 RANK→推荐
     * 周期行移除、视口变高），布局回流把 lastVisible 抬过距底阈值（≥total-1-6）是布局噪声
     * 而非用户滚动意图，放行即误追加换 seed，违背「切 tab 不重拉」（小库一次全揭示时必现）。
     * 窗口只在 switchTab 刷新起点（selectPeriod 不动周期行高度，无需抑制）。时钟回拨判负不抑制。
     */
    fun onNearBottom() {
        val sinceSwitchMs = clockMs() - lastTabSwitchAtMs
        if (sinceSwitchMs in 0 until SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS) return
        when (_uiState.value.currentTab) {
            HomeTab.RECOMMEND -> {
                val s = _uiState.value.recommend
                // exhausted（问题A fresh==0 修复）：换轮穷尽后不再发请求——否则钉底哨兵每次
                // 重触发都白打一轮 4 请求（连续空轮已达上限=库已掏干，刷新换 seed 才有新序）
                if (s.isLoading || s.isRefreshing || s.exhausted) return
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
     * 刷新结果轻提示（内容未变才提示；语义见 [HomeUiState.infoMessage] KDoc）：
     * 只服务刷新路径——首载/翻页/筛选重载没有「和刷新前比」的语义。内容一致=
     * 短暂亮提示后自动消退；有变化=顺带清掉可能残留的旧提示。推荐流刷新不走
     * 此路径：刷新即换 seed 重排，顺序变化本身就是可见反馈。
     */
    private fun reportRefreshOutcome(oldIds: List<String>, newIds: List<String>) {
        if (oldIds != newIds) {
            _uiState.value = _uiState.value.copy(infoMessage = null)
            return
        }
        _uiState.value = _uiState.value.copy(infoMessage = LIST_UP_TO_DATE_MESSAGE)
        viewModelScope.launch {
            delay(INFO_HINT_AUTO_CLEAR_MS)
            if (_uiState.value.infoMessage == LIST_UP_TO_DATE_MESSAGE) {
                _uiState.value = _uiState.value.copy(infoMessage = null)
            }
        }
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
        // 防重（问题B 2026-09-28 收窄）：只拦首载/静默重试路径的在途重复触发；下拉刷新立即
        // 受理——在途旧响应由 recommendGeneration 作废，不再吞刷新手势（原「在途直接 return」
        // 是刷新响应慢主因之一：慢网下首拉在途时用户下拉毫无反馈直到旧请求收圈）
        if (!isRefresh && current.recommend.isLoading) return
        val gen = if (isRefresh) ++recommendGeneration else recommendGeneration
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
                if (gen != recommendGeneration) return@onSuccess // 旧代迟到响应（后续刷新已接管），丢弃
                // 自愈即清账：横幅清空（刷新失败亮过牌后重试/再刷成功不残留）+ 重试预算归零
                initialRetryAttempts.remove(HomeTab.RECOMMEND)
                _uiState.value = _uiState.value.copy(
                    errorMessage = null,
                    recommend = _uiState.value.recommend.copy(
                        pulled = items,
                        revealed = minOf(items.size, RecommendPaging.BATCH_SIZE),
                        isLoading = false,
                        isRefreshing = false,
                        loaded = true,
                        // 新一轮 200 条整体替换：换轮穷尽标记复位（新一轮重新计数）
                        exhausted = false,
                        // 问题A：成功结束 bump 哨兵重评估信号（KDoc 见 RecommendState.reloadTick）
                        reloadTick = _uiState.value.recommend.reloadTick + 1,
                    ),
                )
                // 列表成功落地：打点跳过门 TTL 计时起点（2026-10-01 跳过门 TTL 化，失败不打点；
                // 三 tab 同一首页跳过门，任一落地即刷新计时——语义见 LikeMutationTracker/STALE_AFTER_MS）
                likeMutationTracker.noteListFetchCompleted()
            }.onFailure { error ->
                if (gen != recommendGeneration) return@onFailure // 旧代失败不污染新轮
                val retryScheduled = isInitial && !isRefresh &&
                    !_uiState.value.recommend.loaded &&
                    scheduleInitialRetry(HomeTab.RECOMMEND)
                _uiState.value = _uiState.value.copy(
                    // 静默重试在途：不亮横幅保持加载/空白态（null 顺带清可能残留的旧牌）；
                    // 预算耗尽或用户主动路径失败：立即亮真错误
                    errorMessage = if (retryScheduled) null else LIST_LOAD_FAILED_MESSAGE,
                    recommend = _uiState.value.recommend.copy(isLoading = false, isRefreshing = false),
                )
            }
        }
    }

    /**
     * 批次尽触底：换 seed 追加一轮（无限流；追加前去重，渲染顺序 = 服务端给出的打散序）。
     *
     * fresh==0 续轮（问题A 死锁断路点①修复，2026-09-28）：此前一轮 200 条对已拉取清单全部
     * 去重后（fresh 为空）不更新任何状态——pulled/revealed 不变 → 网格 totalCount 不变 →
     * QimengMediaGrid 触底哨兵 LaunchedEffect(shouldLoadMore, totalCount) 两个 key 均无变化
     * 永不重触发，钉底用户永久死锁（2026-09-15 的修复只治了 revealed 不推进，没治 fresh==0）。
     * 现改为：本轮全空则**不落地、自动换下一个 seed 续拉**，连续空轮达 [MAX_EMPTY_SEED_ROUNDS]
     * 上限仍空则置 exhausted 到底（有限库深翻页掏干即停，防无限续拉；无限库正常轮次必出
     * 新条目不会触顶）。成功落地与穷尽停手两条终路都结束加载，成功路径 bump reloadTick
     * 让哨兵立即复评——条数不变的终态（穷尽）由 onNearBottom 的 exhausted 拦截兜住不发新请求。
     */
    private fun appendNextSeedRound() {
        val current = _uiState.value
        if (current.recommend.isLoading || current.recommend.isRefreshing) return // 防重（onNearBottom 已挡，直调双保险）
        val gen = recommendGeneration // 快照：续轮期间用户下拉刷新即作废本整轮（含已续到的空轮）
        var seed = current.recommend.seed + 1
        _uiState.value = current.copy(
            recommend = current.recommend.copy(isLoading = true, seed = seed),
        )
        viewModelScope.launch {
            var emptyRounds = 0
            while (true) {
                val items = runCatching {
                    mediaRepository.recommendations(seed, RecommendPaging.PULL_LIMIT, null)
                }.getOrElse {
                    if (gen != recommendGeneration) return@launch // 旧轮失败（刷新已接管状态），静默弃局
                    // 追加失败维持既有口径：亮横幅停手，不自动重试（自动重试范围=cursor 翻页路径，
                    // 见 APPEND_RETRY_DELAYS_MS KDoc）；钉底用户经「上滚 6 项再回滚」哨兵重触发
                    // 即可手动重试。不 bump reloadTick：失败若 bump，哨兵立即重触发本轮=持续故障
                    // 下无限锤击（问题A 修复刻意避开的坑）
                    _uiState.value = _uiState.value.copy(
                        errorMessage = LIST_LOAD_FAILED_MESSAGE,
                        recommend = _uiState.value.recommend.copy(isLoading = false),
                    )
                    return@launch
                }
                if (gen != recommendGeneration) return@launch // 旧轮迟到响应（含成功），丢弃
                val seen = _uiState.value.recommend.pulled.map { it.id }.toHashSet()
                val fresh = items.filterNot { seen.contains(it.id) }
                if (fresh.isNotEmpty()) {
                    // 2026-09-15 用户反馈「下滑到底无法加载新的」修复：追加后必须同步推进
                    // 一批揭示——revealed 不动时网格 take(revealed) 的 totalCount 不变，
                    // 哨兵 key 无变化不再触发，用户钉在底部即永久死锁（新条目永远不显示）。
                    // 推进 +BATCH_SIZE 让 totalCount 变化、哨兵恢复工作，且用户视口底部
                    // 立即出现新条目（视觉可感知「加载到了」）。
                    val oldRevealed = _uiState.value.recommend.revealed
                    val newPulled = _uiState.value.recommend.pulled + fresh
                    _uiState.value = _uiState.value.copy(
                        recommend = _uiState.value.recommend.copy(
                            pulled = newPulled,
                            revealed = minOf(newPulled.size, oldRevealed + RecommendPaging.BATCH_SIZE),
                            isLoading = false,
                            // 问题A：成功结束 bump 哨兵重评估信号（KDoc 见 RecommendState.reloadTick）
                            reloadTick = _uiState.value.recommend.reloadTick + 1,
                        ),
                    )
                    return@launch
                }
                // 本轮全空：连续空轮计数达上限即穷尽停手（库已掏干），否则换下一 seed 续拉
                emptyRounds++
                if (emptyRounds >= MAX_EMPTY_SEED_ROUNDS) {
                    _uiState.value = _uiState.value.copy(
                        recommend = _uiState.value.recommend.copy(
                            isLoading = false,
                            exhausted = true,
                        ),
                    )
                    return@launch
                }
                seed++
            }
        }
    }

    private fun loadCosPage(isInitial: Boolean, isRefresh: Boolean = false) {
        val current = _uiState.value
        // 防重语义（问题B 2026-09-28 收窄）：只拦**翻页**（!isInitial && !isRefresh）在途时的
        // 重复触发；下拉刷新改为立即受理（受理即递增代际作废在途旧响应，不再吞刷新手势）；
        // 筛选重载与 tab 首次揭示照旧不受 isLoading 拦截——在途的是旧代请求，其响应会被
        // 代际校验丢弃；同代重复触发会多发一次请求但整页等价替换，无数据损害（Y7 审查 P2-1 记档）
        if (!isInitial && !isRefresh && current.cos.isLoading) return
        val gen = if (isRefresh) ++cosGeneration else cosGeneration
        val isPagination = !isInitial && !isRefresh // 翻页形态：失败启用自动重试（范围见 APPEND_RETRY_DELAYS_MS KDoc）
        _uiState.value = current.copy(cos = current.cos.copy(isLoading = true, isRefreshing = isRefresh))
        viewModelScope.launch {
            var retryAttempt = 0
            while (true) {
                val page = runCatching {
                    mediaRepository.assets(
                        // 任务Y Y4b：排序/顺位/观看/点击/大小/时间/标签由已应用面板草稿经
                        // AssetQuery.withPanelDraft 展开（core/model 单源；默认草稿=协议缺省不传，
                        // Y4b 前行为不变）。cursor/limit/cosOnly 为本调用方持有字段，覆写不触碰。
                        AssetQuery(
                            cursor = if (isRefresh || isInitial) null else _uiState.value.cos.nextCursor,
                            limit = COS_PAGE_SIZE,
                            cosOnly = true,
                        ).withPanelDraft(_uiState.value.cosFilter),
                    )
                }.getOrElse {
                    if (gen != cosGeneration) return@launch // 旧代失败不污染新筛选态
                    // 翻页失败自动重试（问题A「翻页失败即卡死」，2026-09-28）：钉底用户等在底部，
                    // 不重试就必须上滚 6 项再回滚才能再触发。重试期间 isLoading 保持 true——
                    // 不阻塞其它操作（刷新/筛选/首载路径均已绕过 isLoading 拦截），翻页防重
                    // 顺延到重试结束。首载/刷新失败不重试（各有静默重试/立即反馈语义）
                    if (isPagination && retryAttempt < APPEND_RETRY_DELAYS_MS.size) {
                        delay(APPEND_RETRY_DELAYS_MS[retryAttempt])
                        retryAttempt++
                        if (gen != cosGeneration) return@launch // 退避期间刷新/筛选已接管，弃残局
                        continue
                    }
                    val retryScheduled = isInitial && !isRefresh &&
                        !_uiState.value.cos.loaded &&
                        scheduleInitialRetry(HomeTab.COS)
                    _uiState.value = _uiState.value.copy(
                        // 静默重试在途不亮牌 / 预算耗尽或用户主动路径失败立即亮（口径同 loadRecommend）
                        errorMessage = if (retryScheduled) null else LIST_LOAD_FAILED_MESSAGE,
                        cos = _uiState.value.cos.copy(isLoading = false, isRefreshing = false),
                    )
                    return@launch
                }
                if (gen != cosGeneration) return@launch // 旧代迟到响应，丢弃
                // 自愈即清账：横幅清空 + 重试预算归零（同 loadRecommend onSuccess 注释）
                initialRetryAttempts.remove(HomeTab.COS)
                // 刷新同内容轻提示：旧清单 id 快照须在整体替换前抓取（下方 copy 即覆盖）
                val oldIds = if (isRefresh) _uiState.value.cos.items.map { it.id } else null
                _uiState.value = _uiState.value.copy(
                    errorMessage = null,
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
                        // 问题A：成功结束 bump 哨兵重评估信号（KDoc 见 CosState.reloadTick）
                        reloadTick = _uiState.value.cos.reloadTick + 1,
                    ),
                )
                if (oldIds != null) reportRefreshOutcome(oldIds, page.items.map { it.id })
                // 列表成功落地：打点跳过门 TTL 计时起点（loadRecommend 同款注释，失败不打点）
                likeMutationTracker.noteListFetchCompleted()
                return@launch
            }
        }
    }

    private fun loadRank(isInitial: Boolean, isRefresh: Boolean = false) {
        val current = _uiState.value
        // 问题B（2026-09-28）：下拉刷新立即受理——受理即递增代际作废在途旧周期响应，原「刷新
        // 在途被 isLoading 拦截丢弃」退役；首载/切周期路径本就不受 isLoading 拦截（代际防乱序
        // 兜底，同代重复触发整页等价替换无数据损害），故本函数不再有 isLoading 拦截
        val gen = if (isRefresh) ++rankGeneration else rankGeneration
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
                // 自愈即清账：横幅清空 + 重试预算归零（同 loadRecommend onSuccess 注释）
                initialRetryAttempts.remove(HomeTab.RANK)
                // 刷新同内容轻提示：旧清单 id 快照须在整体替换前抓取（口径同 loadCosPage）
                val oldIds = if (isRefresh) _uiState.value.rank.items.map { it.id } else null
                _uiState.value = _uiState.value.copy(
                    errorMessage = null,
                    rank = _uiState.value.rank.copy(
                        items = items,
                        isLoading = false,
                        isRefreshing = false,
                        loaded = true,
                    ),
                )
                if (oldIds != null) reportRefreshOutcome(oldIds, items.map { it.id })
                // 列表成功落地：打点跳过门 TTL 计时起点（loadRecommend 同款注释，失败不打点）
                likeMutationTracker.noteListFetchCompleted()
            }.onFailure {
                if (gen != rankGeneration) return@onFailure // 旧代失败不污染新周期
                val retryScheduled = isInitial && !isRefresh &&
                    !_uiState.value.rank.loaded &&
                    scheduleInitialRetry(HomeTab.RANK)
                _uiState.value = _uiState.value.copy(
                    // 静默重试在途不亮牌 / 预算耗尽或用户主动路径失败立即亮（口径同 loadRecommend）
                    errorMessage = if (retryScheduled) null else LIST_LOAD_FAILED_MESSAGE,
                    rank = _uiState.value.rank.copy(isLoading = false, isRefreshing = false),
                )
            }
        }
    }

    companion object {
        /**
         * tab 切换后距底哨兵抑制窗口（任务J J3a，台账 #35）：500ms 覆盖 pager settle 动画
         * （HorizontalPager snap spring ~300ms 量级）+ 周期行移除引发的 1~2 帧网格重布局回流；
         * 代价是切换后 500ms 内用户真实滚到底的触底被吞一次（滚停后哨兵重触发自愈），
         * 取更长时间会放大该代价。与壳层双击回顶窗口（400ms）同量级。
         */
        const val SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS = 500L

        /**
         * 首屏失败重试：梯度退避参数（2026-09-18 演进，原固定 1.5s 间隔退役）。
         * 为什么 300ms 起步 2^n 爬升 2000ms 封顶：「服务端未就绪」主场景已由 init 的
         * 就绪探针覆盖，退避只兜瞬态失败——固定 1.5s 对真故障自愈太慢（最坏全窗口白等
         * 十几秒），300ms 起步让偶发瞬态一两次内自愈、持续故障指数退到 2s 不刷爆回环。
         * 上限 [INITIAL_RETRY_MAX] 保持 10（总窗口 ≈300+600+1200+2000×7 ≈ 16s，与原
         * 10×1.5s=15s 同量级，冷启动冗余足够）。
         */
        const val INITIAL_RETRY_BASE_DELAY_MS = 300L
        const val INITIAL_RETRY_MAX_DELAY_MS = 2_000L
        const val INITIAL_RETRY_MAX = 10

        /** COS 流分页大小（协议 /assets 缺省 60；同真 cosOnly 优先） */
        const val COS_PAGE_SIZE = 60

        /**
         * 「已是最新」轻提示自动消退窗口（HomeUiState.infoMessage，2026-09-29「COS 下拉
         * 无效」反馈修复）：取 2.5s——短于一次用户注意力转移（对齐双击回顶防抖/哨兵抑制
         * 窗的亚秒量级之上、错误横幅的常驻语义之下），足够读到又不遮挡后续操作；不做
         * 点击消退（提示是成功路径告知，无操作语义）。
         */
        const val INFO_HINT_AUTO_CLEAR_MS = 2_500L

        /**
         * 推荐流换 seed 追加的连续空轮上限（问题A fresh==0 死锁修复，2026-09-28）：
         * 追加轮 200 条对已拉取清单全去重（fresh==0）时自动换下一 seed 续拉，连续
         * [MAX_EMPTY_SEED_ROUNDS] 轮全空即置 exhausted 到底。取 3：无限库正常轮次必出
         * 新条目不触顶；有限库深翻页掏干后 3 轮空转（≈3 个请求）即可判定穷尽，多试只会
         * 白打请求。上限防「无限续拉」：不加此限，钉底哨兵与续轮循环会无限互相喂招。
         */
        const val MAX_EMPTY_SEED_ROUNDS = 3

        /** 排行榜单次拉取量（协议 /rankings limit 缺省 50，榜单展示规模足够） */
        const val RANK_PULL_LIMIT = 50

        /** 排行榜首拉偏移 */
        const val RANK_OFFSET_INITIAL = 0

        // 失败文案不再本地定义：共享常量 core/model LIST_LOAD_FAILED_MESSAGE（下拉刷新
        // 列表通用款，2026-09-07 审查 P3 与相册/收藏/历史同款单源）。COS_PAGE_SIZE 为
        // COS 流专用语义命名，与通用列表分页非同款，保留本地。
    }
}
