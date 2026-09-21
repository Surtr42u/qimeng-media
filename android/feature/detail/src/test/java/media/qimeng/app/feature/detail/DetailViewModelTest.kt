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
import media.qimeng.app.core.data.repository.FavoriteMutationTracker
import media.qimeng.app.core.data.repository.LikeMutationTracker
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MoveConflictException
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.app.feature.detail.playback.ProgressThrottlePolicy

/**
 * 详情页 ViewModel 单测（M4-3 3a）：详情加载/批次序号、互动回填与失败保持、
 * 标签管理流（拉池/即时勾选/新建/整体替换+重拉详情）、路由缺参错误态。
 * fake 全手写（禁 MockK），范式照 HomeViewModelTest + core/testing MainDispatcherRule。
 * （任务W W3：「接下来播放」推荐流数据链随推荐栏退役整段删除，对应用例一并清偿。）
 */
class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身 ----------

    private class FakeDetailRepository : DetailRepository {
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

        var tagPool: List<TagChip> = emptyList()
        var createTagError: Exception? = null
        var createTagResult: TagChip? = null
        val createdTagNames = mutableListOf<String>()

        var replaceTagsError: Exception? = null
        val replaceTagsCalls = mutableListOf<List<String>>()

        // N4 I7b：单条解绑（调用记录 + 失败注入）
        var unbindTagError: Exception? = null
        val unbindTagCalls = mutableListOf<Pair<String, String>>() // assetId to tagName

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

        override suspend fun unbindTag(assetId: String, tagName: String) {
            unbindTagError?.let { throw it }
            unbindTagCalls += assetId to tagName
        }

        // 文件操作（任务G G1b）：调用记录 + 失败/领域冲突注入
        data class MoveCall(val assetId: String, val targetDir: String, val newName: String?)

        val moveCalls = mutableListOf<MoveCall>()
        var moveError: Exception? = null
        val deleteCalls = mutableListOf<String>()
        var deleteError: Exception? = null

        override suspend fun moveAsset(assetId: String, targetDir: String, newName: String?) {
            moveError?.let { throw it }
            moveCalls += MoveCall(assetId, targetDir, newName)
        }

        override suspend fun deleteAsset(assetId: String) {
            deleteError?.let { throw it }
            deleteCalls += assetId
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
        thumbUrlMd: String? = null,
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
        thumbUrlMd = thumbUrlMd,
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

    private fun savedHandle(assetId: String?): SavedStateHandle =
        if (assetId == null) SavedStateHandle() else SavedStateHandle(mapOf(DetailRoutes.KEY_ASSET_ID to assetId))

    private fun viewModel(
        repo: FakeDetailRepository,
        authorRepo: FakeAuthorRepository = FakeAuthorRepository(),
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
        assetId: String? = "b",
        imageDimCache: DetailImageDimCache = DetailImageDimCache(),
        likeTracker: LikeMutationTracker = LikeMutationTracker(),
        favoriteTracker: FavoriteMutationTracker = FavoriteMutationTracker(),
    ): DetailViewModel = DetailViewModel(
        detailRepository = repo,
        authorRepository = authorRepo,
        batchIndex = batchIndex,
        imageDimCache = imageDimCache,
        likeMutationTracker = likeTracker,
        favoriteMutationTracker = favoriteTracker,
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

    // 协议批 2026-09-18：thumbUrlMd（md 档，预生成）随详情落地透出到 UiState——海报
    // 「md 先行」换图策略的 VM 侧数据源（absolutize 映射口径由 core:data SdkDetailMappersTest
    // 锁定，此处断言领域模型经 VM 全链透传不丢字段）
    @Test
    fun `详情模型含thumbUrlMd - 随UiState透出供海报md先行`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", thumbUrl = "https://x/b.lg", thumbUrlMd = "https://x/b.md")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("https://x/b.md", vm.uiState.value.asset?.thumbUrlMd)
        assertEquals("https://x/b.lg", vm.uiState.value.asset?.thumbUrl)
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
    fun `点赞成功上报本地点赞变更指纹 - 失败不上报`() = runTest(mainDispatcherRule.testDispatcher) {
        // 任务I I7（LikeMutationTracker KDoc 口径）：详情页点赞成功处 onLikeMutated——
        // 首页返回重拉（GUIDE_UI L89 点赞后返回自动重排）的感知源；失败不上报
        val tracker = LikeMutationTracker()
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo, likeTracker = tracker)
        advanceUntilIdle()
        assertEquals(0L, tracker.fingerprint().likeVersion) // 初始零变更

        repo.likeResult = LikeToggleResult(likedToday = true, likeCount = 1)
        vm.toggleLike()
        advanceUntilIdle()
        assertEquals(1L, tracker.fingerprint().likeVersion) // 成功 → likeVersion 递增
        assertTrue(tracker.fingerprint().lastMutatedAtMs > 0L)

        repo.likeError = RuntimeException("like boom")
        vm.toggleLike()
        advanceUntilIdle()
        assertEquals(1L, tracker.fingerprint().likeVersion) // 失败 → 指纹不动
    }

    @Test
    fun `toggleFavorite成功本地翻转 - 失败不变加报错`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b", isFavorite = false) }
        // 任务V V1：收藏成功处上报 FavoriteMutationTracker（收藏页返回重拉的感知源），失败不上报
        val favoriteTracker = FavoriteMutationTracker()
        val vm = viewModel(repo, favoriteTracker = favoriteTracker)
        advanceUntilIdle()

        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(repo.favoriteCalls.single().second) // 目标态 = 本地翻转
        assertTrue(vm.uiState.value.asset?.isFavorite == true)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(1L, favoriteTracker.fingerprint().favoriteVersion) // 成功 → 指纹递增

        repo.favoriteError = RuntimeException("fav boom")
        vm.toggleFavorite()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.asset?.isFavorite == true) // 失败不翻转
        assertNotNull(vm.uiState.value.errorMessage)
        assertEquals(1L, favoriteTracker.fingerprint().favoriteVersion) // 失败 → 指纹不动
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

        // retry 走 loadDetail→schedulePreload：prefetchedIds 去重，邻位不重拉
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
    fun `标签即时解绑 - chip关闭立即DELETE乐观移除成功重拉`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", tags = listOf(DetailTag("t1", "甲"), DetailTag("t2", "乙")))
            tagPool = listOf(TagChip("t1", "甲"), TagChip("t2", "乙"))
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openTagSheet()
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2"), vm.uiState.value.selectedTagIds)

        // 模拟服务端删后状态：重拉详情将只含 t2（fake 详情不可变，预置删后回包）
        repo.detailResult = detail("b", tags = listOf(DetailTag("t2", "乙")))

        // 关闭图标：乐观移除 + DELETE 单条解绑（N4 I7b）
        vm.unbindTag("t1")
        advanceUntilIdle()
        assertEquals(listOf("b" to "甲"), repo.unbindTagCalls) // DELETE 按标签名（协议 {tag} 位）
        assertEquals(listOf("t2"), vm.uiState.value.asset!!.tags.map { it.id }) // 乐观移除已生效
        assertEquals(listOf("t2"), vm.uiState.value.selectedTagIds) // 草稿勾选同步收缩（保存不会复活）
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.unbindingTagIds.isEmpty())
        assertEquals(2, repo.detailCallCount) // 成功后重拉详情对齐服务端最终序
    }

    @Test
    fun `标签即时解绑 - 失败回滚乐观态加错误提示`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", tags = listOf(DetailTag("t1", "甲")))
            tagPool = listOf(TagChip("t1", "甲"))
            unbindTagError = java.io.IOException("网络炸了")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openTagSheet()
        advanceUntilIdle()

        vm.unbindTag("t1")
        advanceUntilIdle()
        // 回滚：标签回到当前列表、勾选复位、在途清空、错误横幅提示
        assertEquals(listOf("t1"), vm.uiState.value.asset!!.tags.map { it.id })
        assertEquals(listOf("t1"), vm.uiState.value.selectedTagIds)
        assertTrue(vm.uiState.value.unbindingTagIds.isEmpty())
        assertTrue(vm.uiState.value.errorMessage!!.startsWith("解除标签失败"))
    }

    @Test
    fun `标签即时解绑 - 同标签在途防重`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b", tags = listOf(DetailTag("t1", "甲")))
            tagPool = listOf(TagChip("t1", "甲"))
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openTagSheet()
        advanceUntilIdle()

        // 闸门挂起：在途期间同 id 再点被忽略（每次调用挂一个闸门，这里直接数调用数）
        vm.unbindTag("t1")
        val callsAtFlight = repo.unbindTagCalls.size
        vm.unbindTag("t1")
        assertEquals(callsAtFlight, repo.unbindTagCalls.size)
        advanceUntilIdle()
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

    // ---------- 文件操作（任务G G1b：POST /move + DELETE=回收站） ----------

    @Test
    fun `整理成功 - 参数透传 关弹窗 回调变化维度 重拉详情`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b").copy(directory = "旧目录") }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openMoveSheet()
        assertTrue(vm.uiState.value.moveSheetOpen)

        var movedArg = false
        var renamedArg = false
        vm.moveAsset(targetDir = "新目录", newName = "新名.jpg") { moved, renamed ->
            movedArg = moved
            renamedArg = renamed
        }
        advanceUntilIdle()
        // 仓库收到原始参数（改名=新名、移动=新目录——一个端点两用）
        assertEquals(
            FakeDetailRepository.MoveCall("b", "新目录", "新名.jpg"),
            repo.moveCalls.single(),
        )
        // 成功收尾：关弹窗、清失败文案、回调变化维度（toast 文案分流用）
        assertFalse(vm.uiState.value.moveSheetOpen)
        assertNull(vm.uiState.value.moveError)
        assertFalse(vm.uiState.value.fileOpsPending)
        assertTrue(movedArg)
        assertTrue(renamedArg)
        // 成功后重拉详情（directory/fileName 落新值）：init 1 次 + move 后 1 次
        assertEquals(2, repo.detailCallsById["b"])
    }

    @Test
    fun `整理失败 - 弹窗保持打开 409同名走领域文案`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b")
            moveError = MoveConflictException()
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openMoveSheet()
        vm.moveAsset(targetDir = "x", newName = null) { _, _ -> }
        advanceUntilIdle()
        // 失败：弹窗保持打开（Web toast 后弹窗可重试同语义），失败文案在弹窗内
        assertTrue(vm.uiState.value.moveSheetOpen)
        assertEquals("目标位置已有同名文件，请换个名字或目录", vm.uiState.value.moveError)
        assertFalse(vm.uiState.value.fileOpsPending)
    }

    @Test
    fun `整理失败 - 非同名冲突透传message`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b")
            moveError = RuntimeException("network down")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openMoveSheet()
        vm.moveAsset(targetDir = "x", newName = null) { _, _ -> }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.moveError!!.startsWith("整理失败："))
        assertTrue(vm.uiState.value.moveError!!.endsWith("network down"))
    }

    @Test
    fun `删除成功 - 回调onDeleted交UI层收尾 关确认弹窗`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply { detailResult = detail("b") }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openDeleteConfirm()
        assertTrue(vm.uiState.value.deleteConfirmOpen)

        var deleted = false
        vm.deleteAsset { deleted = true }
        advanceUntilIdle()
        assertEquals(listOf("b"), repo.deleteCalls)
        assertTrue(deleted) // UI 层据此 toast + onBack()（Web navigate(-1) 同收尾）
        assertFalse(vm.uiState.value.deleteConfirmOpen)
        assertFalse(vm.uiState.value.fileOpsPending)
    }

    @Test
    fun `删除失败 - 关弹窗进errorMessage横幅`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailResult = detail("b")
            deleteError = RuntimeException("boom")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openDeleteConfirm()
        vm.deleteAsset { }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.deleteConfirmOpen)
        assertEquals("移入回收站失败，请重试", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.fileOpsPending)
    }

    @Test
    fun `解绑失败 - 只回滚本标签乐观态不整覆盖（审计R13）`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailById["b"] = detail("b", tags = listOf(DetailTag("t1", "甲"), DetailTag("t2", "乙")))
            tagPool = listOf(TagChip("t1", "甲"), TagChip("t2", "乙"))
            unbindTagError = RuntimeException("net down")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        // 先开标签面板：tagPool/selectedTagIds 由 openTagSheet 装配（unbind 前置条件）
        vm.openTagSheet()
        advanceUntilIdle()
        vm.unbindTag("t1")
        // 乐观态落地后、失败回调执行前：用户在标签面板又把 t2 的草稿勾选去掉
        // （旧实现失败回滚整覆盖快照，会把这个窗口期内的用户操作一并吞掉）
        vm.toggleTagSelection("t2")
        advanceUntilIdle()

        val s = vm.uiState.value
        // t1 被回滚回标签列表（乐观移除撤销）、不在途、错误文案在
        assertEquals(listOf("t1", "t2"), s.asset?.tags?.map { it.id })
        assertTrue(s.unbindingTagIds.isEmpty())
        assertNotNull(s.errorMessage)
        // 关键回归：窗口期的用户草稿操作保留（t2 的勾选移除不被快照复活）
        assertFalse("t2" in s.selectedTagIds)
        // t1 恢复勾选（快照里本有 t1，乐观解勾被回滚）
        assertTrue("t1" in s.selectedTagIds)
    }

    @Test
    fun `解绑失败 - 窗口期重新勾回同一标签 回滚不产生重复id（维护批2026-09-21）`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeDetailRepository().apply {
            detailById["b"] = detail("b", tags = listOf(DetailTag("t1", "甲"), DetailTag("t2", "乙")))
            tagPool = listOf(TagChip("t1", "甲"), TagChip("t2", "乙"))
            unbindTagError = RuntimeException("net down")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openTagSheet()
        advanceUntilIdle()
        vm.unbindTag("t1")
        // 乐观解勾落地后、失败回调执行前：用户在弹窗把 t1 重新勾回（草稿已含 t1）
        vm.toggleTagSelection("t1")
        advanceUntilIdle()

        val s = vm.uiState.value
        // 回滚补回不与窗口期草稿叠加出重复 id（List + element 不去重；重复 id
        // 会随 saveTags 整体替换 PUT 上行）
        assertEquals(listOf("t2", "t1"), s.selectedTagIds)
    }

    @Test
    fun `尺寸缓存 - 超上限淘汰最老条目（审计R13）`() {
        val cache = DetailImageDimCache()
        for (i in 0 until 600) cache.put("k$i", ImageDims(10, 10))
        assertNull(cache.dimsOf("k0")) // 最老条目被 LRU 淘汰（上限 512）
        assertNotNull(cache.dimsOf("k599")) // 最新条目保留
        // 命中续期：把 k100 读成「最近使用」，再灌 100 条新键——k100 存活，
        // 未续期的 k90 被挤掉
        assertNotNull(cache.dimsOf("k100"))
        for (i in 600 until 700) cache.put("k$i", ImageDims(10, 10))
        assertNotNull(cache.dimsOf("k100"))
        assertNull(cache.dimsOf("k90"))
    }
}
