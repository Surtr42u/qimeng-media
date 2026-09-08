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
import media.qimeng.app.core.data.repository.LikeMutationTracker
import media.qimeng.app.core.data.repository.MediaBatchIndex
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
 *
 * 任务I I1 追加：①下拉刷新清空三 tab 缓存（当前 tab 立即重拉、另两 tab 标脏切入懒重拉，
 * GUIDE_UI §下拉刷新 L86）；②点赞变更指纹返回重排（LikeMutationTracker，GUIDE_UI L89，
 * detail 侧上报点归 I7 批）。
 */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class RankingsCall(val period: RankingPeriod, val gate: CompletableDeferred<List<MediaAsset>>)

        val rankingsCalls = mutableListOf<RankingsCall>()

        /** 推荐流调用记录（seed 序列，I1 刷新/指纹重拉断言用） */
        val recommendationsCalls = mutableListOf<Long>()

        /** COS 流调用记录（I1 刷新懒重拉断言用） */
        val assetsCalls = mutableListOf<AssetQuery>()

        /** 推荐流/COS 流返回值可配（I1 需要非空数据验证缓存清空；默认空=既有用例行为不变） */
        var recommendationsResult: List<MediaAsset> = emptyList()
        var assetsResult: AssetPageResult = AssetPageResult(items = emptyList(), nextCursor = null, totalMatched = 0)

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            assetsCalls += query
            return assetsResult
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult =
            FacetsResult(partitions = emptyList(), authors = emptyList(), characters = emptyList(), types = emptyList())

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> {
            recommendationsCalls += seed
            return recommendationsResult
        }

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

    private fun viewModel(
        repo: FakeMediaRepository,
        likeTracker: LikeMutationTracker = LikeMutationTracker(),
    ): HomeViewModel = HomeViewModel(
        mediaRepository = repo,
        gridPrefs = FakeGridPrefsRepository(),
        batchIndex = MediaBatchIndex(),
        likeMutationTracker = likeTracker,
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

    // ---------- 任务I I1：下拉刷新清空三 tab 缓存（GUIDE_UI §下拉刷新 L86） ----------

    @Test
    fun `下拉刷新清空三tab缓存 - 当前tab立即重拉，另两tab标脏且切入时懒重拉`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            repo.recommendationsResult = listOf(asset("r1"), asset("r2"))
            repo.assetsResult = AssetPageResult(items = listOf(asset("c1")), nextCursor = "cur", totalMatched = 1)
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertEquals(listOf("r1", "r2"), viewModel.uiState.value.recommend.pulled.map { it.id })

            // 三 tab 都加载过：COS、RANK 各自切入加载
            viewModel.switchTab(HomeTab.COS)
            advanceUntilIdle()
            assertEquals(listOf("c1"), viewModel.uiState.value.cos.items.map { it.id })
            viewModel.switchTab(HomeTab.RANK)
            advanceUntilIdle()
            repo.rankingsCalls[0].gate.complete(listOf(asset("k1")))
            advanceUntilIdle()
            assertEquals(listOf("k1"), viewModel.uiState.value.rank.items.map { it.id })

            // 回推荐 tab 下拉刷新：当前 tab 立即重拉（换 seed），另两 tab 缓存清空标脏
            viewModel.switchTab(HomeTab.RECOMMEND)
            advanceUntilIdle()
            val recommendCallsBefore = repo.recommendationsCalls.size
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(recommendCallsBefore + 1, repo.recommendationsCalls.size) // 当前 tab 立即重拉
            assertEquals(
                repo.recommendationsCalls[0] + 1,
                repo.recommendationsCalls.last(),
            ) // 刷新路径换 seed（B 站式）
            assertFalse(viewModel.uiState.value.cos.loaded) // 另两 tab 标脏
            assertTrue(viewModel.uiState.value.cos.items.isEmpty())
            assertFalse(viewModel.uiState.value.rank.loaded)
            assertTrue(viewModel.uiState.value.rank.items.isEmpty())

            // 切入 COS：懒重拉（不复用旧缓存）
            val assetsCallsBefore = repo.assetsCalls.size
            viewModel.switchTab(HomeTab.COS)
            advanceUntilIdle()
            assertEquals(assetsCallsBefore + 1, repo.assetsCalls.size)
            assertTrue(viewModel.uiState.value.cos.loaded)

            // 切入 RANK：懒重拉并落地新数据
            viewModel.switchTab(HomeTab.RANK)
            advanceUntilIdle()
            assertEquals(2, repo.rankingsCalls.size)
            repo.rankingsCalls[1].gate.complete(listOf(asset("k2")))
            advanceUntilIdle()
            assertEquals(listOf("k2"), viewModel.uiState.value.rank.items.map { it.id })
        }

    @Test
    fun `下拉刷新在榜单tab - 推荐与COS缓存标脏，榜单照常刷新`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        repo.recommendationsResult = listOf(asset("r1"))
        repo.assetsResult = AssetPageResult(items = listOf(asset("c1")), nextCursor = null, totalMatched = 1)
        val viewModel = viewModel(repo)
        advanceUntilIdle()
        viewModel.switchTab(HomeTab.COS)
        advanceUntilIdle()
        viewModel.switchTab(HomeTab.RANK)
        advanceUntilIdle()
        repo.rankingsCalls[0].gate.complete(listOf(asset("k1")))
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.recommend.loaded) // 推荐缓存标脏
        assertTrue(viewModel.uiState.value.recommend.pulled.isEmpty())
        assertFalse(viewModel.uiState.value.cos.loaded) // COS 缓存标脏
        assertEquals(2, repo.rankingsCalls.size) // 当前 tab（榜单）立即重拉

        repo.rankingsCalls[1].gate.complete(listOf(asset("k2")))
        advanceUntilIdle()
        assertEquals(listOf("k2"), viewModel.uiState.value.rank.items.map { it.id })
    }

    // ---------- 任务I I1：点赞变更指纹返回重排（GUIDE_UI L89；上报点归 I7 批） ----------

    @Test
    fun `点赞指纹变化 - 返回首页重拉当前推荐tab，无变更保持原样`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        repo.recommendationsResult = listOf(asset("r1"))
        val tracker = LikeMutationTracker()
        val viewModel = viewModel(repo, tracker)
        advanceUntilIdle()
        assertEquals(1, repo.recommendationsCalls.size)

        // 首次 ON_RESUME：基线采纳，进页不误刷
        viewModel.onHomeResumed()
        advanceUntilIdle()
        assertEquals(1, repo.recommendationsCalls.size)

        // 无点赞变更的返回（如浏览返回）：指纹不变不重拉——「浏览退出保持原样」
        viewModel.onHomeResumed()
        advanceUntilIdle()
        assertEquals(1, repo.recommendationsCalls.size)

        // 详情页点赞（I7 上报点）→ 返回首页：指纹变化 → 当前 tab（推荐）重拉且换 seed
        tracker.onLikeMutated()
        viewModel.onHomeResumed()
        advanceUntilIdle()
        assertEquals(2, repo.recommendationsCalls.size)
        assertEquals(
            repo.recommendationsCalls[0] + 1,
            repo.recommendationsCalls[1],
        ) // 同 seed 服务端返回同一打散序，重排必须换 seed 才可见

        // 再次 ON_RESUME（无新变更）：不重拉
        viewModel.onHomeResumed()
        advanceUntilIdle()
        assertEquals(2, repo.recommendationsCalls.size)
    }

    @Test
    fun `点赞指纹变化 - 榜单tab返回重拉排行榜`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val tracker = LikeMutationTracker()
        val viewModel = viewModel(repo, tracker)
        advanceUntilIdle()

        viewModel.switchTab(HomeTab.RANK)
        advanceUntilIdle()
        repo.rankingsCalls[0].gate.complete(listOf(asset("k1")))
        advanceUntilIdle()
        assertEquals(listOf("k1"), viewModel.uiState.value.rank.items.map { it.id })

        viewModel.onHomeResumed() // 基线
        tracker.onLikeMutated()
        viewModel.onHomeResumed() // 指纹变化 → 当前 tab（排行榜）重拉
        advanceUntilIdle()
        assertEquals(2, repo.rankingsCalls.size)
        repo.rankingsCalls[1].gate.complete(listOf(asset("k2"), asset("k3")))
        advanceUntilIdle()
        assertEquals(listOf("k2", "k3"), viewModel.uiState.value.rank.items.map { it.id })
    }
}
