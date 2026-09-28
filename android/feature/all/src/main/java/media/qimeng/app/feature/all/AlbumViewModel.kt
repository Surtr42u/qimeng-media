package media.qimeng.app.feature.all

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
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.TagNameConflictException
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchThrottle
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.APPEND_RETRY_DELAYS_MS
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.FilterPanelUiState
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.PARTITION_KEY_ALL
import media.qimeng.app.core.model.PanelFeedback
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.panelDraft
import media.qimeng.app.core.model.withOtherBucketLast
import media.qimeng.app.core.model.withPanelDraft

/** 相册页聚合状态（原「全部」页；2026-09-05 导航四化后为相册 Tab 内容） */
data class AlbumUiState(
    val filter: AlbumFilterState = AlbumFilterState(),
    val activeDim: AlbumDim = AlbumDim.PARTITION,
    val partitionOptions: List<FacetOption> = emptyList(),
    val authorOptions: List<FacetOption> = emptyList(),
    val characterOptions: List<FacetOption> = emptyList(),
    val typeOptions: List<FacetOption> = emptyList(),
    /** 「全部」胶囊计数（分区栏 all 桶 fileCount；Web 同口径） */
    val totalForAllPill: Int? = null,
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    val totalMatched: Int? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** 列表加载成功结束信号（问题A 哨兵哑火修复，2026-09-28）：QimengMediaGrid 触底哨兵的
     *  重评估 key——翻页成功但新页为空（totalCount 不变）时哨兵不再哑火。失败不 bump：
     *  会与翻页自动重试叠加成无限锤击循环（语义详见 HomeViewModel.RecommendState.reloadTick） */
    val reloadTick: Int = 0,
)

/**
 * 相册页 ViewModel：四维胶囊状态机（[AlbumFilter]，纯逻辑单测锁定）+
 * 四请求排自身候选（partition 恒显式传）+ cursor 分页 + 下拉刷新 +
 * 万能筛选面板草稿流（M4-2A-B3：打开拷贝/应用走 [applyFilter] 刷新链/
 * 重置=回默认+立即应用+关面板（旧版三合一口径）/标签增删）。
 * 面板状态类型（FilterPanelUiState/PanelFeedback）与查询展开（withPanelDraft）
 * 已收敛至 core/model 单源（任务Y Y4b，HomeViewModel 同范式互指）。
 */
@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    private val batchIndex: MediaBatchIndex,
    // 预取避让（问题B 下拉刷新响应慢修复，2026-09-28）：下拉刷新开始时让全库缩略图预取
    // 暂停抢带宽（窄接口，语义见 ThumbnailPrefetchThrottle KDoc）
    private val prefetchThrottle: ThumbnailPrefetchThrottle,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private val _panelState = MutableStateFlow(FilterPanelUiState())
    val panelState: StateFlow<FilterPanelUiState> = _panelState.asStateFlow()

    /**
     * 筛选代际号：筛选变化即递增。弱网下仓库响应可能乱序归位（自审 P2-1），
     * 请求发起时快照代际、响应落地前校验——旧代响应（含失败）一律丢弃，
     * 不再覆盖新筛选态。读写都发生在 Main（viewModelScope 与状态更新同线程），无需原子类。
     */
    private var filterGeneration = 0

    /** 相册网格列数（2~5 持久化，LEGACY §F） */
    val albumColumns: StateFlow<Int> = gridPrefs.albumColumns
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DataStoreGridPrefsRepository.DEFAULT_ALBUM_COLUMNS,
        )

    init {
        reloadAll()
    }

    fun selectDim(dim: AlbumDim) {
        _uiState.value = _uiState.value.copy(
            activeDim = dim,
            // 切维度默认展开（B8 拍板：规格书 §药丸容器，Web 现版自相矛盾处不采）
            filter = _uiState.value.filter.copy(expanded = true),
        )
    }

    /**
     * 维度芯片点击入口（M4-2A-B2 起芯片行由页面直接接线，交互语义收进状态机——铁律 7
     * 「组件禁业务规则」；旧仓库 AllFilesFragment.setViewMode 等四 Fragment 内嵌同款规则）：
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

    /**
     * selectSort 页头排序链已删除（任务L L4：页头四档排序行按用户拍板移除，G5 引入）。
     * 排序唯一编辑入口回归万能筛选面板：面板「排序方式/顺位」点选改草稿（updatePanelDraft），
     * 「应用筛选」经 [applyPanelDraft] → withPanelDraft 写入已应用态并走 [applyFilter] 刷新链。
     */

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

    // ---------- 万能筛选面板（M4-2A-B3） ----------

    /**
     * 打开面板：拷贝当前已应用面板值为草稿（编辑态语义，旧版 show(current) 同口径）
     * 并拉取标签候选流。关闭不回写——丢弃草稿。
     */
    fun openFilterSheet() {
        _panelState.value = _panelState.value.copy(
            visible = true,
            draft = _uiState.value.filter.panelDraft(),
            message = null, // 重开面板清上一轮操作反馈
        )
        loadTags()
    }

    /** 下滑/点外部关闭：丢弃草稿（BottomSheet 常规语义），反馈一并清空 */
    fun dismissFilterSheet() {
        _panelState.value = _panelState.value.copy(visible = false, message = null)
    }

    /** 面板内每次点选：只改草稿，不触发刷新 */
    fun updatePanelDraft(draft: AlbumPanelDraft) {
        _panelState.value = _panelState.value.copy(draft = draft)
    }

    /**
     * 「重置」：草稿回默认值后走 [applyPanelDraft] 同一条应用链——写已应用态 + applyFilter 刷新 + 关面板。
     * 旧版口径即三合一：`dialog.dismiss(); onApply(MediaFilterState())`（旧仓库 MediaFilterSheet.kt
     * L266-269 实读），修复轮 P1-1 按此对齐。
     */
    fun resetPanelDraft() {
        updatePanelDraft(AlbumPanelDraft())
        applyPanelDraft()
    }

    /** 「应用筛选」：草稿写入已应用态 + 走既有 [applyFilter] 刷新链（代际防乱序自动生效）+ 关面板 */
    fun applyPanelDraft() {
        val draft = _panelState.value.draft
        _panelState.value = _panelState.value.copy(visible = false)
        applyFilter(_uiState.value.filter.withPanelDraft(draft))
    }

    /**
     * 新建标签（面板「+ 添加标签」）：成功后刷新候选流；空名不发出请求。
     * 重名分流两道（修复轮 P2-1，取旧版 v1.16 查重口径 + 服务端 409 兜底）：
     * 候选预查重拦一道（命中即反馈，不发请求）；服务端 409 经 [TagNameConflictException]
     * 领域化兜底一道——均给「已存在」专门反馈；其余失败给中性操作失败反馈，
     * 不再冒充「加载失败」。
     */
    fun addTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (_panelState.value.tags.any { it.name == trimmed }) {
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
     * 删除标签（面板长按，确认框后）：服务端删除 + 候选流刷新；若删除的是草稿已选标签则一并从 tagIds 移除
     * （否则应用时会引用已不存在的 id）。失败给中性操作失败反馈（P2-1 分流）。
     */
    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            runCatching { mediaRepository.deleteTag(tagId) }
                .onSuccess {
                    val draft = _panelState.value.draft
                    if (tagId in draft.tagIds) {
                        _panelState.value = _panelState.value.copy(draft = draft.copy(tagIds = draft.tagIds - tagId))
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
                .onSuccess { _panelState.value = _panelState.value.copy(tags = it) }
                .onFailure {
                    // 候选流失败属列表级加载失败，沿用列表加载文案（与面板操作失败语义分流，P2-1）
                    _uiState.value = _uiState.value.copy(errorMessage = LIST_LOAD_FAILED_MESSAGE)
                }
        }
    }

    /** 面板操作反馈落位（null=清除；操作成功即清除上一条反馈） */
    private fun setPanelFeedback(feedback: PanelFeedback?) {
        _panelState.value = _panelState.value.copy(message = feedback)
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(cursor = state.nextCursor, append = true)
    }

    fun toggleColumns() {
        viewModelScope.launch {
            val current = albumColumns.value
            val next = if (current >= DataStoreGridPrefsRepository.MAX_ALBUM_COLUMNS) {
                DataStoreGridPrefsRepository.MIN_ALBUM_COLUMNS
            } else {
                current + 1
            }
            gridPrefs.setAlbumColumns(next)
        }
    }

    /**
     * 双指缩放期间的瞬时列数（null=无进行中手势，展示持久化值）。
     * 手势逐事件步进只改内存、不落盘——DataStore 写有延迟，逐帧写会卡顿且无意义；
     * 手势结束由 [commitPinchColumns] 统一持久化一次。
     */
    private val _pinchColumns = MutableStateFlow<Int?>(null)
    val pinchColumns: StateFlow<Int?> = _pinchColumns.asStateFlow()

    /** 双指缩放步进（delta=±1：放大减列、缩小加列，语义见 AllScreen 手势），clamp 2..5 */
    fun adjustColumnsLive(delta: Int) {
        val current = _pinchColumns.value ?: albumColumns.value
        _pinchColumns.value = (current + delta).coerceIn(
            DataStoreGridPrefsRepository.MIN_ALBUM_COLUMNS,
            DataStoreGridPrefsRepository.MAX_ALBUM_COLUMNS,
        )
    }

    /** 手势结束：缩放结果持久化一次（复用 [GridPrefsRepository.setAlbumColumns]，仓库内部再 clamp 2..5） */
    fun commitPinchColumns() {
        val target = _pinchColumns.value ?: return
        _pinchColumns.value = null
        if (target != albumColumns.value) {
            viewModelScope.launch { gridPrefs.setAlbumColumns(target) }
        }
    }

    /**
     * 进详情前的批次上下文写入（详情页「i / N」序号与左右滑切换的数据链，首页/收藏/历史同款机制）：
     * 「已加载 = 当前显示清单」口径——相册页 items 即当前筛选后的已显示清单，快照式整体替换
     * [MediaBatchIndex.ids]，详情页滑切才跟随相册当前筛选而非其他页面残留清单；清单未落地
     * （首载在途/失败）时 items 为空 → 空批次，详情页序号区不显示（调用点在 AllScreen
     * 卡片点击，先写批次再交壳层导航）。
     */
    fun enterDetail(assetId: String) {
        batchIndex.ids = _uiState.value.items.map { it.id }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 筛选变化统一入口：递增代际（作废在途旧响应）+ 重载第一页 + 重取四维候选（计数随其他维变化） */
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
        // 受理——受理即递增代际作废在途旧响应（列表+facets，刷新语义上覆盖一切在途状态），
        // 不再被 isLoading 吞掉刷新手势；筛选重载照旧不受 isLoading 拦截（在途的是旧代请求，
        // 其响应会被代际校验丢弃，自审 P2-1）
        if (append && state.isLoading) return
        if (isRefresh) filterGeneration += 1
        val gen = filterGeneration
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            var retryAttempt = 0
            while (true) {
                val page = runCatching {
                    mediaRepository.assets(
                        AlbumFilter.toAssetQuery(state.filter, limit = LIST_PAGE_SIZE, cursor = cursor),
                    )
                }.getOrElse {
                    if (gen != filterGeneration) return@launch // 旧代失败不污染新筛选态
                    // 翻页失败自动重试（问题A「翻页失败即卡死」，2026-09-28）：钉底用户等在
                    // 底部，不重试就必须上滚 6 项再回滚才能再触发。重试期间 isLoading 保持
                    // true——不阻塞其它操作（刷新/筛选路径均已绕过 isLoading 拦截），翻页防重
                    // 顺延到重试结束。首载/刷新失败不重试（立即亮横幅反馈既有口径）
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
                    totalMatched = page.totalMatched,
                    isLoading = false,
                    isRefreshing = false,
                    // 问题A：成功结束 bump 哨兵重评估信号（KDoc 见 AlbumUiState.reloadTick）
                    reloadTick = _uiState.value.reloadTick + 1,
                )
                return@launch
            }
        }
    }

    /** 四维候选：四请求各缺自身参数（排自身计数）；partition 恒显式传。并行取，互不阻塞 */
    private fun loadFacets() {
        val filter = _uiState.value.filter
        val gen = filterGeneration
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val partition = async { mediaRepository.facets(AlbumFilter.partitionFacetsQuery(filter)) }
                    val author = async { mediaRepository.facets(AlbumFilter.authorFacetsQuery(filter)) }
                    val character = async { mediaRepository.facets(AlbumFilter.characterFacetsQuery(filter)) }
                    val type = async { mediaRepository.facets(AlbumFilter.typeFacetsQuery(filter)) }
                    FacetsCombiner.collect(partition.await(), author.await(), character.await(), type.await())
                }
            }.onSuccess { facets ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    partitionOptions = facets.partitions,
                    authorOptions = facets.authors.withOtherBucketLast(),
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                    totalForAllPill = facets.total,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新筛选态
                _uiState.value = _uiState.value.copy(errorMessage = LIST_LOAD_FAILED_MESSAGE)
            }
        }
    }
    // 分页大小/失败文案：共享常量收敛至 core/model ListQueryDefaults.kt（2026-09-07
    // 审查 P3，与收藏/历史/搜索同款单源）。
}

/** 四请求聚合（ FacetsResult + 「全部」胶囊计数；独立小类便于测试与复用） */
internal data class FacetsCombiner(
    val partitions: List<FacetOption>,
    val authors: List<FacetOption>,
    val characters: List<FacetOption>,
    val types: List<FacetOption>,
    val total: Int?,
) {
    companion object {
        // 「全部」桶 key 不再本地定义：共享常量 core/model PARTITION_KEY_ALL
        // （协议 Partition 枚举字面值，2026-09-07 审查 P3 收敛单源）。

        fun collect(
            partition: FacetsResult,
            author: FacetsResult,
            character: FacetsResult,
            type: FacetsResult,
        ): FacetsCombiner = FacetsCombiner(
            partitions = partition.partitions,
            authors = author.authors,
            characters = character.characters,
            types = type.types,
            total = partition.partitions.firstOrNull { it.key == PARTITION_KEY_ALL }?.fileCount,
        )
    }
}
