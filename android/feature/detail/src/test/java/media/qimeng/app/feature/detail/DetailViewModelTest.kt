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
import media.qimeng.app.core.testing.MainDispatcherRule

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

        override suspend fun assetDetail(assetId: String): AssetDetail {
            detailCallCount += 1
            detailError?.let { throw it }
            return requireNotNull(detailResult)
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
        thumbUrl = null,
        origUrl = null,
        durationMs = null,
        cosWork = cosWork,
        lastPositionSeconds = null,
        viewCount = null,
        playCount = null,
        width = null,
        height = null,
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
    ): DetailViewModel = DetailViewModel(
        detailRepository = repo,
        authorRepository = authorRepo,
        batchIndex = batchIndex,
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
        assertEquals(1, repo.detailCallCount)
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
}
