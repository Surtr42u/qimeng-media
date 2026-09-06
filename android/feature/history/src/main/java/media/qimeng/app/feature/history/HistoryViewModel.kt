package media.qimeng.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.HistoryRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.HistoryQuery
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.zoneToHistoryParams
import media.qimeng.app.core.model.withOtherBucketLast
import media.qimeng.app.feature.history.R

/** 历史页维度子集：分区/角色·作品/类型（作者行无协议参数支撑——/history 无 source/authorId，见交付报告） */
private val HISTORY_DIMS = listOf(AlbumDim.PARTITION, AlbumDim.CHARACTER, AlbumDim.TYPE)

data class HistoryUiState(
    val filter: AlbumFilterState = AlbumFilterState(),
    val activeDim: AlbumDim = AlbumDim.PARTITION,
    val partitionOptions: List<FacetOption> = emptyList(),
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

    /** 历史页维度子集（无作者行） */
    val dims: List<AlbumDim> = HISTORY_DIMS
}

/**
 * 历史页 ViewModel：GET /history（cursor 分页；每资产一条 lastViewedAt 倒序，服务端口径）。
 * 无清空按钮（拍板 2B：协议无 DELETE /history）。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val mediaRepository: MediaRepository,
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
     * 铁律 7「组件禁业务规则」，镜像 AlbumViewModel 同名入口；QimengFourDimSection 内嵌
     * 同款规则的旧组件随本批退役）：
     * 点已激活维=切换展开/折叠，点其他维=切维并默认展开（规格书 §药丸容器）。
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
        // 切分区清作者/角色（类型保留）；历史页作者行不存在，只清角色行
        applyFilter(AlbumFilter.selectPartition(_uiState.value.filter, zone))
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
                        limit = PAGE_SIZE,
                        includeCos = includeCos,
                        cosOnly = cosOnly,
                        mediaType = state.filter.mediaType,
                        character = state.filter.character?.takeIf { it.kind == media.qimeng.app.core.model.FacetParamKind.CHARACTER }?.key,
                        work = state.filter.character?.takeIf { it.kind == media.qimeng.app.core.model.FacetParamKind.WORK }?.key,
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
                    errorMessage = LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    /** 候选（facets 加 history=1 子集约束）：分区/角色/类型三维（无作者行） */
    private fun loadFacets() {
        val filter = _uiState.value.filter
        val gen = filterGeneration
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val partition = async { mediaRepository.facets(AlbumFilter.partitionFacetsQuery(filter, history = true)) }
                    val character = async { mediaRepository.facets(AlbumFilter.characterFacetsQuery(filter, history = true)) }
                    val type = async { mediaRepository.facets(AlbumFilter.typeFacetsQuery(filter, history = true)) }
                    FacetsResult(
                        partitions = partition.await().partitions,
                        authors = emptyList(),
                        characters = character.await().characters,
                        types = type.await().types,
                    )
                }
            }.onSuccess { facets ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    partitionOptions = facets.partitions,
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                    totalForAllPill = facets.partitions.firstOrNull { it.key == PARTITION_KEY_ALL }?.fileCount,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新筛选态
                _uiState.value = _uiState.value.copy(errorMessage = LOAD_FAILED_MESSAGE)
            }
        }
    }

    companion object {
        /** 列表分页大小（协议 /history limit 缺省 60） */
        const val PAGE_SIZE = 60

        /** 「全部」桶 key（协议 Partition.all 字面值） */
        private const val PARTITION_KEY_ALL = "all"

        private const val LOAD_FAILED_MESSAGE = "加载失败，请下拉重试"
    }
}
