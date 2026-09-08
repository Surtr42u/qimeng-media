package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 作者集合页 ViewModel 单测（任务G G1b）：锁定取数参数口径（authorId 精确过滤 +
 * includeCos 恒 true——COS 作者文件不显式包含则列表恒空）与 cursor 增量分页链。
 */
class AuthorCollectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMediaRepository(private val endOfList: Boolean = false) : MediaRepository {
        val assetsCalls = mutableListOf<AssetQuery>()
        var assetsError: Exception? = null

        /** 造数在 fake 内（非 inner class 不能用外层实例方法） */
        private fun asset(id: String) = MediaAsset(
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

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            assetsCalls += query
            assetsError?.let { throw it }
            return AssetPageResult(
                items = listOf(asset("a-${assetsCalls.size}")),
                nextCursor = if (endOfList) null else "c${assetsCalls.size}",
                totalMatched = 42,
            )
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult = FacetsResult(
            partitions = emptyList(),
            authors = emptyList(),
            characters = emptyList(),
            types = emptyList(),
        )

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null
    }

    private fun viewModel(
        repo: FakeMediaRepository,
        authorId: String? = "22222222-2222-2222-2222-222222222222",
        authorName: String? = "蠢沫",
    ): AuthorCollectionViewModel = AuthorCollectionViewModel(
        mediaRepository = repo,
        origUrlResolver = object : media.qimeng.app.core.data.repository.AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
        savedStateHandle = SavedStateHandle(
            buildMap {
                authorId?.let { put(AuthorCollectionRoutes.KEY_AUTHOR_ID, it) }
                authorName?.let { put(AuthorCollectionRoutes.KEY_AUTHOR_NAME, it) }
            },
        ),
    )

    @Test
    fun `取数参数 - authorId精确过滤 includeCos恒true 分页大小单源`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        viewModel(repo)
        advanceUntilIdle()
        assertEquals(1, repo.assetsCalls.size)
        val query = repo.assetsCalls.first()
        assertEquals("22222222-2222-2222-2222-222222222222", query.authorId)
        assertEquals(true, query.includeCos)
        assertEquals(LIST_PAGE_SIZE, query.limit)
        assertNull(query.cursor) // 首载无 cursor
    }

    @Test
    fun `onNearBottom - 带nextCursor增量取数 追加不替换`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onNearBottom()
        advanceUntilIdle()
        val second = repo.assetsCalls[1]
        assertEquals("c1", second.cursor)
        assertEquals(listOf("a-1", "a-2"), vm.uiState.value.items.map { it.id })
        assertEquals(42, vm.uiState.value.totalMatched)
    }

    @Test
    fun `onNearBottom - 无nextCursor不取数`() = runTest(mainDispatcherRule.testDispatcher) {
        // 末页语义：fake 回 nextCursor=null，到底后 onNearBottom 不再发请求
        val repo = FakeMediaRepository(endOfList = true)
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onNearBottom()
        assertEquals(1, repo.assetsCalls.size) // 只有 init 首载
    }

    @Test
    fun `加载失败 - 列表失败文案 兜底不清空已加载项`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        repo.assetsError = RuntimeException("boom")
        vm.onNearBottom() // 第二页失败
        advanceUntilIdle()
        assertEquals("加载失败，请下拉重试", vm.uiState.value.errorMessage)
        assertEquals(listOf("a-1"), vm.uiState.value.items.map { it.id }) // 首页内容保留
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `缺参容错 - authorId缺失不发请求空态`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo, authorId = null, authorName = null)
        advanceUntilIdle()
        assertTrue(repo.assetsCalls.isEmpty())
        assertEquals("", vm.authorName)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `路由名透传 - authorName不参与取数仅展示`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo, authorName = "某作者")
        advanceUntilIdle()
        assertEquals("某作者", vm.authorName)
        assertEquals("22222222-2222-2222-2222-222222222222", repo.assetsCalls.first().authorId)
    }
}
