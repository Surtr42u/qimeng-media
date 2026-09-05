package media.qimeng.app.feature.favorite

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
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.withOtherBucketLast

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
) {
    /** 空态文案（规格书 §收藏页：COS 分区「没有COS收藏」，其余「还没有收藏」） */
    val emptyText: String
        get() = if (filter.partition == Zone.COS) "没有COS收藏" else "还没有收藏"
}

/** 收藏页 ViewModel：相册同款四维状态机，facets 加 favorite 子集约束（收藏计数只数收藏） */
@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoriteUiState())
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()

    init {
        reloadAll()
    }

    fun selectDim(dim: AlbumDim) {
        _uiState.value = _uiState.value.copy(
            activeDim = dim,
            filter = _uiState.value.filter.copy(expanded = true), // 切维度默认展开（B8）
        )
    }

    fun toggleExpanded() {
        val filter = _uiState.value.filter
        _uiState.value = _uiState.value.copy(filter = filter.copy(expanded = !filter.expanded))
    }

    fun collapsePills() {
        _uiState.value = _uiState.value.copy(filter = _uiState.value.filter.copy(expanded = false))
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
        _uiState.value = _uiState.value.copy(filter = filter)
        reloadAll()
    }

    private fun reloadAll(isRefresh: Boolean = false) {
        loadItems(cursor = null, append = false, isRefresh = isRefresh)
        loadFacets()
    }

    private fun loadItems(cursor: String?, append: Boolean, isRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.isLoading) return
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                val base = AlbumFilter.toAssetQuery(state.filter, limit = PAGE_SIZE, cursor = cursor)
                // 收藏固定口径：favorite=true + 收藏时间倒序（sort=favoriteAt 仅在收藏语义成立）
                mediaRepository.assets(
                    base.copy(favorite = true, sort = AssetSort.FAVORITE_AT, order = SortOrder.DESC),
                )
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    private fun loadFacets() {
        val filter = _uiState.value.filter
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
                _uiState.value = _uiState.value.copy(
                    partitionOptions = facets.partitions,
                    authorOptions = facets.authors.withOtherBucketLast(),
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                    totalForAllPill = facets.partitions.firstOrNull { it.key == PARTITION_KEY_ALL }?.fileCount,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(errorMessage = LOAD_FAILED_MESSAGE)
            }
        }
    }

    companion object {
        /** 列表分页大小（与相册页同口径：协议 /assets 缺省 60） */
        const val PAGE_SIZE = 60

        /** 「全部」桶 key（协议 Partition.all 字面值） */
        private const val PARTITION_KEY_ALL = "all"

        private const val LOAD_FAILED_MESSAGE = "加载失败，请下拉重试"
    }
}
