package media.qimeng.app.feature.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.FavoriteFingerprint
import media.qimeng.app.core.data.repository.FavoriteMutationTracker
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchThrottle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.APPEND_RETRY_DELAYS_MS
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.PARTITION_KEY_ALL
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.withOtherBucketLast
import media.qimeng.app.feature.favorite.R

/**
 * 收藏页状态：四维同相册口径 + 基础约束 favorite=true & sort=favoriteAt 降序
 * （Web MinePage.tsx:82 已核的同款口径；收藏时间倒序）。
 */
data class FavoriteUiState(
    val filter: AlbumFilterState = AlbumFilterState(),
    val activeDim: AlbumDim = AlbumDim.PARTITION,
    val partitionOptions: List<FacetOption> = emptyList(),
    val authorOptions: List<FacetOption> = emptyList(),
    val characterOptions: List<FacetOption> = emptyList(),
    val typeOptions: List<FacetOption> = emptyList(),
    val totalForAllPill: Int? = null,
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** 列表加载成功结束信号（问题A 哨兵哑火修复，2026-09-28；语义见 AlbumViewModel.AlbumUiState.reloadTick） */
    val reloadTick: Int = 0,
) {
    /**
     * 空态文案资源（旧仓库 FavoriteFragment.kt L370-380 逐字双分支）：COS 分区单行
     * 「没有COS收藏」；其余双行「还没有收藏\n在详情页点击收藏按钮添加」（L376）。
     * VM 只发资源 id 结构化语义，文案本体落地 strings.xml（§5.1 文案全 strings），
     * 分支选择由单测锁定。
     */
    val emptyTextRes: Int
        get() = if (filter.partition == Zone.COS) {
            R.string.favorite_empty_cos
        } else {
            R.string.favorite_empty_default
        }
}

/** 收藏页 ViewModel：相册同款四维状态机，facets 加 favorite 子集约束（收藏计数只数收藏） */
@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    private val batchIndex: MediaBatchIndex,
    // 本地收藏变更指纹（任务V V1，2026-09-10 返回刷新缺陷修复）：详情 toggleFavorite 成功处
    // 上报，此处 ON_RESUME 对比指纹变化才重拉——纯浏览返回零网络零重组（tracker KDoc 口径）；
    // 2026-10-01 门收敛（ADR-0029）：isListFetchFresh 判定 = TTL 半边（STALE_AFTER_MS，SSE
    // 断线/离线窗口兜底）+ SSE 信号半边（DataFreshnessSignal，favorite.changed/library.changed
    // 计数采样对比，跨端变更秒级感知）——三层语义与门关联关系见 tracker KDoc 详述
    private val favoriteMutationTracker: FavoriteMutationTracker,
    // 预取避让（问题B 下拉刷新响应慢修复，2026-09-28）：下拉刷新开始时让全库缩略图预取
    // 暂停抢带宽（窄接口，语义见 ThumbnailPrefetchThrottle KDoc）
    private val prefetchThrottle: ThumbnailPrefetchThrottle,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoriteUiState())
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()

    /**
     * 筛选代际号：筛选变化即递增。弱网下仓库响应可能乱序归位（与相册页同族缺陷，
     * 2026-09-06 审查清偿补齐），请求发起时快照代际、响应落地前校验——旧代响应
     * （含失败）一律丢弃，不再覆盖新筛选态。读写都在 Main（viewModelScope 与
     * 状态更新同线程），无需原子类。
     */
    private var filterGeneration = 0

    /**
     * 网格列数（双指缩放 2~5 列，GUIDE_UI §公共UI工具 L300 + §全部页 L149）：**共用全部页档**——
     * 旧版 v1.15 持久化语义「收藏/浏览历史/作者文件/全部→updateGridColumnsAll」，此处纯复用既有
     * [GridPrefsRepository.albumColumns]（键 grid_columns_all）读写同档，core:data 零改动。
     * 进页读档为初始值，双指缩放手势结束持久化回写同档。
     */
    val gridColumns: StateFlow<Int> = gridPrefs.albumColumns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DataStoreGridPrefsRepository.DEFAULT_ALBUM_COLUMNS,
        )

    /**
     * 双指缩放期间的瞬时列数（null=无进行中手势，展示持久化值）。
     * 手势逐事件步进只改内存、不落盘——DataStore 写有延迟，逐帧写会卡顿且无意义；
     * 手势结束由 [commitPinchColumns] 统一持久化一次。（镜像 AlbumViewModel 同名接线，M4-2A-B2）
     */
    private val _pinchColumns = MutableStateFlow<Int?>(null)
    val pinchColumns: StateFlow<Int?> = _pinchColumns.asStateFlow()

    /** 双指缩放步进（delta=±1：放大减列、缩小加列，语义见 QimengGridPinchGesture），clamp 2..5 */
    fun adjustColumnsLive(delta: Int) {
        val current = _pinchColumns.value ?: gridColumns.value
        _pinchColumns.value = (current + delta).coerceIn(
            DataStoreGridPrefsRepository.MIN_ALBUM_COLUMNS,
            DataStoreGridPrefsRepository.MAX_ALBUM_COLUMNS,
        )
    }

    /** 手势结束：缩放结果持久化一次（复用 [GridPrefsRepository.setAlbumColumns]，仓库内部再 clamp 2..5） */
    fun commitPinchColumns() {
        val target = _pinchColumns.value ?: return
        _pinchColumns.value = null
        if (target != gridColumns.value) {
            viewModelScope.launch { gridPrefs.setAlbumColumns(target) }
        }
    }

    /**
     * 返回/回前台（ON_RESUME，由 FavoriteScreen 生命周期观测驱动）：收藏变更指纹
     * （[FavoriteMutationTracker]，任务V V1）与上次留存不一致时才重拉——详情页
     * toggleFavorite 后返回列表即反映（GUIDE_UI §收藏页 L410 的刷新语义收窄为「有变更才刷」；
     * 用户 2026-09-10 拍板「返回时不要刷新界面…应该是原来的不变」：此前无条件重拉使
     * 返回共享元素 morph（缩略图飞回）期间列表整体重显 + 刷新指示器闪一轮，缺陷根因）。
     * - 首次回调只采纳基线（进页不误刷）；
     * - 无变更不重拉 =「纯浏览返回保持原样」零网络零重组；
     * - 跨端感知与兜底（2026-10-01 ADR-0029 门收敛）：isListFetchFresh = TTL 半边
     *   （[STALE_AFTER_MS]，SSE 断线/离线窗口兜底）+ SSE 信号半边（DataFreshnessSignal，
     *   favorite.changed/library.changed 计数较上次拉取采样增长即放行，跨端收藏/资产集合
     *   变更秒级感知）——指纹未变但任一半边不满足时静默重拉一次自愈；历史口径：SSE 曾无
     *   favorite 事件、跨端陈旧至多 5 分钟自愈，ADR-0029 服务端补发后收敛为三层；
     * - 重拉走静默路径（isRefresh=false，不置 isRefreshing）：morph 窗口内指示器不闪，
     *   数据仍整组替换、morph 结束时列表已新。
     */
    private var lastFavoriteFingerprint: FavoriteFingerprint? = null

    fun onResumed() {
        val snapshot = favoriteMutationTracker.fingerprint()
        val last = lastFavoriteFingerprint
        if (last == null) {
            // 首次回调：只采纳基线（进页不误刷）
            lastFavoriteFingerprint = snapshot
            return
        }
        if (last == snapshot && favoriteMutationTracker.isListFetchFresh()) {
            // 指纹无变化且未过 staleTime 上界（纯浏览返回）：不重拉——零网络零重组；
            // 超过 STALE_AFTER_MS 则放行到下方重拉（跨端改动无事件可感知，TTL 兜底见 KDoc）
            lastFavoriteFingerprint = snapshot
            return
        }
        if (_uiState.value.isLoading) return // 在途（首载/上次重拉/筛选）不叠加：不采纳指纹，下次 resume 重试
        lastFavoriteFingerprint = snapshot
        reloadAll(isRefresh = false) // 静默重拉：不置 isRefreshing，morph 期间指示器不闪（见 KDoc）
    }

    init {
        reloadAll()
    }

    fun selectDim(dim: AlbumDim) {
        _uiState.value = _uiState.value.copy(
            activeDim = dim,
            filter = _uiState.value.filter.copy(expanded = true), // 切维度默认展开（B8）
        )
    }

    /**
     * 维度芯片点击入口（M4-2A-B5：芯片行随头部重排由页面直接接线，交互语义收进状态机——
     * 铁律 7「组件禁业务规则」，镜像 AlbumViewModel 同名入口；旧仓库
     * FavoriteFragment.setViewMode 内嵌同款规则）：
     * 点已激活维=切换展开/收起，点其他维=切维并展开。进页默认收起
     * （用户 2026-09-07 覆盖 B8；切维仍展开，旧仓库实录四 Fragment 齐证）。
     */
    fun onDimChipClicked(dim: AlbumDim) {
        if (_uiState.value.activeDim == dim) toggleExpanded() else selectDim(dim)
    }

    fun toggleExpanded() {
        val filter = _uiState.value.filter
        _uiState.value = _uiState.value.copy(filter = filter.copy(expanded = !filter.expanded))
    }

    fun selectPartition(zone: Zone) {
        applyFilter(AlbumFilter.selectPartition(_uiState.value.filter, zone))
    }

    fun selectAuthor(option: FacetOption?) {
        applyFilter(AlbumFilter.selectAuthor(_uiState.value.filter, option))
    }

    fun selectCharacter(option: FacetOption?) {
        applyFilter(AlbumFilter.selectCharacter(_uiState.value.filter, option))
    }

    fun selectMediaType(kind: MediaKind?) {
        applyFilter(AlbumFilter.selectMediaType(_uiState.value.filter, kind))
    }

    fun refresh() {
        // 预取避让（问题B 2026-09-28）：刷新首屏与登录后全库预取抢 NAS 带宽，入口先让路
        prefetchThrottle.pauseForForegroundRefresh()
        reloadAll(isRefresh = true)
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(cursor = state.nextCursor, append = true)
    }

    /**
     * 进详情前的批次上下文写入（2026-09-09 拍板：收藏/历史对齐旧版补基建，首页同款机制）：
     * 「已加载 = 当前显示清单」口径——收藏页 items 即整页显示，快照式整体替换
     * [MediaBatchIndex.ids]，详情页据此得「i / N」序号与滑动切换（调用点在 FavoriteScreen
     * 卡片点击，先写批次再交壳层导航）。
     */
    fun enterDetail(assetId: String) {
        batchIndex.ids = _uiState.value.items.map { it.id }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun applyFilter(filter: AlbumFilterState) {
        filterGeneration += 1
        _uiState.value = _uiState.value.copy(filter = filter)
        reloadAll()
    }

    private fun reloadAll(isRefresh: Boolean = false) {
        loadItems(cursor = null, append = false, isRefresh = isRefresh)
        loadFacets()
    }

    private fun loadItems(cursor: String?, append: Boolean, isRefresh: Boolean = false) {
        val state = _uiState.value
        // 防重语义（问题B 2026-09-28 收窄）：只拦**翻页**在途时的重复触发；下拉刷新改为立即
        // 受理（受理即递增代际作废在途旧响应，不再吞刷新手势）；筛选重载照旧不受 isLoading
        // 拦截（在途的是旧代请求，其响应会被代际校验丢弃）——AlbumViewModel.loadItems 同范式
        if (append && state.isLoading) return
        if (isRefresh) filterGeneration += 1
        val gen = filterGeneration
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            var retryAttempt = 0
            while (true) {
                val page = runCatching {
                    val base = AlbumFilter.toAssetQuery(state.filter, limit = LIST_PAGE_SIZE, cursor = cursor)
                    // 收藏固定口径：favorite=true + 收藏时间倒序（sort=favoriteAt 仅在收藏语义成立）
                    mediaRepository.assets(
                        base.copy(favorite = true, sort = AssetSort.FAVORITE_AT, order = SortOrder.DESC),
                    )
                }.getOrElse {
                    if (gen != filterGeneration) return@launch // 旧代失败不污染新筛选态
                    // 翻页失败自动重试（问题A 2026-09-28，范围/取舍见 APPEND_RETRY_DELAYS_MS KDoc；
                    // 重试期间 isLoading 保持 true 不阻塞刷新/筛选，同 AlbumViewModel.loadItems）
                    if (append && retryAttempt < APPEND_RETRY_DELAYS_MS.size) {
                        delay(APPEND_RETRY_DELAYS_MS[retryAttempt])
                        retryAttempt++
                        if (gen != filterGeneration) return@launch // 退避期间刷新/筛选已接管，弃残局
                        continue
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = LIST_LOAD_FAILED_MESSAGE,
                    )
                    return@launch
                }
                if (gen != filterGeneration) return@launch // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    isRefreshing = false,
                    // 问题A：成功结束 bump 哨兵重评估信号（KDoc 见 FavoriteUiState.reloadTick）
                    reloadTick = _uiState.value.reloadTick + 1,
                )
                // 列表成功落地：打点跳过门 TTL 计时起点（2026-10-01 跳过门 TTL 化；含翻页
                // 追加，失败不打点——语义见 FavoriteMutationTracker/STALE_AFTER_MS KDoc）
                favoriteMutationTracker.noteListFetchCompleted()
                return@launch
            }
        }
    }

    private fun loadFacets() {
        val filter = _uiState.value.filter
        val gen = filterGeneration
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val partition = async { mediaRepository.facets(AlbumFilter.partitionFacetsQuery(filter, favorite = true)) }
                    val author = async { mediaRepository.facets(AlbumFilter.authorFacetsQuery(filter, favorite = true)) }
                    val character = async { mediaRepository.facets(AlbumFilter.characterFacetsQuery(filter, favorite = true)) }
                    val type = async { mediaRepository.facets(AlbumFilter.typeFacetsQuery(filter, favorite = true)) }
                    FacetsResult(
                        partitions = partition.await().partitions,
                        authors = author.await().authors,
                        characters = character.await().characters,
                        types = type.await().types,
                    )
                }
            }.onSuccess { facets ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    partitionOptions = facets.partitions,
                    authorOptions = facets.authors.withOtherBucketLast(),
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                    totalForAllPill = facets.partitions.firstOrNull { it.key == PARTITION_KEY_ALL }?.fileCount,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新筛选态
                _uiState.value = _uiState.value.copy(errorMessage = LIST_LOAD_FAILED_MESSAGE)
            }
        }
    }
    // 分页大小/全部桶 key/失败文案：共享常量收敛至 core/model ListQueryDefaults.kt
    // （2026-09-07 审查 P3，与相册/历史/搜索同款单源）。
}
