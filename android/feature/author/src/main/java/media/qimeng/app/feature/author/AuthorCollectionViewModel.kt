package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
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
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilter
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.withOtherBucketLast

/** 作者集合页聚合状态（cursor 分页字段族与列表族页面同构 + 四维筛选子集状态） */
data class AuthorCollectionUiState(
    /** 四维筛选子集状态（core:model 状态机复用——只消费 author/character/mediaType/expanded 位） */
    val filter: AlbumFilterState = AlbumFilterState(),
    /** 当前激活维度芯片（进页 = 维度子集首维） */
    val activeDim: AlbumDim = AlbumDim.AUTHOR,
    /** 作品维候选（仅常规作者加载；COS 作者无作品维恒空） */
    val authorOptions: List<FacetOption> = emptyList(),
    val characterOptions: List<FacetOption> = emptyList(),
    val typeOptions: List<FacetOption> = emptyList(),
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    /** 列表首响的服务端总计数（页头「作者 · N 个文件」的 N 缺省源；兼作「全部 (N)」药丸计数） */
    val totalMatched: Int? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 作者集合页 ViewModel（任务G G1b 建，任务I I6 扩四维芯片体系）：
 * GET /assets authorId 固定收窄 + includeCos 恒 true + cursor 分页 + 下拉刷新；
 * 筛选维度子集 = 常规作者「作品/角色/类型」、COS 作者「角色/类型」（GUIDE_UI
 * §芯片栏配置对比 L68-69，COS 判定走 [isCosAuthorId] 前缀——openapi Author.id 口径）。
 * 药丸进页默认收起、切维度行强制展开（拍板②，与收藏/历史页同款状态机语义）；
 * 筛选变化重拉第一页 + facets（代际防乱序，镜像 FavoriteViewModel 同族方案）。
 * 列数（双指缩放 2~5）共用全部页档 [GridPrefsRepository.albumColumns]（GUIDE_UI §全部页
 * L149 updateGridColumnsAll 口径；core:data 零改动纯复用）。
 * 路由参数：authorId（取数键）+ authorName（标题展示，不参与数据定位）。
 */
@HiltViewModel
class AuthorCollectionViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val gridPrefs: GridPrefsRepository,
    val origUrlResolver: AssetOrigUrlResolver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 路由参数（键单源在 [AuthorCollectionRoutes]）；id 缺参 = 空态（深链容错，不崩溃） */
    private val authorId: String? = savedStateHandle[AuthorCollectionRoutes.KEY_AUTHOR_ID]

    /** 页标题（URL 解码后的原始名；缺参回退空串由 UI 兜底文案） */
    val authorName: String = savedStateHandle[AuthorCollectionRoutes.KEY_AUTHOR_NAME] ?: ""

    /** COS 作者判定（openapi Author.id cos_ 前缀；id 缺参按常规容错——不发起取数无影响） */
    private val isCos: Boolean = authorId?.let(::isCosAuthorId) ?: false

    /** 维度子集（常规=作品/角色/类型；COS=角色/类型）——芯片行与激活维默认值的单源 */
    val dims: List<AlbumDim> = authorCollectionDims(isCos)

    private val _uiState = MutableStateFlow(AuthorCollectionUiState(activeDim = dims.first()))
    val uiState: StateFlow<AuthorCollectionUiState> = _uiState.asStateFlow()

    /**
     * 筛选代际号：筛选变化即递增。弱网下仓库响应可能乱序归位（与收藏/相册页同族缺陷），
     * 请求发起时快照代际、响应落地前校验——旧代响应（含失败）一律丢弃。
     * 读写都在 Main（viewModelScope 与状态更新同线程），无需原子类。
     */
    private var filterGeneration = 0

    /**
     * 网格列数（双指缩放 2~5 列，GUIDE_UI §导航结构 L36 + §全部页 L149）：**共用全部页档**——
     * 旧版 v1.15 持久化语义「作者文件→updateGridColumnsAll」，纯复用既有
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
     * 手势逐事件步进只改内存、不落盘；手势结束由 [commitPinchColumns] 统一持久化一次
     * （镜像 FavoriteViewModel I5 同名接线）。
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

    /** 切维度行：强制展开药丸容器（拍板②；进页默认态由 [AlbumFilterState] 缺省收起承接） */
    fun selectDim(dim: AlbumDim) {
        _uiState.value = _uiState.value.copy(
            activeDim = dim,
            filter = _uiState.value.filter.copy(expanded = true),
        )
    }

    /** 维度芯片点击：点已激活维=切换展开/收起，点其他维=切维并展开（与收藏页同款语义） */
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

    /** 作品维（仅常规作者渲染；payload=null=「全部」清行） */
    fun selectAuthor(option: FacetOption?) {
        applyFilter(AlbumFilter.selectAuthor(_uiState.value.filter, option))
    }

    /** 角色维（kind 分派 character|work 在查询映射层；payload=null=清行） */
    fun selectCharacter(option: FacetOption?) {
        applyFilter(AlbumFilter.selectCharacter(_uiState.value.filter, option))
    }

    /** 类型维（点已选=取消；payload=null=「全部」） */
    fun selectMediaType(kind: MediaKind?) {
        applyFilter(AlbumFilter.selectMediaType(_uiState.value.filter, kind))
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(cursor = state.nextCursor, append = true)
    }

    fun refresh() {
        loadItems(cursor = null, append = false, isRefresh = true)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun applyFilter(filter: AlbumFilterState) {
        filterGeneration += 1
        _uiState.value = _uiState.value.copy(filter = filter)
        loadItems(cursor = null, append = false)
        loadFacets()
    }

    private fun loadItems(cursor: String?, append: Boolean, isRefresh: Boolean = false) {
        val id = authorId ?: return // 缺参（异常深链）：不发请求，UI 走空态
        val state = _uiState.value
        // 防重语义（与代际防乱序正交）：分页/下拉刷新在途时照旧丢弃重复触发；
        // 筛选重载不受 isLoading 拦截——在途的是旧代请求，其响应会被代际校验丢弃
        if ((append || isRefresh) && state.isLoading) return
        val gen = filterGeneration
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                mediaRepository.assets(
                    collectionAssetQuery(authorId = id, filter = state.filter, limit = LIST_PAGE_SIZE, cursor = cursor),
                )
            }.onSuccess { page ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    totalMatched = page.totalMatched,
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

    /** 维候选（排自身维）：常规作者三路并发；COS 作者无作品维只拉两路（候选收窄在查询映射层） */
    private fun loadFacets() {
        val id = authorId ?: return
        val filter = _uiState.value.filter
        val gen = filterGeneration
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val author = if (isCos) {
                        null
                    } else {
                        async { mediaRepository.facets(collectionFacetsQuery(id, AlbumDim.AUTHOR, filter)) }
                    }
                    val character = async { mediaRepository.facets(collectionFacetsQuery(id, AlbumDim.CHARACTER, filter)) }
                    val type = async { mediaRepository.facets(collectionFacetsQuery(id, AlbumDim.TYPE, filter)) }
                    FacetsResult(
                        partitions = emptyList(),
                        authors = author?.await()?.authors ?: emptyList(),
                        characters = character.await().characters,
                        types = type.await().types,
                    )
                }
            }.onSuccess { facets ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    // 作品维候选只取 SOURCE 桶（协议 facets 作者行排自身=source 与 authorId
                    // 一起忽略——authorId 无法收窄作者行候选，服务端返回的是全库候选，
                    // 其中 kind=author 的 COS 作者桶在本页（固定集合作者）不可作筛选参数，
                    // 客户端裁剪掉；协议限制记交付报告/台账）。kind 分派语义见 collectionAssetQuery。
                    authorOptions = facets.authors
                        .filter { it.kind == FacetParamKind.SOURCE }
                        .withOtherBucketLast(),
                    characterOptions = facets.characters.withOtherBucketLast(),
                    typeOptions = facets.types,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新筛选态
                _uiState.value = _uiState.value.copy(errorMessage = LIST_LOAD_FAILED_MESSAGE)
            }
        }
    }

    init {
        loadItems(cursor = null, append = false)
        loadFacets()
    }
}
