package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.app.feature.detail.playback.ProgressThrottlePolicy

/**
 * 详情页 ViewModel 单测（M4-3 3a）：详情加载/批次序号、互动回填与失败保持、
 * 推荐流参数锁死（seed=0 初始/limit=12/类型收窄/过滤当前资产/换一批换 seed/跳转换批次）、
 * 标签管理流（拉池/即时勾选/新建/整体替换+重拉详情）、路由缺参错误态。
 * fake 全手写（禁 MockK），范式照 HomeViewModelTest + core/testing MainDispatcherRule。
 */
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身 ----------

    private class FakeDetailRepository : DetailRepository {
        data class UpNextCall(val seed: Long, val limit: Int, val mediaType: MediaKind?, val cosOnly: Boolean)

        var detailResult: AssetDetail? = null

        /** 按 id 的详情返回（3b 预载：窗口内会拉多个 id；未配置的 id 回退 [detailResult]） */
        val detailById = mutableMapOf<String, AssetDetail>()

        /** 按 id 的取数计数（预载去重断言用） */
        val detailCallsById = mutableMapOf<String, Int>()

        var detailError: Exception? = null
        var detailCallCount = 0

        var likeResult: LikeToggleResult? = null
        var likeError: Exception? = null

        var favoriteError: Exception? = null
        val favoriteCalls = mutableListOf<Pair<String, Boolean>>()

        var upNextResult: List<MediaAsset> = emptyList()
        var upNextError: Exception? = null
        val upNextCalls = mutableListOf<UpNextCall>()

        var tagPool: List<TagChip> = emptyList()
        var createTagError: Exception? = null
        var createTagResult: TagChip? = null
        val createdTagNames = mutableListOf<String>()

        var replaceTagsError: Exception? = null
        val replaceTagsCalls = mutableListOf<List<String>>()

        // 3d 接口（播放进度/打点/时间轴标签）——接口已收拢为抽象方法，fake 全量实现
        val progressCalls = mutableListOf<Double>()

        data class ViewEventCall(
            val assetId: String,
            val kind: ViewEventKind,
            val sessionId: String,
            val dwellSeconds: Long?,
        )

        val viewEventCalls = mutableListOf<ViewEventCall>()

        /** 时间轴标签存储（add/delete 真实变异，重拉可断言刷新）；失败注入三通道（3d P3） */
        val timelineTagStore = mutableListOf<TimelineTag>()
        var timelineTagsError: Exception? = null
        var addTimelineTagError: Exception? = null
        var deleteTimelineTagError: Exception? = null
        val addTimelineTagCalls = mutableListOf<Pair<Long, String>>()
        val deleteTimelineTagCalls = mutableListOf<String>()

        override suspend fun reportProgress(assetId: String, positionSeconds: Double) {
            progressCalls += positionSeconds
        }

        override suspend fun reportViewEvent(
            assetId: String,
            kind: ViewEventKind,
            startedAtMs: Long,
            sessionId: String,
            dwellSeconds: Long?,
        ) {
            viewEventCalls += ViewEventCall(assetId, kind, sessionId, dwellSeconds)
        }

        override suspend fun timelineTags(assetId: String): List<TimelineTag> {
            timelineTagsError?.let { throw it }
            return timelineTagStore.toList()
        }

        override suspend fun addTimelineTag(assetId: String, timeMillis: Long, name: String): TimelineTag {
            addTimelineTagError?.let { throw it }
            addTimelineTagCalls += timeMillis to name
            val tag = TimelineTag(id = "tag-${addTimelineTagCalls.size}", timeMillis = timeMillis, name = name)
            timelineTagStore += tag
            return tag
        }

        override suspend fun deleteTimelineTag(assetId: String, tagId: String) {
            deleteTimelineTagError?.let { throw it }
            deleteTimelineTagCalls += tagId
            timelineTagStore.removeAll { it.id == tagId }
        }

        override suspend fun assetDetail(assetId: String): AssetDetail {
            detailCallCount += 1
            detailCallsById.merge(assetId, 1, Int::plus)
            detailError?.let { throw it }
            return detailById[assetId] ?: requireNotNull(detailResult)
        }

        override suspend fun toggleLike(assetId: String): LikeToggleResult {
            likeError?.let { throw it }
            return requireNotNull(likeResult)
        }

        override suspend fun setFavorite(assetId: String, favorite: Boolean) {
            favoriteError?.let { throw it }
            favoriteCalls += assetId to favorite
        }

        override suspend fun allTags(): List<TagChip> = tagPool

        override suspend fun createTag(name: String): TagChip {
            createTagError?.let { throw it }
            createdTagNames += name
            return requireNotNull(createTagResult)
        }

        override suspend fun replaceAssetTags(assetId: String, tagIds: List<String>) {
            replaceTagsError?.let { throw it }
            replaceTagsCalls += tagIds
        }

        override suspend fun upNext(
            seed: Long,
            limit: Int,
            mediaType: MediaKind?,
            cosOnly: Boolean,
        ): List<MediaAsset> {
            upNextError?.let { throw it }
            upNextCalls += UpNextCall(seed, limit, mediaType, cosOnly)
            return upNextResult
        }
    }

    private class FakeAuthorRepository : AuthorRepository {
        var followError: Exception? = null
        val followCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun authors(): List<AuthorSummary> = emptyList()

        override suspend fun setFollowed(authorId: String, followed: Boolean) {
            followError?.let { throw it }
            followCalls += authorId to followed
        }
    }

    // ---------- 造数 ----------

    private fun detail(
        id: String,
        cosWork: String? = null,
        mediaType: MediaKind = MediaKind.IMAGE,
        tags: List<DetailTag> = emptyList(),
        authors: List<DetailAuthor> = emptyList(),
        isFavorite: Boolean = false,
        origUrl: String? = null,
        thumbUrl: String? = null,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        lastPositionSeconds: Double? = null,
    ) = AssetDetail(
        id = id,
        fileName = "$id.jpg",
        title = cosWork ?: "$id.jpg",
        mediaType = mediaType,
        sizeBytes = null,
        modifiedAtMs = null,
        source = null,
        isFavorite = isFavorite,
        likeCount = 0,
        likedToday = false,
        thumbUrl = thumbUrl,
        origUrl = origUrl,
        durationMs = durationMs,
        cosWork = cosWork,
        lastPositionSeconds = lastPositionSeconds,
        viewCount = null,
        playCount = null,
        width = width,
        height = height,
        tags = tags,
        authors = authors,
    )

    private fun mediaAsset(id: String) = MediaAsset(
        id = id,
        fileName = "$id.jpg",
        title = id,
        mediaType = MediaKind.IMAGE,
        thumbUrl = null,
        source = null,
        characters = emptyList(),
        isFavorite = false,
        authorNames = emptyList(),
        modifiedAtMs = null,
        durationMs = null,
        viewCount = null,
        playCount = null,
        lastViewedAtMs = null,
    )

    private fun savedHandle(assetId: String?): SavedStateHandle =
        if (assetId == null) SavedStateHandle() else SavedStateHandle(mapOf(DetailRoutes.KEY_ASSET_ID to assetId))

    private fun viewModel(
        repo: FakeDetailRepository,
        authorRepo: FakeAuthorRepository = FakeAuthorRepository(),
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
        assetId: String? = "b",
        imageDimCache: DetailImageDimCache = DetailImageDimCache(),
    ): DetailViewModel = DetailViewModel(
        detailRepository = repo,
        authorRepository = authorRepo,
        batchIndex = batchIndex,
        imageDimCache = imageDimCache,
        savedStateHandle = savedHandle(assetId),
    )

    // ---------- 用例 ----------

    @Test
    fun `加载成功 - asset到位且批次序号正确`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c")
        val repo = FakeDetailRepository().apply { detailResult = detail("b", cosWork = "作品B") }
        val vm = viewModel(repo, batchIndex = batchIndex)

        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("作品B", state.asset?.title) // title=cosWork 优先（mapper 口径，VM 透传）
        assertEquals("b", state.asset?.id)
        assertEquals(1, state.batchIndex) // [a,b,c] 中 b → 序号 1
        assertEquals(3, state.batchSize)
        // 当前资产自身只取 1 次（批次 [a,b,c] 的窗口预载会另拉 c/a，见预载用例）
        assertEquals(1, repo.detailCallsById["b"])
    }

    @Test
    fun `加载失败 - 报错且retry可恢复`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailError = RuntimeException("boom") }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.asset)

        repo.detailError = null
        repo.detailResult = detail("b")
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("b", vm.uiState.value.asset?.id)
    }

    @Test
    fun `toggleLike成功用服务端值回填 - 失败报错且状态不变`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo)
        advanceUntilIdle()

        repo.likeResult = LikeToggleResult(likedToday = true, likeCount = 42)
        vm.toggleLike()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.likePending)
        assertEquals(true, vm.uiState.value.asset?.likedToday)
        assertEquals(42, vm.uiState.value.asset?.likeCount)
        assertNull(vm.uiState.value.errorMessage)

        repo.likeError = RuntimeException("like boom")
        vm.toggleLike()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage) // 含操作名的中文报错
        assertEquals(true, vm.uiState.value.asset?.likedToday) // 状态不变
        assertEquals(42, vm.uiState.value.asset?.likeCount)
    }

    @Test
    fun `toggleFavorite成功本地翻转 - 失败不变加报错`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b", isFavorite = false) }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(repo.favoriteCalls.single().second) // 目标态 = 本地翻转
        assertTrue(vm.uiState.value.asset?.isFavorite == true)
        assertNull(vm.uiState.value.errorMessage)

        repo.favoriteError = RuntimeException("fav boom")
        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.asset?.isFavorite == true) // 失败不翻转
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `toggleFollow成功更新对应作者followed`() = runTest(mainDispatcherRule.testDispatcher) {
        val authorRepo = FakeAuthorRepository()
        val repo = FakeDetailRepository().apply {
            detailResult = detail(
                "b",
                authors = listOf(
                    DetailAuthor(id = "au1", displayName = "作者甲", isCos = true, followed = false),
                ),
            )
        }
        val vm = viewModel(repo, authorRepo = authorRepo)
        advanceUntilIdle()

        vm.toggleFollow("au1")
        advanceUntilIdle()
        assertEquals(listOf("au1" to true), authorRepo.followCalls)
        assertTrue(vm.uiState.value.asset?.authors?.single()?.followed == true)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `upNext - 参数锁死过滤当前资产reshuffle换seed且jump替换批次清单`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c")
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", cosWork = "作品B", mediaType = MediaKind.VIDEO)
            upNextResult = listOf(mediaAsset("b"), mediaAsset("x"), mediaAsset("y"))
        }
        val vm = viewModel(repo, batchIndex = batchIndex)
        advanceUntilIdle()

        // 请求参数锁死：seed=0 初始（协议不打散固定序）、limit=12、类型收窄、COS 同区收窄
        val call = repo.upNextCalls.single()
        assertEquals(0L, call.seed)
        assertEquals(12, call.limit)
        assertEquals(MediaKind.VIDEO, call.mediaType)
        assertTrue(call.cosOnly) // cosWork!=null → cosOnly
        // 结果过滤当前资产
        assertEquals(listOf("x", "y"), vm.uiState.value.upNext.map { it.id })

        // 换一批：seed 变为时间戳（非 0，可复现语义）
        vm.reshuffleUpNext()
        advanceUntilIdle()
        assertEquals(2, repo.upNextCalls.size)
        assertTrue(repo.upNextCalls[1].seed > 0)
        assertTrue(repo.upNextCalls[1].seed != repo.upNextCalls[0].seed)

        // 跳转：批次清单整体替换为推荐栏清单（推荐栏即新清单；无参——目标 id 不参与替换语义）
        vm.upNextJump()
        assertEquals(listOf("x", "y"), batchIndex.ids)
    }

    @Test
    fun `moveBy - 批次内前进后退返回邻位id`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c")
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo, batchIndex = batchIndex)
        advanceUntilIdle()

        // 当前资产 b（index=1）：+1 → c，-1 → a
        assertEquals("c", vm.moveBy(1))
        assertEquals("a", vm.moveBy(-1))
    }

    @Test
    fun `moveBy - 首尾越界返回null`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c")
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo, batchIndex = batchIndex)
        advanceUntilIdle()

        assertNull(vm.moveBy(2)) // b+2 越过尾
        assertNull(vm.moveBy(-2)) // b-2 越过首
    }

    @Test
    fun `moveBy - 无批次上下文空批次与路由缺参均返回null`() = runTest(mainDispatcherRule.testDispatcher) {
        // 当前资产不在批次内（无批次上下文：冷启动直进详情）
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo, batchIndex = MediaBatchIndex())
        advanceUntilIdle()
        assertNull(vm.moveBy(1)) // 空批次

        // 路由缺参（SavedStateHandle 无 assetId）
        val vmNoId = viewModel(repo, batchIndex = MediaBatchIndex(), assetId = null)
        advanceUntilIdle()
        assertNull(vmNoId.moveBy(-1))
    }

    // ---------- 预加载窗口（拍板③，3b） ----------

    @Test
    fun `预载窗口 - 进入后按前1后2拉邻位详情并按最近序发布目标`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c", "d", "e")
        val repo = FakeDetailRepository().apply {
            detailById["b"] = detail("b", origUrl = "https://x/b.orig")
            detailById["a"] = detail("a", origUrl = "https://x/a.orig")
            detailById["c"] = detail("c", origUrl = "https://x/c.orig")
            detailById["d"] = detail("d", mediaType = MediaKind.VIDEO, thumbUrl = "https://x/d.thumb")
            // e 在窗口外（+3），不预载——越界加载策略：不主动拉批次窗口外资产
        }
        val vm = viewModel(repo, batchIndex = batchIndex)
        advanceUntilIdle()

        val targets = vm.uiState.value.preloadTargets
        // 窗口序 [+1, -1, +2] = [c, a, d]；当前 b 自身不在目标里
        assertEquals(listOf("c", "a", "d"), targets.map { it.assetId })
        // 图片→origUrl 原图（口径②）；视频→thumbUrl 海报帧
        assertEquals("https://x/c.orig", targets[0].url)
        assertFalse(targets[0].isVideo)
        assertEquals("https://x/d.thumb", targets[2].url)
        assertTrue(targets[2].isVideo)
        // 窗口外 e 未拉详情；窗口内各拉 1 次
        assertEquals(null, repo.detailCallsById["e"])
        assertEquals(1, repo.detailCallsById["c"])
    }

    @Test
    fun `预载窗口 - 已知超大图限量一张且堆占用达阈值全部跳过`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c", "d", "e")
        val dimCache = DetailImageDimCache().apply {
            put("c", ImageDims(5000, 6000)) // 超大（长边 6000 > 4096）
            put("a", ImageDims(5000, 5000)) // 超大
            put("d", ImageDims(1000, 800)) // 普通
        }
        fun repoWithAll() = FakeDetailRepository().apply {
            detailById["b"] = detail("b", origUrl = "https://x/b.orig")
            detailById["a"] = detail("a", origUrl = "https://x/a.orig")
            detailById["c"] = detail("c", origUrl = "https://x/c.orig")
            detailById["d"] = detail("d", origUrl = "https://x/d.orig")
        }

        // 内存宽裕（heap 0.3）：窗口 [c(超大→预), a(超大→撞限量跳过), d(普通→预)]
        val repo = repoWithAll()
        val vm = viewModel(repo, batchIndex = batchIndex, imageDimCache = dimCache)
        vm.heapUsedRatioProvider = { 0.3f }
        advanceUntilIdle()
        assertEquals(listOf("c", "d"), vm.uiState.value.preloadTargets.map { it.assetId })

        // 内存紧张（heap 0.6 达阈值）：超大图 c/a 全跳过，只留普通 d
        val repo2 = repoWithAll()
        val vm2 = viewModel(repo2, batchIndex = batchIndex, imageDimCache = dimCache)
        vm2.heapUsedRatioProvider = { 0.6f }
        advanceUntilIdle()
        assertEquals(listOf("d"), vm2.uiState.value.preloadTargets.map { it.assetId })
    }

    @Test
    fun `预载窗口 - 重试重拉详情不重复预载邻位`() = runTest(mainDispatcherRule.testDispatcher) {
        val batchIndex = MediaBatchIndex()
        batchIndex.ids = listOf("a", "b", "c")
        val repo = FakeDetailRepository().apply {
            detailById["b"] = detail("b", origUrl = "https://x/b.orig")
            detailById["a"] = detail("a", origUrl = "https://x/a.orig")
            detailById["c"] = detail("c", origUrl = "https://x/c.orig")
        }
        val vm = viewModel(repo, batchIndex = batchIndex)
        advanceUntilIdle()
        assertEquals(1, repo.detailCallsById["c"])

        // retry 走 loadDetail(initial=true)→schedulePreload：prefetchedIds 去重，邻位不重拉
        vm.retry()
        advanceUntilIdle()
        assertEquals(1, repo.detailCallsById["c"])
        assertEquals(1, repo.detailCallsById["a"])
        // 当前资产自身按 retry 语义重拉
        assertEquals(2, repo.detailCallsById["b"])
    }

    @Test
    fun `预载窗口 - 无批次上下文不预载`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo, batchIndex = MediaBatchIndex())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.preloadTargets.isEmpty())
        // 只拉了当前自身，无窗口取数
        assertEquals(1, repo.detailCallCount)
    }

    @Test
    fun `标签流 - 拉池选中即时勾选新建入池保存重拉详情`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", tags = listOf(DetailTag("t1", "甲")))
            tagPool = listOf(TagChip("t1", "甲"), TagChip("t2", "乙"))
            createTagResult = TagChip("t3", "新")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(1, repo.detailCallCount)

        // 打开弹窗：拉池 + 勾选=当前 tags
        vm.openTagSheet()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tagSheetOpen)
        assertEquals(listOf("t1", "t2"), vm.uiState.value.tagPool.map { it.id })
        assertEquals(listOf("t1"), vm.uiState.value.selectedTagIds)

        // 即时勾选/取消
        vm.toggleTagSelection("t2")
        assertEquals(listOf("t1", "t2"), vm.uiState.value.selectedTagIds)
        vm.toggleTagSelection("t1")
        assertEquals(listOf("t2"), vm.uiState.value.selectedTagIds)

        // 新建入池且选中
        vm.createAndSelectTag("  新  ")
        advanceUntilIdle()
        assertEquals("新", repo.createdTagNames.single()) // trim 后发请求
        assertEquals(3, vm.uiState.value.tagPool.size)
        assertTrue("t3" in vm.uiState.value.selectedTagIds)

        // 保存：PUT 载荷=勾选集；成功后重新 GET 详情、关弹窗
        vm.saveTags()
        advanceUntilIdle()
        assertEquals(listOf("t2", "t3"), repo.replaceTagsCalls.single())
        assertEquals(2, repo.detailCallCount) // 重拉详情（关联时间倒序最终序在服务端）
        assertFalse(vm.uiState.value.tagSheetOpen)
        assertFalse(vm.uiState.value.savingTags)
    }

    @Test
    fun `savedStateHandle无assetId - 错误态不崩溃`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository()
        val vm = viewModel(repo, assetId = null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.asset)
        assertNotNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(0, repo.detailCallCount)
        assertEquals(0, repo.upNextCalls.size)
    }

    // ---------- 续播起点与已看完（3d，WatchState 冻结口径经 UiState 消费） ----------

    @Test
    fun `续播起点 - 未看完起点断点秒x1000且无徽标`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail(
                "b",
                mediaType = MediaKind.VIDEO,
                durationMs = 200_000L,
                lastPositionSeconds = 90.5,
            )
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(90_500L, vm.uiState.value.videoStartPositionMs)
        assertFalse(vm.uiState.value.videoWatched)
    }

    @Test
    fun `续播起点 - 已看完起点归0且显示徽标`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            // 边界：断点 == 时长 → 已看完（>= 口径）
            detailResult = detail(
                "b",
                mediaType = MediaKind.VIDEO,
                durationMs = 120_000L,
                lastPositionSeconds = 120.0,
            )
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.videoStartPositionMs)
        assertTrue(vm.uiState.value.videoWatched)
    }

    @Test
    fun `续播起点 - 断点时长缺省均未看完起点0无徽标`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            // 两者皆空
            detailResult = detail("b", mediaType = MediaKind.VIDEO)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.videoStartPositionMs)
        assertFalse(vm.uiState.value.videoWatched)

        // 任一为空同样未看完（WatchState 口径：两者非空才判已看完）
        val repo2 = FakeDetailRepository().apply {
            detailResult = detail("b", mediaType = MediaKind.VIDEO, lastPositionSeconds = 30.0)
        }
        val vm2 = viewModel(repo2)
        advanceUntilIdle()
        assertEquals(30_000L, vm2.uiState.value.videoStartPositionMs)
        assertFalse(vm2.uiState.value.videoWatched)
    }

    // ---------- 打点接线（3d）：open 每实例一次 / play 每实例一次 / dwell 分段累加 ----------

    @Test
    fun `打点 - open进入一次play起播一次dwell分段累加且sessionId一致`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b", mediaType = MediaKind.VIDEO) }
        val vm = viewModel(repo)
        advanceUntilIdle()

        // open 恰一次（init onDetailEntered；重复进入不会发生——每实例一次）
        assertEquals(listOf(ViewEventKind.OPEN), repo.viewEventCalls.map { it.kind })
        val open = repo.viewEventCalls.single()

        // play：重复起播回调只打一次（reporter 幂等）
        vm.onPlaybackStarted()
        vm.onPlaybackStarted()
        advanceUntilIdle()
        assertEquals(
            listOf(ViewEventKind.OPEN, ViewEventKind.PLAY),
            repo.viewEventCalls.map { it.kind },
        )
        assertEquals(open.sessionId, repo.viewEventCalls.last().sessionId) // 会话同 sessionId

        // onPause：dwell 第一段 flush（带秒数）+ 无重复 play/open
        vm.onScreenPaused()
        advanceUntilIdle()
        assertEquals(ViewEventKind.DWELL, repo.viewEventCalls.last().kind)
        assertNotNull(repo.viewEventCalls.last().dwellSeconds)

        // resume 开新段（分段累加口径）；onDispose 的 leave flush 第二段——dwell 两条
        vm.onScreenResumed()
        vm.onScreenDisposed()
        advanceUntilIdle()
        assertEquals(
            listOf(ViewEventKind.OPEN, ViewEventKind.PLAY, ViewEventKind.DWELL, ViewEventKind.DWELL),
            repo.viewEventCalls.map { it.kind },
        )
        assertNotNull(repo.viewEventCalls.last().dwellSeconds)
        // 全部事件同一 sessionId + 同一 assetId（open/play 去重键；dwell 同会话归组）
        assertTrue(repo.viewEventCalls.all { it.sessionId == open.sessionId && it.assetId == "b" })
    }

    // ---------- 进度上报接线（3d）：节流放行 + force 立即补报 ----------

    @Test
    fun `进度上报 - 首次放行窗口内吞掉force补报用最新值`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b", mediaType = MediaKind.VIDEO) }
        val vm = viewModel(repo)
        // 假时钟策略（项目惯例：纯逻辑时间源注入，测试不依赖真实时钟）
        var now = 0L
        vm.progressThrottle = ProgressThrottlePolicy(nowMs = { now })
        advanceUntilIdle()

        // 首次 tick 必放行（5.0）
        vm.onPositionChanged(5.0)
        advanceUntilIdle()
        assertEquals(listOf(5.0), repo.progressCalls)

        // 5s 窗口内 tick 被吞（最新值 6.0 覆盖，不发）
        vm.onPositionChanged(6.0)
        advanceUntilIdle()
        assertEquals(listOf(5.0), repo.progressCalls)

        // 窗口经过后再 tick，放行最新值
        now += ProgressThrottlePolicy.PROGRESS_REPORT_INTERVAL_MS
        vm.onPositionChanged(7.0)
        advanceUntilIdle()
        assertEquals(listOf(5.0, 7.0), repo.progressCalls)

        // force 立即补报（暂停/离开路径）：窗口内也放行，用策略内保留的最新值
        vm.onPositionChanged(8.0)
        vm.flushProgressNow()
        advanceUntilIdle()
        assertEquals(listOf(5.0, 7.0, 8.0), repo.progressCalls)
    }

    // ---------- 时间轴标签接线（3d）：加载 / 增 / 删 / 刷新 ----------

    @Test
    fun `时间轴标签 - 视频加载增删刷新且图片不加载`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", mediaType = MediaKind.VIDEO)
            timelineTagStore += TimelineTag(id = "t1", timeMillis = 1_000L, name = "\u2764\uFE0F")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        // 视频资产：详情落地即拉标签
        assertEquals(listOf("t1"), vm.uiState.value.timelineTags.map { it.id })

        // 增：trim 非空才发；成功重拉刷新（fake 存储真实变异）
        vm.addTimelineTag(timeMillis = 2_500L, name = "  ⭐ 名场面  ")
        advanceUntilIdle()
        assertEquals(2_500L to "⭐ 名场面", repo.addTimelineTagCalls.single())
        assertEquals(listOf("t1", "tag-1"), vm.uiState.value.timelineTags.map { it.id })

        // 空白名不发请求
        vm.addTimelineTag(timeMillis = 3_000L, name = "   ")
        advanceUntilIdle()
        assertEquals(1, repo.addTimelineTagCalls.size)

        // 删：成功重拉刷新
        vm.deleteTimelineTag("t1")
        advanceUntilIdle()
        assertEquals(listOf("t1"), repo.deleteTimelineTagCalls)
        assertEquals(listOf("tag-1"), vm.uiState.value.timelineTags.map { it.id })
    }

    @Test
    fun `时间轴标签 - 图片资产不拉取`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.timelineTags.isEmpty())
        // 图片无 timelineTags 取数（打点 open 仍发生，不在此断言范围）
    }

    @Test
    fun `时间轴标签 - 添加失败进errorMessage且列表不假刷新`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", mediaType = MediaKind.VIDEO)
            addTimelineTagError = RuntimeException("add boom")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.addTimelineTag(timeMillis = 1_000L, name = "名场面")
        advanceUntilIdle()
        // 失败进 errorMessage 横幅（与互动行同一反馈通道）
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.startsWith("时间轴标签添加失败"))
        // 失败不重拉：本地列表不假成功
        assertTrue(vm.uiState.value.timelineTags.isEmpty())
    }

    @Test
    fun `时间轴标签 - 删除失败进errorMessage且列表不假删`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", mediaType = MediaKind.VIDEO)
            timelineTagStore += TimelineTag(id = "t1", timeMillis = 1_000L, name = "\u2764\uFE0F")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.uiState.value.timelineTags.map { it.id })

        repo.deleteTimelineTagError = RuntimeException("delete boom")
        vm.deleteTimelineTag("t1")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.startsWith("时间轴标签删除失败"))
        // 失败不重拉：芯片不假消失
        assertEquals(listOf("t1"), vm.uiState.value.timelineTags.map { it.id })
    }

    @Test
    fun `时间轴标签 - 加载失败静默不开弹窗不崩`() = runTest(mainDispatcherRule.testDispatcher) {
        // 标签是增强体验：GET 失败静默（不进 errorMessage、不开标签弹窗、不崩、主内容照常落地）
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", mediaType = MediaKind.VIDEO)
            timelineTagsError = RuntimeException("load boom")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.timelineTags.isEmpty())
        assertFalse(vm.uiState.value.tagSheetOpen)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("b", vm.uiState.value.asset?.id) // 主内容不受影响
    }
}
