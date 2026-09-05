package media.qimeng.app.feature.all

import kotlinx.coroutines.CompletableDeferred
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
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 相册页 ViewModel 单测（自审 P2-1 返工）：筛选代际防乱序——
 * 在途旧代响应（含失败）不覆盖新筛选态；分页/下拉刷新防重语义保持原状。
 * 时序构造：每个仓库请求挂一道闸门，由测试决定放行顺序，精确复现「旧请求迟到归位」。
 */
class AlbumViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class AssetsCall(val query: AssetQuery, val gate: CompletableDeferred<AssetPageResult>)
        data class FacetsCall(val query: FacetsQuery, val gate: CompletableDeferred<FacetsResult>)

        val assetsCalls = mutableListOf<AssetsCall>()
        val facetsCalls = mutableListOf<FacetsCall>()

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            val call = AssetsCall(query, CompletableDeferred())
            assetsCalls += call
            return call.gate.await()
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult {
            val call = FacetsCall(query, CompletableDeferred())
            facetsCalls += call
            return call.gate.await()
        }

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null

        /**
         * 整批放行某一代的四维候选请求（loadFacets 每批固定并行 4 个——AlbumViewModel 实现细节，
         * 只在此测试替身内约定；coroutineScope 须四路全归位才走 onSuccess）
         */
        fun completeFacetsBatch(batch: Int, result: FacetsResult) {
            val base = batch * FACETS_PER_BATCH
            repeat(FACETS_PER_BATCH) { facetsCalls[base + it].gate.complete(result) }
        }

        companion object {
            const val FACETS_PER_BATCH = 4
        }
    }

    private class FakeGridPrefs : GridPrefsRepository {
        override val homeColumns = MutableStateFlow(1)
        override val albumColumns = MutableStateFlow(3)
        override suspend fun setHomeColumns(columns: Int) {
            homeColumns.value = columns
        }

        override suspend fun setAlbumColumns(columns: Int) {
            albumColumns.value = columns
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

    private fun page(items: List<MediaAsset>, total: Int? = null) =
        AssetPageResult(items = items, nextCursor = null, totalMatched = total)

    private fun facets(total: Int) = FacetsResult(
        partitions = listOf(FacetOption(key = "all", name = "全部", fileCount = total, kind = FacetParamKind.SOURCE)),
        authors = emptyList(),
        characters = emptyList(),
        types = emptyList(),
    )

    private fun viewModel(repo: FakeMediaRepository): AlbumViewModel = AlbumViewModel(
        mediaRepository = repo,
        gridPrefs = FakeGridPrefs(),
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    // ---------- 用例 ----------

    @Test
    fun `筛选请求代际防乱序 - 在途A未归时应用筛选B，A迟到响应被丢弃，最终为B结果`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoading) // init 首屏请求在途

            // 筛选 B：媒体类型=图片（旧代 A 仍在途，修复前此处会被 isLoading 拦截丢弃）
            viewModel.selectMediaType(MediaKind.IMAGE)
            advanceUntilIdle()
            assertEquals(MediaKind.IMAGE, repo.assetsCalls[1].query.mediaType)

            // 旧代 A 迟到归位：不得落地（修复前会覆盖 items 与 totalMatched）
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("old-a")), total = 1))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertNull(viewModel.uiState.value.totalMatched)
            assertTrue(viewModel.uiState.value.isLoading) // 新代 B 仍在途，loading 不被旧代收走

            // 新代 B 归位：落地
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("new-b")), total = 7))
            advanceUntilIdle()
            assertEquals(listOf("new-b"), viewModel.uiState.value.items.map { it.id })
            assertEquals(7, viewModel.uiState.value.totalMatched)
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `旧代请求失败不污染新筛选态`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        viewModel.selectMediaType(MediaKind.VIDEO)
        advanceUntilIdle()

        // 旧代失败迟到：不弹错误、不收 loading（错误只属于当前代）
        repo.assetsCalls[0].gate.completeExceptionally(RuntimeException("stale failure"))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.isLoading)

        // 新代成功落地：正常展示
        repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"))))
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `四维候选旧代响应不覆盖新筛选候选`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        viewModel.selectPartition(Zone.COS)
        advanceUntilIdle()

        // 旧代候选整批迟到：计数与候选不落地
        repo.completeFacetsBatch(batch = 0, result = facets(total = 9))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.totalForAllPill)

        // 新代候选整批落地
        repo.completeFacetsBatch(batch = 1, result = facets(total = 3))
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.totalForAllPill)
    }

    @Test
    fun `分页在途防重保持 - 同代在途不重复请求，追加不丢页`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        // 首屏落地（带 cursor，可翻页）
        repo.assetsCalls[0].gate.complete(
            AssetPageResult(items = listOf(asset("a")), nextCursor = "c1", totalMatched = 2),
        )
        repo.completeFacetsBatch(batch = 0, result = facets(total = 1))
        advanceUntilIdle()
        assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })

        // 翻页在途时再次触发：被防重拦截，不重复请求
        viewModel.onNearBottom()
        viewModel.onNearBottom()
        advanceUntilIdle()
        assertEquals(2, repo.assetsCalls.size)

        // 翻页归位：追加不丢页
        repo.assetsCalls[1].gate.complete(
            AssetPageResult(items = listOf(asset("b")), nextCursor = null, totalMatched = 2),
        )
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.nextCursor)
    }
}
