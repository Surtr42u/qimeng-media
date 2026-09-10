package media.qimeng.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.HistoryRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.HistoryQuery
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.PARTITION_KEY_ALL
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.zoneToHistoryParams
import media.qimeng.app.core.model.withOtherBucketLast
import media.qimeng.app.feature.history.R

/**
 * 历史页维度子集：分区/作品/角色/类型（GUIDE_UI §浏览历史 L386 芯片栏逐字顺序）。
 * 「作品」行 = 出处分组多选（旧版「作品模式：按出处分组……支持多选作品筛选」；
 * N2 协议批 #30 起 /history 有 source 数组位）。COS 作者桶不作历史页作品行候选
 * （/history authorId 为单值位且旧版历史页作品模式只有出处分组——候选侧再按 kind 收口）。
 */
private val HISTORY_DIMS = listOf(AlbumDim.PARTITION, AlbumDim.AUTHOR, AlbumDim.CHARACTER, AlbumDim.TYPE)

data class HistoryUiState(
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
) {
    /**
     * 空态文案资源（规格书 §浏览历史，M4-2 已对齐原样随迁 strings.xml——§5.1 文案全 strings）：
     * COS 分区「没有COS浏览记录」/其余「没有浏览记录」。分支选择由单测锁定。
     */
    val emptyTextRes: Int
        get() = if (filter.partition == Zone.COS) {
            R.string.history_empty_cos
        } else {
            R.string.history_empty_default
        }

    /** 历史页维度子集（分区/作品/角色/类型，GUIDE_UI §浏览历史 L386 顺序） */
    val dims: List<AlbumDim> = HISTORY_DIMS
}

/**
 * 历史页 ViewModel：GET /history（cursor 分页；每资产一条 lastViewedAt 倒序，服务端口径）。
 * 无清空按钮（拍板 2B：协议无 DELETE /history）。
 *
 * 任务V V1（2026-09-10）：**无详情返回自动重拉**——此前 ON_RESUME 无条件 refresh() 补偿
 * 服务端化后丢失的 Room Flow 自动重排（详情浏览上报 lastViewedAt 已变、返回重排），但
 * 无条件重拉使返回共享元素 morph（缩略图飞回）期间列表整体重显（items 整组替换+指示器
 * 闪一轮），用户拍板「返回时不要刷新界面…应该是原来的不变」，该机制整体移除（Screen 侧
 * ON_RESUME 观测同删）。取舍：刚浏览条目的 lastViewedAt 排序滞后（重排不可见），靠
 * 下拉刷新/下次冷启动收敛；收藏页保留刷新语义但收窄为变更指纹门控（见
 * FavoriteMutationTracker），历史无本地变更上报点（浏览上报在详情侧、非用户显式操作），
 * 依用户原话直接不刷。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    private val batchIndex: MediaBatchIndex,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

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
     * BrowseHistoryFragment.setViewMode 内嵌同款规则）：
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

    fun collapsePills() {
        _uiState.value = _uiState.value.copy(filter = _uiState.value.filter.copy(expanded = false))
    }

    fun selectPartition(zone: Zone) {
        // 切分区清作品/角色（类型保留）；历史页无 COS 作者位，作品行只清出处分组
        applyFilter(AlbumFilter.selectPartition(_uiState.value.filter, zone))
    }

    /** 「作品」行候选点击（出处分组多选，同维 OR；null=「全部」胶囊清本行） */
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
        reloadAll(isRefresh = true)
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(cursor = state.nextCursor, append = true)
    }

    /**
     * 进详情前的批次上下文写入（2026-09-09 拍板：收藏/历史对齐旧版补基建，首页同款机制）：
     * 「已加载 = 当前显示清单」口径——历史页 items（每资产一条 lastViewedAt 倒序）即整页显示，
     * 快照式整体替换 [MediaBatchIndex.ids]，详情页据此得「i / N」序号与滑动切换（调用点在
     * HistoryScreen 卡片点击，先写批次再交壳层导航）。
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
        // 防重语义（与代际防乱序正交）：分页/下拉刷新在途时照旧丢弃重复触发；
        // 筛选重载不受 isLoading 拦截——在途的是旧代请求，其响应会被代际校验丢弃
        if ((append || isRefresh) && state.isLoading) return
        val gen = filterGeneration
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                // 分区映射（历史页口径）：全部=不传（服务端缺省 includeCos=true）/
                // 常规=显式 includeCos=false / COS=cosOnly=true
                val (includeCos, cosOnly) = zoneToHistoryParams(state.filter.partition)
                historyRepository.history(
                    HistoryQuery(
                        cursor = cursor,
                        limit = LIST_PAGE_SIZE,
                        includeCos = includeCos,
                        cosOnly = cosOnly,
                        mediaType = state.filter.mediaType,
                        // 「作品」行出处分组多选（N2 #30 数组位；N4 消费批接线）
                        source = state.filter.authors
                            .filter { it.kind == FacetParamKind.SOURCE }
                            .map { it.key }
                            .ifEmpty { null },
                        character = state.filter.characters
                            .filter { it.kind == FacetParamKind.CHARACTER }
                            .map { it.key }
                            .ifEmpty { null },
                        work = state.filter.characters
                            .filter { it.kind == FacetParamKind.WORK }
                            .map { it.key }
                            .ifEmpty { null },
                    ),
                )
            }.onSuccess { page ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items.map { it.asset } else page.items.map { it.asset },
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新筛选态
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    /** 候选（facets 加 history=1 子集约束）：分区/作品/角色/类型四维。
     * 「作品」行候选 = facets 作者行（history=1）的 source 桶——COS 作者桶
     * 不作历史页作品行候选（/history 无 authorId 数组位，且旧版作品模式只
     * 有出处分组），候选侧按 kind=SOURCE 收口（单测锁定）。 */
    private fun loadFacets() {
        val filter = _uiState.value.filter
        val gen = filterGeneration
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val partition = async { mediaRepository.facets(AlbumFilter.partitionFacetsQuery(filter, history = true)) }
                    val author = async { mediaRepository.facets(AlbumFilter.authorFacetsQuery(filter, history = true)) }
                    val character = async { mediaRepository.facets(AlbumFilter.characterFacetsQuery(filter, history = true)) }
                    val type = async { mediaRepository.facets(AlbumFilter.typeFacetsQuery(filter, history = true)) }
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
                    authorOptions = facets.authors
                        .filter { it.kind == FacetParamKind.SOURCE }
                        .withOtherBucketLast(),
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
    // （2026-09-07 审查 P3，与相册/收藏/搜索同款单源）。
}
