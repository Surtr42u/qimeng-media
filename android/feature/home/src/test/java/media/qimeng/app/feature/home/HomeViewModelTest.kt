package media.qimeng.app.feature.home

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
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 首页 ViewModel 单测（2026-09-06 审查清偿）：排行榜周期切换的代际防乱序——
 * 在途旧周期响应（含失败）不覆盖新周期；下拉刷新防重语义保持原状。
 * 推荐流/COS 流无代际语义（各自 isLoading 防重 + 纯函数分批），不在此锁定。
 * 时序构造与 AlbumViewModelTest 同款：rankings 请求挂闸门，由测试决定放行顺序。
 */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class RankingsCall(val period: RankingPeriod, val gate: CompletableDeferred<List<MediaAsset>>)

        val rankingsCalls = mutableListOf<RankingsCall>()

        override suspend fun assets(query: AssetQuery): AssetPageResult =
            AssetPageResult(items = emptyList(), nextCursor = null, totalMatched = 0)

        override suspend fun facets(query: FacetsQuery): FacetsResult =
            FacetsResult(partitions = emptyList(), authors = emptyList(), characters = emptyList(), types = emptyList())

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> {
            val call = RankingsCall(period, CompletableDeferred())
            rankingsCalls += call
            return call.gate.await()
        }

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null
    }

    private class FakeGridPrefsRepository : GridPrefsRepository {
        override val homeColumns: Flow<Int> = MutableStateFlow(1)
        override val albumColumns: Flow<Int> = MutableStateFlow(2)

        override suspend fun setHomeColumns(columns: Int) = Unit

        override suspend fun setAlbumColumns(columns: Int) = Unit
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

    private fun viewModel(repo: FakeMediaRepository): HomeViewModel = HomeViewModel(
        mediaRepository = repo,
        gridPrefs = FakeGridPrefsRepository(),
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    // ---------- 用例 ----------

    @Test
    fun `切周期代际防乱序 - 日榜在途时切周榜，日榜迟到响应被丢弃，周榜落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.rank.loaded) // 懒加载：榜单未拉过

            // 切到榜单 tab：日榜首拉在途
            viewModel.switchTab(HomeTab.RANK)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.rank.isLoading)
            assertEquals(1, repo.rankingsCalls.size)
            assertEquals(RankingPeriod.DAY, repo.rankingsCalls[0].period)

            // 切周榜：旧代日榜仍在途（修复前此处会被 isLoading 拦截，周榜请求根本不发出）
            viewModel.selectPeriod(RankingPeriod.WEEK)
            advanceUntilIdle()
            assertEquals(2, repo.rankingsCalls.size)
            assertEquals(RankingPeriod.WEEK, repo.rankingsCalls[1].period)
            assertEquals(RankingPeriod.WEEK, viewModel.uiState.value.rank.period)

            // 旧代日榜迟到归位：不得落地
            repo.rankingsCalls[0].gate.complete(listOf(asset("old-day")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.rank.items.isEmpty())
            assertTrue(viewModel.uiState.value.rank.isLoading) // 新代仍在途，loading 不被旧代收走

            // 新代周榜归位：落地
            repo.rankingsCalls[1].gate.complete(listOf(asset("new-week")))
            advanceUntilIdle()
            assertEquals(listOf("new-week"), viewModel.uiState.value.rank.items.map { it.id })
            assertFalse(viewModel.uiState.value.rank.isLoading)
            assertFalse(viewModel.uiState.value.rank.isRefreshing)
        }

    @Test
    fun `旧周期失败不污染新周期`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        viewModel.switchTab(HomeTab.RANK)
        advanceUntilIdle()
        viewModel.selectPeriod(RankingPeriod.MONTH)
        advanceUntilIdle()
        assertEquals(2, repo.rankingsCalls.size)

        // 旧代失败迟到：不弹错误、不收 loading（错误只属于当前代）
        repo.rankingsCalls[0].gate.completeExceptionally(RuntimeException("stale failure"))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.rank.isLoading)

        // 新代成功落地：正常展示
        repo.rankingsCalls[1].gate.complete(listOf(asset("m")))
        advanceUntilIdle()
        assertEquals(listOf("m"), viewModel.uiState.value.rank.items.map { it.id })
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `下拉刷新防重保持 - 刷新在途时重复触发丢弃，归位不覆盖数据`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        viewModel.switchTab(HomeTab.RANK)
        advanceUntilIdle()
        repo.rankingsCalls[0].gate.complete(listOf(asset("a")))
        advanceUntilIdle()
        assertEquals(listOf("a"), viewModel.uiState.value.rank.items.map { it.id })

        // 下拉刷新在途时再次触发：被防重拦截，不重复请求
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.rankingsCalls.size)
        assertTrue(viewModel.uiState.value.rank.isRefreshing)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.rankingsCalls.size)

        // 刷新归位：替换展示
        repo.rankingsCalls[1].gate.complete(listOf(asset("b")))
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.uiState.value.rank.items.map { it.id })
        assertFalse(viewModel.uiState.value.rank.isRefreshing)
    }
}
