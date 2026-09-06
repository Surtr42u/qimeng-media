package media.qimeng.app.feature.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.SearchHistoryRepository
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 搜索页 ViewModel 单测（2026-09-06 审查清偿）：与相册页同族的查询代际防乱序——
 * 在途旧代响应（含失败）不覆盖新查询/筛选态（提交/切分区/切类型三个入口）；
 * 翻页防重语义保持原状。
 * 时序构造与 AlbumViewModelTest 同款：assets 请求挂闸门，由测试决定放行顺序。
 */
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class AssetsCall(val query: AssetQuery, val gate: CompletableDeferred<AssetPageResult>)

        val assetsCalls = mutableListOf<AssetsCall>()

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            val call = AssetsCall(query, CompletableDeferred())
            assetsCalls += call
            return call.gate.await()
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult =
            FacetsResult(partitions = emptyList(), authors = emptyList(), characters = emptyList(), types = emptyList())

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null
    }

    private class FakeSearchHistoryRepository : SearchHistoryRepository {
        private val _history = MutableStateFlow<List<String>>(emptyList())
        val recorded = mutableListOf<String>()
        var cleared = 0

        override val history: Flow<List<String>> = _history

        override suspend fun record(query: String) {
            recorded += query
        }

        override suspend fun clear() {
            cleared += 1
        }
    }

    // ---------- 造数 ----------

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

    private fun page(items: List<MediaAsset>, nextCursor: String? = null, total: Int? = null) =
        AssetPageResult(items = items, nextCursor = nextCursor, totalMatched = total)

    private fun viewModel(
        repo: FakeMediaRepository,
        historyRepo: FakeSearchHistoryRepository,
    ): SearchViewModel = SearchViewModel(
        mediaRepository = repo,
        searchHistoryRepository = historyRepo,
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    // ---------- 用例 ----------

    @Test
    fun `查询代际防乱序 - 在途A未归时切分区COS，A迟到响应被丢弃，COS结果落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()
            assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
            assertTrue(repo.assetsCalls.isEmpty())

            // 提交查询：切结果态 + 记历史 + 首屏在途
            viewModel.submit("megumi")
            advanceUntilIdle()
            assertEquals(SearchPhase.RESULT, viewModel.uiState.value.phase)
            assertEquals("megumi", repo.assetsCalls[0].query.q)
            assertEquals(true, repo.assetsCalls[0].query.includeCos) // 缺省全部=显式 includeCos=1
            assertEquals(listOf("megumi"), historyRepo.recorded)
            assertTrue(viewModel.uiState.value.isLoading)

            // 切分区：旧代仍在途（修复前此处会被 isLoading 拦截，第二次请求根本不发出）
            viewModel.selectPartition(Zone.COS)
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
            assertEquals(true, repo.assetsCalls[1].query.cosOnly)
            assertNull(repo.assetsCalls[1].query.includeCos)

            // 旧代 A 迟到归位：不得落地
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("old-a")), total = 1))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading) // 新代仍在途，loading 不被旧代收走

            // 新代归位：落地
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("new-b")), total = 7))
            advanceUntilIdle()
            assertEquals(listOf("new-b"), viewModel.uiState.value.items.map { it.id })
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `旧代查询失败不污染新查询态 - 连续提交与切类型的迟到失败均被丢弃`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            // 三代连续：提交 A → 在途时提交 B（重载不被防重拦截）→ 在途时切类型
            viewModel.submit("a")
            advanceUntilIdle()
            viewModel.submit("b")
            advanceUntilIdle()
            viewModel.selectMediaType(MediaKind.VIDEO)
            advanceUntilIdle()
            assertEquals(3, repo.assetsCalls.size)
            assertEquals("b", repo.assetsCalls[1].query.q)
            assertEquals("b", repo.assetsCalls[2].query.q)
            assertEquals(MediaKind.VIDEO, repo.assetsCalls[2].query.mediaType)

            // 前两代失败迟到：不弹错误、不收 loading
            repo.assetsCalls[0].gate.completeExceptionally(RuntimeException("stale a"))
            repo.assetsCalls[1].gate.completeExceptionally(RuntimeException("stale b"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.isLoading)

            // 当代成功落地：正常展示
            repo.assetsCalls[2].gate.complete(page(items = listOf(asset("c"))))
            advanceUntilIdle()
            assertEquals(listOf("c"), viewModel.uiState.value.items.map { it.id })
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `翻页在途防重保持 - 同代在途不重复请求，追加不丢页`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val historyRepo = FakeSearchHistoryRepository()
        val viewModel = viewModel(repo, historyRepo)
        advanceUntilIdle()

        viewModel.submit("q")
        advanceUntilIdle()
        repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a")), nextCursor = "c1", total = 2))
        advanceUntilIdle()
        assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })

        // 翻页在途时再次触发：被防重拦截，不重复请求
        viewModel.onNearBottom()
        viewModel.onNearBottom()
        advanceUntilIdle()
        assertEquals(2, repo.assetsCalls.size)
        assertEquals("c1", repo.assetsCalls[1].query.cursor)

        // 翻页归位：追加不丢页
        repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b")), nextCursor = null))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.nextCursor)
    }
}
