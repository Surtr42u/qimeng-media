package media.qimeng.app.feature.all

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
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.withOtherBucketLast

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
)

/**
 * 相册页 ViewModel：四维胶囊状态机（[AlbumFilter]，纯逻辑单测锁定）+
 * 四请求排自身候选（partition 恒显式传）+ cursor 分页 + 下拉刷新。
 */
@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

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
        android.util.Log.d("QimengApi", "album loadMore cursor=${state.nextCursor}")
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 筛选变化统一入口：重载第一页 + 重取四维候选（计数随其他维变化） */
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
                mediaRepository.assets(
                    AlbumFilter.toAssetQuery(state.filter, limit = PAGE_SIZE, cursor = cursor),
                )
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    totalMatched = page.totalMatched,
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

    /** 四维候选：四请求各缺自身参数（排自身计数）；partition 恒显式传。并行取，互不阻塞 */
    private fun loadFacets() {
        val filter = _uiState.value.filter
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
                _uiState.value = _uiState.value.copy(
                    partitionOptions = facets.partitions,
                    authorOptions = facets.authors.withOtherBucketLast(),
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                    totalForAllPill = facets.total,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(errorMessage = LOAD_FAILED_MESSAGE)
            }
        }
    }

    companion object {
        /** 列表分页大小（协议 /assets limit 缺省 60，≤200；协议侧改动须同步此处） */
        const val PAGE_SIZE = 60

        private const val LOAD_FAILED_MESSAGE = "加载失败，请下拉重试"
    }
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
        /** 「全部」桶在分区栏的 key（协议 Partition.all 字面值） */
        private const val PARTITION_KEY_ALL = "all"

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
