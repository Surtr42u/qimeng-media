package media.qimeng.app.feature.detail

import android.os.Debug
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.FavoriteMutationTracker
import media.qimeng.app.core.data.repository.LikeMutationTracker
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MoveConflictException
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.feature.detail.playback.DirectAnalyticsReporter
import media.qimeng.app.feature.detail.playback.ProgressThrottlePolicy
import media.qimeng.app.feature.detail.playback.WatchState

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
    /** 标签管理弹窗：全量标签池（服务端名字序）与勾选集合 */
    val tagPool: List<TagChip> = emptyList(),
    val selectedTagIds: List<String> = emptyList(),
    val tagSheetOpen: Boolean = false,
    val savingTags: Boolean = false,
    /** 解绑在途的标签 id 集（N4 I7b：chip 关闭图标=立即 DELETE；同 id 在途防重） */
    val unbindingTagIds: List<String> = emptyList(),
    /** 互动行请求进行中（按钮 disabled 态） */
    val likePending: Boolean = false,
    val favoritePending: Boolean = false,
    /** 关注 toggle 进行中的作者 id（该行按钮 disabled；null = 无在途关注请求） */
    val followPendingAuthorId: String? = null,
    /**
     * 预加载目标（3b 拍板③窗口：前 1 后 2，距当前最近序）。url 取窗口内邻位详情——
     * 图片=origUrl 原图、视频=thumbUrl 海报帧；url 缺失的邻位不出现在列表里。
     * Coil 入队/取消在 UI 层（DetailScreen DisposableEffect），VM 只发目标清单。
     */
    val preloadTargets: List<DetailPreloadTarget> = emptyList(),
    /**
     * 视频续播起点毫秒（3d，[WatchState] 冻结口径）：未看完 = 断点秒×1000；
     * 已看完 = 0（重播语义）。图片资产恒 0（不被消费）。
     */
    val videoStartPositionMs: Long = 0L,
    /** 已看完徽标（3d，[WatchState] 冻结口径：lastPositionSeconds 与 durationMs 齐备且 ≥ 才真） */
    val videoWatched: Boolean = false,
    /** 时间轴标签（3d，仅视频资产加载；GET 按 timeMillis 升序，服务端排序） */
    val timelineTags: List<TimelineTag> = emptyList(),
    // ---------- 文件操作（任务G G1b：整理/删除） ----------
    /** 整理弹窗开关（条件挂载——打开即从当前 directory/fileName 惰性复位预填值） */
    val moveSheetOpen: Boolean = false,
    /** 删除确认弹窗开关 */
    val deleteConfirmOpen: Boolean = false,
    /** 文件操作请求进行中（两弹窗确认钮与互动行两钮 disabled 同源） */
    val fileOpsPending: Boolean = false,
    /** 整理弹窗内失败文案（null=无失败；弹窗内独立展示——错误横幅在弹窗遮罩后不可见） */
    val moveError: String? = null,
)

/**
 * 单个预加载目标（3b）。isVideo 决定 UI 侧请求形态：图片按原图尺寸（口径②不降采样），
 * 视频海报帧是小缩略图按默认档即可。
 */
data class DetailPreloadTarget(
    val assetId: String,
    val url: String,
    val isVideo: Boolean,
)

/**
 * 详情页 ViewModel（M4-3）：3a 详情读取、互动行（点赞/收藏/关注）、标签管理
 * （读-改-写一次提交）与批次导航数据链；3b 补兄弟资产切换取数
 * （moveBy，UI 经 push 叠栈消费）与预加载窗口（拍板③：窗口计算=DetailPreloadPolicy
 * 纯函数，本 VM 只做取数与目标发布）；3d 补播放接线三件：续播起点（WatchState 口径）、
 * 进度上报（ProgressThrottlePolicy 5s 心跳节流 + 暂停/离开 force 补报）、行为打点
 * （DirectAnalyticsReporter：open/play/dwell），以及时间轴标签增删查。
 * 互动/标签失败只置 errorMessage，不动 asset——已加载内容不因单次请求失败回退成空页。
 * 任务W W3：原「接下来播放」推荐流数据链随推荐栏退役整段删除。
 *
 * 超文件警戒线理由：详情页全部数据链单 ViewModel——详情读取、互动行、标签读改写、
 * 兄弟切换与预加载窗口、播放三件（续播/节流上报/打点）、时间轴标签共享同一
 * asset 生命周期与批次清单状态，拆分会引入跨 VM 的资产态同步；类体主体为各自
 * 取数段（已尽量拆纯函数策略出类：DetailPreloadPolicy/ProgressThrottlePolicy），
 * 警戒线特此记档。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val authorRepository: AuthorRepository,
    private val batchIndex: MediaBatchIndex,
    private val imageDimCache: DetailImageDimCache,
    // 本地点赞变更指纹（任务I I7 接线，I1 批交付的消费端基座）：点赞成功处上报，
    // 首页 ON_RESUME 对比指纹变化重拉推荐/排行榜（GUIDE_UI §下拉刷新 L89「点赞后返回
    // 自动重排」；SSE 无 like 事件，本地感知是协议内唯一路径——tracker KDoc 口径）
    private val likeMutationTracker: LikeMutationTracker,
    // 本地收藏变更指纹（任务V V1，2026-09-10 返回刷新缺陷修复）：收藏成功处上报，
    // 收藏页 ON_RESUME 对比指纹变化才重拉（此前无条件重拉，返回 morph 期间列表整体
    // 重显——缺陷根因；SSE 无 favorite 事件，与 like 同款协议约束，tracker KDoc 口径）
    private val favoriteMutationTracker: FavoriteMutationTracker,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** 路由参数（键单源在 [DetailRoutes.KEY_ASSET_ID]）；缺参 = 错误态而非崩溃（深链容错） */
    private val assetId: String? = savedStateHandle[DetailRoutes.KEY_ASSET_ID]

    /**
     * 打点会话标识（3d）：**每 VM 实例一次 UUID**（实例生命周期 ≈ 一次详情停留）。
     * 与协议口径对应：服务端按 assetId+kind+sessionId+当日 对 open/play 会话级去重，
     * dwell 不去重逐条累加——同一停留会话全部事件（open/play/dwell）携带同一 sessionId，
     * dwell 分段逐条上报由 DwellSessionTracker 保证（真实口径见其类注释）。
     */
    private val sessionId: String = UUID.randomUUID().toString()

    /**
     * 行为打点组装器（3d，DirectAnalyticsReporter 纯逻辑壳）：init 即 onDetailEntered()
     * = open 打点一次 + 开 dwell 会话；起播/暂停/离开由 Screen 生命周期与起播回调驱动。
     * 上报落点 = DetailRepository.reportViewEvent（M4-4 起写进离线队列：写成功即返回，
     * 出网补传由队列三通道异步完成）；失败静默 = 仅队列写失败时静默（打点尽力而为，
     * 不影响浏览主链路）。assetId 缺参（错误态）为 null：无资产可打点，reporter 不创建、
     * 一切入口无害跳过。
     */
    private val analyticsReporter: DirectAnalyticsReporter? = assetId?.let { id ->
        DirectAnalyticsReporter(
            assetId = id,
            sessionIdProvider = { sessionId },
            report = { emission ->
                viewModelScope.launch {
                    runCatching {
                        detailRepository.reportViewEvent(
                            assetId = emission.assetId,
                            kind = emission.kind,
                            startedAtMs = emission.startedAtMs,
                            sessionId = emission.sessionId,
                            dwellSeconds = emission.dwellSeconds,
                        )
                    }.onFailure { /* 写队列失败才静默（打点尽力而为）；出网补传由队列负责 */ }
                }
            },
        )
    }

    /**
     * 进度上报节流策略（3d，5s 心跳冻结口径）。internal var 供单测注入假时钟策略
     * （同 heapUsedRatioProvider 惯例——生产默认真实时钟，VM init 不消费、无注入时序问题）。
     */
    internal var progressThrottle = ProgressThrottlePolicy()

    init {
        // 3d：进入详情 = open 打点一次 + 开 dwell 会话（open 每实例一次由 reporter 幂等保证）
        analyticsReporter?.onDetailEntered()
        loadDetail()
    }

    /** 错误横幅「重试」/错误态「重试」按钮：重新走详情加载 */
    fun retry() {
        if (_uiState.value.isLoading) return
        loadDetail()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // ---------- 详情加载 ----------

    private fun loadDetail() {
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
                    applyWatchState(detail)
                    rememberDims(detail)
                    schedulePreload()
                    // 时间轴标签只对视频资产加载（3d；失败静默，不影响主内容）
                    if (detail.mediaType == MediaKind.VIDEO) loadTimelineTags()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = ERROR_LOAD_DETAIL)
                }
        }
    }

    /** 标签保存成功后的详情重拉：只刷新 asset */
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
                    applyWatchState(detail)
                    rememberDims(detail)
                    // 标签保存重拉后窗口可能因新learned尺寸收紧；prefetchedIds 去重防重复取数
                    schedulePreload()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_LOAD_DETAIL)
                }
        }
    }

    // ---------- 续播起点与已看完（3d，WatchState 冻结口径） ----------

    /**
     * 详情落地后按 [WatchState] 纯函数口径推导续播起点与已看完徽标并发布到 UiState：
     * 未看完 → 起点 = 断点秒×1000；已看完 → 起点 0（重播语义）。进度上报也借本节流
     * 策略实例，与续播起点无耦合（上报的是播放器实时位置，起点只在 prepare 时用一次）。
     */
    private fun applyWatchState(detail: AssetDetail) {
        val watch = WatchState.of(detail.lastPositionSeconds, detail.durationMs)
        _uiState.value = _uiState.value.copy(
            videoStartPositionMs = (watch.resumeStartSeconds * 1000).toLong(),
            videoWatched = watch.watched,
        )
    }

    // ---------- 批次导航 ----------

    /**
     * 批次邻位切换取数（拍板③：详情页左右滑切换相邻资产）：基于批次快照以当前路由资产
     * 定位 index，返回 delta 偏移后的目标资产 id；路由缺参/当前资产不在批次内/目标越界
     * 返回 null（UI 据此不动，不环绕）。3b 已接线：ImageStage 横滑回调 → 本方法 → 壳层
     * push 叠栈导航（批次清单不变）。
     */
    fun moveBy(delta: Int): String? {
        val id = assetId ?: return null
        val current = batchIndex.indexOf(id)
        if (current < 0) return null
        return batchIndex.assetIdAt(current, delta)
    }

    // ---------- 预加载窗口（拍板③，3b） ----------

    /**
     * 堆占用比例读取（超大图预载收紧依据）。internal var 供单测注入定值——
     * 生产默认**双通道取 max**（旧版 isMemoryComfortable 口径）：Java 堆 (total-free)/max
     * 与 native 堆 allocated/total——API26+ 大图 bitmap 像素数据在 native 堆，只看 Java 堆
     * 会漏判真紧张（native 已涨爆而 Java 堆空闲）。native 读取 runCatching 兜底回 0
     * （JVM 单测 android.jar stub 不可调，回退只看 Java 通道=宽松不收紧）。
     */
    internal var heapUsedRatioProvider: () -> Float = {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory().toFloat()
        val javaRatio = if (max <= 0f) 0f else (runtime.totalMemory() - runtime.freeMemory()) / max
        val nativeRatio = runCatching {
            val nativeTotal = Debug.getNativeHeapSize().toFloat()
            if (nativeTotal <= 0f) 0f else Debug.getNativeHeapAllocatedSize().toFloat() / nativeTotal
        }.getOrDefault(0f)
        maxOf(javaRatio, nativeRatio)
    }

    /** 窗口序（策略产出，目标发布按此序对齐「距当前最近优先」） */
    private var preloadWindowOrder: List<String> = emptyList()

    /** 窗口内邻位详情取到的目标（id → target；就绪一个发布一个） */
    private val preloadTargetsById = mutableMapOf<String, DetailPreloadTarget>()

    /** 本 VM 生命周期内已发起过详情取数的预载 id（去重：标签保存重拉不重复预载） */
    private val prefetchedIds = mutableSetOf<String>()

    /**
     * 进入详情后对窗口（前 1 后 2）内 id 拉详情取直链，发布到 [DetailUiState.preloadTargets]，
     * UI 层（DetailScreen DisposableEffect）对目标做 Coil 预取（图片 origUrl / 视频 thumbUrl
     * 海报帧）。越界加载策略：只对已加载批次内 id 预取，不主动拉批次外资产（批次=列表页
     * 内存传递，拍板④）。预载尽力而为：邻位详情拉取失败静默（不影响主内容浏览）；
     * 在途取数随 viewModelScope 取消（onCleared 天然清理）。
     */
    private fun schedulePreload() {
        val id = assetId ?: return
        val current = batchIndex.indexOf(id)
        if (current < 0) return // 无批次上下文：无窗口可预载
        val window = DetailPreloadPolicy.computePreload(
            currentIndex = current,
            ids = batchIndex.ids,
            sizeOf = imageDimCache::dimsOf,
            heapUsedRatio = heapUsedRatioProvider(),
        )
        preloadWindowOrder = window
        window.filterNot { it in prefetchedIds }.forEach { targetId ->
            prefetchedIds += targetId
            viewModelScope.launch {
                runCatching { detailRepository.assetDetail(targetId) }
                    .onSuccess { neighbor ->
                        rememberDims(neighbor)
                        val isVideo = neighbor.mediaType == MediaKind.VIDEO
                        val url = if (isVideo) neighbor.thumbUrl else neighbor.origUrl
                        if (url != null) {
                            preloadTargetsById[neighbor.id] =
                                DetailPreloadTarget(neighbor.id, url, isVideo)
                            publishPreloadTargets()
                        }
                    }
                    .onFailure { /* 预载尽力而为：静默（错误不进 errorMessage） */ }
            }
        }
        publishPreloadTargets()
    }

    /** 按窗口序发布目标清单（只含 url 已就绪的邻位） */
    private fun publishPreloadTargets() {
        _uiState.value = _uiState.value.copy(
            preloadTargets = preloadWindowOrder.mapNotNull(preloadTargetsById::get),
        )
    }

    /** 详情落地后登记已知尺寸（预载策略超大图判定的数据源，进程级共享跨兄弟叠栈屏） */
    private fun rememberDims(detail: AssetDetail) {
        val w = detail.width
        val h = detail.height
        if (w != null && h != null && w > 0 && h > 0) {
            imageDimCache.put(detail.id, ImageDims(w, h))
        }
    }

    // ---------- 互动行 ----------

    /** 点赞 toggle：成功后用服务端权威值（LikeState）回填，不做本地猜测；成功处上报本地点赞
     *  变更指纹（I7，LikeMutationTracker——首页返回重拉的感知源，失败不上报） */
    fun toggleLike() {
        val id = assetId ?: return
        if (_uiState.value.likePending) return
        _uiState.value = _uiState.value.copy(likePending = true)
        viewModelScope.launch {
            runCatching { detailRepository.toggleLike(id) }
                .onSuccess { result ->
                    likeMutationTracker.onLikeMutated()
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

    /** 收藏显式设置：目标态 = 本地翻转（协议 PUT body 是显式布尔，非 toggle）；成功处上报
     *  本地收藏变更指纹（任务V V1，FavoriteMutationTracker——收藏页返回重拉的感知源，失败不上报） */
    fun toggleFavorite() {
        val id = assetId ?: return
        val current = _uiState.value.asset ?: return
        if (_uiState.value.favoritePending) return
        val target = !current.isFavorite
        _uiState.value = _uiState.value.copy(favoritePending = true)
        viewModelScope.launch {
            runCatching { detailRepository.setFavorite(id, target) }
                .onSuccess {
                    favoriteMutationTracker.onFavoriteMutated()
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
     * 「当前标签」chip 关闭图标 = **立即** DELETE 单条解绑（N4 I7b；N3 #32 逐条删端点），
     * 不再只是草稿勾选：乐观移除 UI → 服务端成功后重拉详情（服务端按关联时间倒序回「最终序」）；
     * 失败回滚乐观态 + 错误提示。草稿新增流（其他标签点选 + 新建 + 保存=整体替换）保留不动。
     */
    fun unbindTag(tagId: String) {
        val current = _uiState.value
        val asset = current.asset ?: return
        val id = assetId ?: return
        if (tagId in current.unbindingTagIds) return // 同标签在途防重
        val chip = current.tagPool.firstOrNull { it.id == tagId } ?: return
        val removedTagIndex = asset.tags.indexOfFirst { it.id == tagId }
        if (removedTagIndex < 0) return // 不在当前标签列表：无可解绑（防御）
        val removedTag = asset.tags[removedTagIndex]
        // 乐观态快照（StateFlow 值可被并发替换，不能依赖链式引用）
        val snapshotSelected = current.selectedTagIds
        _uiState.value = current.copy(
            unbindingTagIds = current.unbindingTagIds + tagId,
            asset = asset.copy(tags = asset.tags.filterNot { it.id == tagId }),
            selectedTagIds = current.selectedTagIds - tagId,
        )
        viewModelScope.launch {
            runCatching { detailRepository.unbindTag(id, chip.name) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        unbindingTagIds = _uiState.value.unbindingTagIds - tagId,
                        errorMessage = null,
                    )
                    // 重拉详情对齐服务端最终序（保存同款收尾；失败不影响已解绑事实）
                    reloadAssetOnly()
                }
                .onFailure { error ->
                    // R13（审计 2026-09-20）：失败只回滚「本 tagId 的乐观改动」，
                    // 不再整覆盖乐观态快照——乐观态与失败回调之间可能有更新的
                    // 状态落入 _uiState（窗口邻位重拉完成/用户其他操作），整覆盖
                    // 会把它们一并回退到旧时刻。
                    val rollback = _uiState.value
                    val restoredAsset = rollback.asset?.let { cur ->
                        if (cur.tags.any { it.id == tagId }) {
                            cur // 已被后续重拉恢复：不重复插回
                        } else {
                            val idx = removedTagIndex.coerceAtMost(cur.tags.size)
                            cur.copy(tags = cur.tags.toMutableList().apply { add(idx, removedTag) })
                        }
                    }
                    _uiState.value = rollback.copy(
                        unbindingTagIds = rollback.unbindingTagIds - tagId,
                        asset = restoredAsset,
                        // distinct 防重复：窗口期用户可能在弹窗重新勾回同一标签
                        // （List + element 追加不去重，重复 id 会随 saveTags 整体
                        // 替换 PUT 上行）——createAndSelectTag 同款口径（2026-09-21 维护批）。
                        selectedTagIds = (
                            if (tagId in snapshotSelected) rollback.selectedTagIds + tagId else rollback.selectedTagIds
                            ).distinct(),
                        errorMessage = "$ERROR_UNBIND_TAG${error.message ?: ""}",
                    )
                }
        }
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

    // ---------- 播放进度上报（3d） ----------

    /** 起播回调（VideoStage onIsPlayingChanged(true) 驱动）：play 打点一次（reporter 幂等去重） */
    fun onPlaybackStarted() {
        analyticsReporter?.onPlayStarted()
    }

    /**
     * 播放进度 tick（VideoStage 播放中 1s 轮询喂入）：先 observe 覆盖最新位置，再问节流
     * 策略是否放行——放行即 PUT progress（服务端只存最新值；窗口内被吞的中间 tick 由
     * 「最新值覆盖」语义自然丢弃，不丢最近位置）。
     */
    fun onPositionChanged(positionSeconds: Double) {
        progressThrottle.observe(positionSeconds)
        consumeAndReportProgress()
    }

    /**
     * 立即补报（暂停/离开：Screen onPause、onDispose 与 onCleared 兜底）：
     * force 下次消费必放行，用策略内保留的最新位置发一次。
     */
    fun flushProgressNow() {
        progressThrottle.force()
        consumeAndReportProgress()
    }

    private fun consumeAndReportProgress() {
        val id = assetId ?: return
        val position = progressThrottle.consumeReportable() ?: return
        viewModelScope.launch {
            runCatching { detailRepository.reportProgress(id, position) }
                .onFailure { /* 上报尽力而为：失败静默（服务端只存最新值，下一心跳/补报自然覆盖） */ }
        }
    }

    // ---------- Screen 生命周期接线（3d；由 DetailScreen DisposableEffect 驱动） ----------

    /** 进后台/切走（ON_PAUSE）：dwell flush 当前段并结束会话（resume 由 onScreenResumed 开新段，dwell 分段累加口径）+ 进度 force 立即补报 */
    fun onScreenPaused() {
        analyticsReporter?.onPaused()
        flushProgressNow()
    }

    /** 回前台（ON_RESUME）：dwell 开新段（分段累加口径，见 DwellSessionTracker；未 pause 过则无害） */
    fun onScreenResumed() {
        analyticsReporter?.onResumed()
    }

    /**
     * 舞台组合离场（onDispose；Navigation Compose 中先于 VM onCleared，此时 viewModelScope
     * 仍存活，补报可送达）：dwell leave flush 当前段（幂等，会话关闭）+ 进度 force 补报。
     * 弱网极端时序下 onCleared 取消协程可能掐断补报，尽力而为口径。
     */
    fun onScreenDisposed() {
        analyticsReporter?.onDetailLeft()
        flushProgressNow()
    }

    override fun onCleared() {
        // R13（审计 2026-09-20）清死代码：原此处还调 flushProgressNow()，但
        // onCleared 时 viewModelScope 已取消、其内部 launch 永不执行——网络
        // 补报在此不可能送达，可靠路径是 onScreenDisposed（先于 onCleared
        // 且 scope 存活）。只保留同步的 destroy()（释放分析器会话句柄）。
        analyticsReporter?.destroy()
        super.onCleared()
    }

    // ---------- 时间轴标签（3d，视频资产核心体验） ----------

    /** 拉时间轴标签（GET 按 timeMillis 升序）。失败静默：标签是增强体验，不阻塞播放主链路 */
    private fun loadTimelineTags() {
        val id = assetId ?: return
        viewModelScope.launch {
            runCatching { detailRepository.timelineTags(id) }
                .onSuccess { tags -> _uiState.value = _uiState.value.copy(timelineTags = tags) }
                .onFailure { /* 尽力而为：静默 */ }
        }
    }

    /**
     * 新建时间轴标签（timeMillis=VideoStage 采集的当前播放位置毫秒；name trim 非空才发）。
     * 成功后重拉列表刷新芯片；失败进 errorMessage 横幅（与互动行同一反馈通道）。
     */
    fun addTimelineTag(timeMillis: Long, name: String) {
        val id = assetId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { detailRepository.addTimelineTag(id, timeMillis, trimmed) }
                .onSuccess { loadTimelineTags() }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_ADD_TIMELINE_TAG)
                }
        }
    }

    /** 删除时间轴标签（长按菜单入口；成功后重拉列表刷新芯片，失败进 errorMessage） */
    fun deleteTimelineTag(tagId: String) {
        val id = assetId ?: return
        viewModelScope.launch {
            runCatching { detailRepository.deleteTimelineTag(id, tagId) }
                .onSuccess { loadTimelineTags() }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_DELETE_TIMELINE_TAG)
                }
        }
    }

    // ---------- 文件操作（任务G G1b：POST /move + DELETE=回收站） ----------

    /** 打开整理弹窗（预填值来自当前 asset 的 directory/fileName，弹窗条件挂载即复位） */
    fun openMoveSheet() {
        if (_uiState.value.asset == null || _uiState.value.fileOpsPending) return
        _uiState.value = _uiState.value.copy(moveSheetOpen = true, moveError = null)
    }

    fun dismissMoveSheet() {
        if (_uiState.value.fileOpsPending) return // 请求在途不许关（防丢结果上下文）
        _uiState.value = _uiState.value.copy(moveSheetOpen = false, moveError = null)
    }

    /** 打开删除确认弹窗（danger 二次确认，文案在弹窗内明示回收站语义） */
    fun openDeleteConfirm() {
        if (_uiState.value.asset == null || _uiState.value.fileOpsPending) return
        _uiState.value = _uiState.value.copy(deleteConfirmOpen = true)
    }

    fun dismissDeleteConfirm() {
        if (_uiState.value.fileOpsPending) return
        _uiState.value = _uiState.value.copy(deleteConfirmOpen = false)
    }

    /**
     * 整理提交（POST /assets/{id}/move：改名 = 原目录 + 新名；移动 = 新目录 + 原名）。
     * 成功：关弹窗 + 重拉详情（directory/fileName 落新值，orig/thumb 签名直链随新路径刷新），
     * [onMoved] 回调交 UI 层做 toast（Web toast.success 同语义；VM 不触 Android 资源）。
     * 失败：弹窗保持打开 + 弹窗内失败文案（409 同名 → 领域文案；其余 → 通用 + 透传 message）。
     *
     * @param moved/moved & renamed 由 UI 侧（弹窗输入态）判定的变化维度，仅用于 toast 文案分流
     */
    fun moveAsset(targetDir: String, newName: String?, onMoved: (moved: Boolean, renamed: Boolean) -> Unit) {
        val id = assetId ?: return
        val current = _uiState.value
        if (current.fileOpsPending) return
        val asset = current.asset ?: return
        _uiState.value = current.copy(fileOpsPending = true, moveError = null)
        viewModelScope.launch {
            runCatching { detailRepository.moveAsset(id, targetDir, newName) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        fileOpsPending = false,
                        moveSheetOpen = false,
                        moveError = null,
                    )
                    onMoved(targetDir != (asset.directory ?: ""), newName != null)
                    reloadAssetOnly()
                }
                .onFailure { error ->
                    val message = if (error is MoveConflictException) {
                        ERROR_MOVE_CONFLICT
                    } else {
                        "$ERROR_MOVE_FAILED${error.message.orEmpty()}"
                    }
                    _uiState.value = _uiState.value.copy(fileOpsPending = false, moveError = message)
                }
        }
    }

    /**
     * 删除提交（DELETE = 移入回收站，铁律 4；浏览/点赞等记录保留）。
     * 成功：[onDeleted] 回调交 UI 层做 toast + onBack() 离开已删资产（Web navigate(-1)
     * 同收尾；VM 不做导航）。失败：关弹窗 + errorMessage 横幅（弹窗已关，横幅可见）。
     */
    fun deleteAsset(onDeleted: () -> Unit) {
        val id = assetId ?: return
        val current = _uiState.value
        if (current.fileOpsPending) return
        _uiState.value = current.copy(fileOpsPending = true)
        viewModelScope.launch {
            runCatching { detailRepository.deleteAsset(id) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(fileOpsPending = false, deleteConfirmOpen = false)
                    onDeleted()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        fileOpsPending = false,
                        deleteConfirmOpen = false,
                        errorMessage = ERROR_DELETE_ASSET,
                    )
                }
        }
    }

    companion object {
        // 错误文案（中文含操作名；与列表族共享的 LIST_LOAD_FAILED_MESSAGE（core:model 单源）同为
        // 状态层文案——不进 res，测试直接断言；本文件文案为详情页专用故留在本地）
        private const val ERROR_MISSING_ASSET = "缺少资产参数，无法打开详情"
        private const val ERROR_LOAD_DETAIL = "详情加载失败，请重试"
        private const val ERROR_LIKE = "点赞操作失败，请重试"
        private const val ERROR_FAVORITE = "收藏操作失败，请重试"
        private const val ERROR_FOLLOW = "关注操作失败，请重试"
        private const val ERROR_LOAD_TAGS = "标签列表加载失败"
        private const val ERROR_CREATE_TAG = "新建标签失败："
        private const val ERROR_SAVE_TAGS = "标签保存失败："
        private const val ERROR_UNBIND_TAG = "解除标签失败："
        private const val ERROR_ADD_TIMELINE_TAG = "时间轴标签添加失败"
        private const val ERROR_DELETE_TIMELINE_TAG = "时间轴标签删除失败"

        // 文件操作文案（任务G G1b；中文含操作名。服务端 409 同名是可预期输入，
        // 给专门文案而非笼统失败——TagNameConflictException 分流同范式）
        private const val ERROR_MOVE_CONFLICT = "目标位置已有同名文件，请换个名字或目录"
        private const val ERROR_MOVE_FAILED = "整理失败："
        private const val ERROR_DELETE_ASSET = "移入回收站失败，请重试"
    }
}
