package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.TagChip

/**
 * 详情页 UI 状态（3a 骨架）。错误反馈照 home 的 errorMessage 横幅模式；
 * asset 为 null 且 isLoading=false 即「无内容可恢复」态（加载失败/路由缺参）。
 */
data class DetailUiState(
    val isLoading: Boolean = true,
    val asset: AssetDetail? = null,
    val errorMessage: String? = null,
    /** 当前资产在批次清单中的序号（0 基；-1 = 无批次上下文，序号区不显示） */
    val batchIndex: Int = -1,
    /** 批次清单大小（「i / N」的 N） */
    val batchSize: Int = 0,
    /** 「接下来播放」推荐流（已过滤当前资产） */
    val upNext: List<MediaAsset> = emptyList(),
    val upNextLoading: Boolean = false,
    /** 标签管理弹窗：全量标签池（服务端名字序）与勾选集合 */
    val tagPool: List<TagChip> = emptyList(),
    val selectedTagIds: List<String> = emptyList(),
    val tagSheetOpen: Boolean = false,
    val savingTags: Boolean = false,
    /** 互动行请求进行中（按钮 disabled 态） */
    val likePending: Boolean = false,
    val favoritePending: Boolean = false,
    /** 关注 toggle 进行中的作者 id（该行按钮 disabled；null = 无在途关注请求） */
    val followPendingAuthorId: String? = null,
)

/**
 * 详情页 ViewModel（M4-3 3a）：详情读取、互动行（点赞/收藏/关注）、标签管理
 * （读-改-写一次提交）、「接下来播放」与批次导航数据链。滑动接线（3b）不在本批。
 * 互动/标签失败只置 errorMessage，不动 asset——已加载内容不因单次请求失败回退成空页。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val authorRepository: AuthorRepository,
    private val batchIndex: MediaBatchIndex,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** 路由参数（键单源在 [DetailRoutes.KEY_ASSET_ID]）；缺参 = 错误态而非崩溃（深链容错） */
    private val assetId: String? = savedStateHandle[DetailRoutes.KEY_ASSET_ID]

    /** 推荐流当前 seed（初始 0；换一批 = 换成当前时间戳） */
    private var upNextSeed: Long = INITIAL_UP_NEXT_SEED

    init {
        loadDetail(initial = true)
    }

    /** 错误横幅「重试」/错误态「重试」按钮：重新走详情加载（成功后续拉推荐流） */
    fun retry() {
        if (_uiState.value.isLoading) return
        loadDetail(initial = true)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // ---------- 详情加载 ----------

    private fun loadDetail(initial: Boolean) {
        val id = assetId
        if (id == null) {
            // 无 assetId（导航缺参）：不崩溃，进错误态（测试 8 锁定）
            _uiState.value = DetailUiState(isLoading = false, errorMessage = ERROR_MISSING_ASSET)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { detailRepository.assetDetail(id) }
                .onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        asset = detail,
                        // 批次序号在详情落地时取快照（列表页 enterDetail 已先写入）
                        batchIndex = batchIndex.indexOf(id),
                        batchSize = batchIndex.size(),
                    )
                    if (initial) refreshUpNext(INITIAL_UP_NEXT_SEED)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = ERROR_LOAD_DETAIL)
                }
        }
    }

    /** 标签保存成功后的详情重拉：只刷新 asset（推荐流与此无关，不重触发） */
    private fun reloadAssetOnly() {
        val id = assetId ?: return
        viewModelScope.launch {
            runCatching { detailRepository.assetDetail(id) }
                .onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(
                        asset = detail,
                        batchIndex = batchIndex.indexOf(id),
                        batchSize = batchIndex.size(),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_LOAD_DETAIL)
                }
        }
    }

    // ---------- 「接下来播放」 ----------

    /**
     * 拉推荐流。seed 初始 0：协议 seed=0 不打散=固定序（Web UpNextList useState(0) 同款）；
     * mediaType 按详情类型收窄；cosOnly = COS 资产推荐流同区收窄（与首页 cos tab 同参数语义）；
     * 结果过滤当前资产（协议无排除参数，客户端滤——Web useUpNextList 同款）。
     */
    private fun refreshUpNext(seed: Long) {
        val asset = _uiState.value.asset ?: return
        upNextSeed = seed
        _uiState.value = _uiState.value.copy(upNextLoading = true)
        viewModelScope.launch {
            runCatching {
                detailRepository.upNext(
                    seed = seed,
                    limit = UP_NEXT_LIMIT,
                    mediaType = asset.mediaType,
                    cosOnly = asset.cosWork != null,
                )
            }.onSuccess { items ->
                _uiState.value = _uiState.value.copy(
                    upNextLoading = false,
                    upNext = items.filter { it.id.isNotEmpty() && it.id != asset.id },
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    upNextLoading = false,
                    errorMessage = ERROR_LOAD_UP_NEXT,
                )
            }
        }
    }

    /** 「换一批」：seed=当前时间戳重取（同 seed 可复现，DOMAIN_RULES §1.1 禁纯随机）。
     *  在途判定 = upNextLoading（按钮 disabled 同源；原 reshufflePending 死状态已清偿） */
    fun reshuffleUpNext() {
        if (_uiState.value.upNextLoading) return
        refreshUpNext(seed = System.currentTimeMillis())
    }

    /**
     * 推荐栏行点击（批次导航数据链，3b 滑动接线的 VM 侧基座）：
     * 把批次清单整体替换为推荐栏清单（推荐栏即新清单），随后 UI 层调 onOpenAsset 导航。
     * 无参：目标 id 对批次替换无意义（整表替换语义），原参数为死参已清偿。
     */
    fun upNextJump() {
        batchIndex.ids = _uiState.value.upNext.map { it.id }
    }

    /**
     * 批次邻位切换取数（拍板③：详情页左右滑切换相邻资产）：基于批次快照以当前路由资产
     * 定位 index，返回 delta 偏移后的目标资产 id；路由缺参/当前资产不在批次内/目标越界
     * 返回 null（UI 据此不动，不环绕）。3b 接线：滑动回调 → moveBy(±1) → 壳层导航，本批 UI 不调用。
     */
    fun moveBy(delta: Int): String? {
        val id = assetId ?: return null
        val current = batchIndex.indexOf(id)
        if (current < 0) return null
        return batchIndex.assetIdAt(current, delta)
    }

    // ---------- 互动行 ----------

    /** 点赞 toggle：成功后用服务端权威值（LikeState）回填，不做本地猜测 */
    fun toggleLike() {
        val id = assetId ?: return
        if (_uiState.value.likePending) return
        _uiState.value = _uiState.value.copy(likePending = true)
        viewModelScope.launch {
            runCatching { detailRepository.toggleLike(id) }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        likePending = false,
                        asset = _uiState.value.asset?.copy(
                            likedToday = result.likedToday,
                            likeCount = result.likeCount,
                        ),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(likePending = false, errorMessage = ERROR_LIKE)
                }
        }
    }

    /** 收藏显式设置：目标态 = 本地翻转（协议 PUT body 是显式布尔，非 toggle） */
    fun toggleFavorite() {
        val id = assetId ?: return
        val current = _uiState.value.asset ?: return
        if (_uiState.value.favoritePending) return
        val target = !current.isFavorite
        _uiState.value = _uiState.value.copy(favoritePending = true)
        viewModelScope.launch {
            runCatching { detailRepository.setFavorite(id, target) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        favoritePending = false,
                        asset = _uiState.value.asset?.copy(isFavorite = target),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(favoritePending = false, errorMessage = ERROR_FAVORITE)
                }
        }
    }

    /** 关注 toggle（复用 [AuthorRepository.setFollowed]）：成功后更新对应作者行 followed */
    fun toggleFollow(authorId: String) {
        val author = _uiState.value.asset?.authors?.firstOrNull { it.id == authorId } ?: return
        if (_uiState.value.followPendingAuthorId != null) return
        val target = !author.followed
        _uiState.value = _uiState.value.copy(followPendingAuthorId = authorId)
        viewModelScope.launch {
            runCatching { authorRepository.setFollowed(authorId, target) }
                .onSuccess {
                    // 局部 val 持有当前 asset：StateFlow 值可被并发替换，不能依赖链式 smart cast
                    val current = _uiState.value.asset
                    _uiState.value = _uiState.value.copy(
                        followPendingAuthorId = null,
                        asset = current?.copy(
                            authors = current.authors.map {
                                if (it.id == authorId) it.copy(followed = target) else it
                            },
                        ),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        followPendingAuthorId = null,
                        errorMessage = ERROR_FOLLOW,
                    )
                }
        }
    }

    // ---------- 标签管理 ----------

    /** 打开弹窗：先拉全量池（失败→报错且不开弹窗）；勾选态以详情当前标签复位（Web 同款打开即复位） */
    fun openTagSheet() {
        val asset = _uiState.value.asset ?: return
        viewModelScope.launch {
            runCatching { detailRepository.allTags() }
                .onSuccess { pool ->
                    _uiState.value = _uiState.value.copy(
                        tagSheetOpen = true,
                        tagPool = pool,
                        selectedTagIds = asset.tags.map { it.id },
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_LOAD_TAGS)
                }
        }
    }

    fun dismissTagSheet() {
        _uiState.value = _uiState.value.copy(tagSheetOpen = false)
    }

    /** 勾选即时本地增删（增删后立即体现——LEGACY §A:15；保存前不落库） */
    fun toggleTagSelection(tagId: String) {
        val current = _uiState.value
        val next = if (tagId in current.selectedTagIds) {
            current.selectedTagIds - tagId
        } else {
            current.selectedTagIds + tagId
        }
        _uiState.value = current.copy(selectedTagIds = next)
    }

    /**
     * 新建并勾选：trim 非空才发；重名失败透传服务端错误文案；成功入池且选中。
     * [onCreated] 仅创建成功后回调（弹窗据此清空输入框；失败保留输入供重试，对齐 Web 成功回调清空）。
     */
    fun createAndSelectTag(rawName: String, onCreated: () -> Unit = {}) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            runCatching { detailRepository.createTag(name) }
                .onSuccess { chip ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        tagPool = if (current.tagPool.any { it.id == chip.id }) {
                            current.tagPool
                        } else {
                            current.tagPool + chip
                        },
                        selectedTagIds = (current.selectedTagIds + chip.id).distinct(),
                        errorMessage = null,
                    )
                    onCreated()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "$ERROR_CREATE_TAG${error.message ?: ""}",
                    )
                }
        }
    }

    /**
     * 保存 = PUT tags 整体替换（读-改-写一次提交；PUT 返回 void 故无回填），
     * 成功后重新 GET assetDetail：服务端按关联时间倒序返回「最近添加置顶」最终序（LEGACY §A）。
     * 注：整体替换会刷新全部关联时间=协议已知限制（唯一标签端点，DOMAIN_RULES §7）。
     */
    fun saveTags() {
        val id = assetId ?: return
        val current = _uiState.value
        if (current.savingTags) return
        _uiState.value = current.copy(savingTags = true)
        viewModelScope.launch {
            runCatching { detailRepository.replaceAssetTags(id, current.selectedTagIds) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        savingTags = false,
                        tagSheetOpen = false,
                        errorMessage = null,
                    )
                    reloadAssetOnly()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        savingTags = false,
                        errorMessage = "$ERROR_SAVE_TAGS${error.message ?: ""}",
                    )
                }
        }
    }

    companion object {
        /** 推荐栏初始 seed：0 = 协议不打散固定序（Web useUpNextList 初始 0 同款） */
        const val INITIAL_UP_NEXT_SEED = 0L

        /** 推荐栏条数（Web useUpNextList limit 缺省 12，use-assets.ts:322） */
        const val UP_NEXT_LIMIT = 12

        // 错误文案（中文含操作名；与 home 的 LOAD_FAILED_MESSAGE 同为 VM 常量——
        // 状态层文案不进 res，测试直接断言）
        private const val ERROR_MISSING_ASSET = "缺少资产参数，无法打开详情"
        private const val ERROR_LOAD_DETAIL = "详情加载失败，请重试"
        private const val ERROR_LOAD_UP_NEXT = "推荐加载失败"
        private const val ERROR_LIKE = "点赞操作失败，请重试"
        private const val ERROR_FAVORITE = "收藏操作失败，请重试"
        private const val ERROR_FOLLOW = "关注操作失败，请重试"
        private const val ERROR_LOAD_TAGS = "标签列表加载失败"
        private const val ERROR_CREATE_TAG = "新建标签失败："
        private const val ERROR_SAVE_TAGS = "标签保存失败："
    }
}
