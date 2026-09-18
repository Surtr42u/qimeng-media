package media.qimeng.app.feature.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.network.AuthApi
import media.qimeng.app.core.network.AuthApiFactory
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.ServerReadinessProbe
import media.qimeng.app.core.testing.MainDispatcherRule
import java.io.IOException

/** 探针替身用的假地址（替身 probe 不发真实 IO，任意合法 URL 串即可） */
private const val TEST_FAKE_SERVER_URL = "http://qimeng.test"

/**
 * 首页 ViewModel 单测（2026-09-06 审查清偿）：排行榜周期切换的代际防乱序——
 * 在途旧周期响应（含失败）不覆盖新周期；下拉刷新防重语义保持原状。
 * 推荐流/COS 流无代际语义（各自 isLoading 防重 + 纯函数分批），不在此锁定。
 * 时序构造与 AlbumViewModelTest 同款：rankings 请求挂闸门，由测试决定放行顺序。
 *
 * 任务I I1 追加：①下拉刷新清空三 tab 缓存（当前 tab 立即重拉、另两 tab 标脏切入懒重拉，
 * GUIDE_UI §下拉刷新 L86）；②点赞变更指纹返回重排（LikeMutationTracker，GUIDE_UI L89，
 * detail 侧上报点归 I7 批）。
 *
 * 任务R R1 追加：COS 流筛选代际防乱序对位锁定（任务Y Y4b 引入 cosGeneration 后，上面
 * 「COS 流无代际语义」的表述仅适用于 Y4b 之前）——applyPanelDraft/resetPanelDraft 是唯一
 * 代际递增点，翻页/下拉刷新不递增但响应同样受代际校验：在途旧代响应（成功/失败/翻页追加）
 * 一律丢弃不落地；且各次请求携带当次应用筛选的快照（断言 AssetQuery.sort 区分草稿A/B，
 * 证明请求不是「最终态重放」）。时序构造沿 rankings 同款闸门：assetsGated=true 时
 * assets() 每次调用挂独立 gate，由测试决定归位顺序与成败。
 */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class RankingsCall(val period: RankingPeriod, val gate: CompletableDeferred<List<MediaAsset>>)

        val rankingsCalls = mutableListOf<RankingsCall>()

        /**
         * COS 流闸门调用记录（任务R R1）：gate 由测试逐个 complete/completeExceptionally，
         * 归位顺序与成败完全可控（rankings 闸门同款范式）。
         */
        data class AssetsCall(val query: AssetQuery, val gate: CompletableDeferred<AssetPageResult>)

        /** 推荐流调用记录（seed 序列，I1 刷新/指纹重拉断言用） */
        val recommendationsCalls = mutableListOf<Long>()

        /** COS 流调用记录（I1 刷新懒重拉断言用） */
        val assetsCalls = mutableListOf<AssetQuery>()

        /**
         * COS 流闸门开关（任务R R1 代际防乱序用例）：false 时 assets() 直返 assetsResult
         * （既有用例行为不变）；true 时每次调用记录独立 gate 并挂起等待——多次在途请求
         * 必须各自可控归位，单一共享 gate 一次 complete 会同时放行所有等待者，区分不了代次。
         */
        var assetsGated = false
        val assetsGateCalls = mutableListOf<AssetsCall>()

        /** 推荐流/COS 流返回值可配（I1 需要非空数据验证缓存清空；默认空=既有用例行为不变） */
        var recommendationsResult: List<MediaAsset> = emptyList()
        var assetsResult: AssetPageResult = AssetPageResult(items = emptyList(), nextCursor = null, totalMatched = 0)

        /**
         * 推荐流前缀失败注入（首屏静默重试用例，2026-09-17）：前 n 次调用抛 RuntimeException
         * （模拟冷启动内嵌服务端就绪前的确定性首枪失败），之后正常返回 [recommendationsResult]。
         * 默认 0=从不失败，既有用例行为不变。
         */
        var recommendationsFailFirstN = 0

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            assetsCalls += query
            if (!assetsGated) return assetsResult
            val call = AssetsCall(query, CompletableDeferred())
            assetsGateCalls += call
            return call.gate.await()
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult =
            FacetsResult(partitions = emptyList(), authors = emptyList(), characters = emptyList(), types = emptyList())

    override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> {
        recommendationsCalls += seed
        if (recommendationsFailFirstN > 0) {
            recommendationsFailFirstN--
            throw RuntimeException("injected recommendations failure")
        }
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

    // ---------- 服务端就绪探针替身（2026-09-18 探针接线适配） ----------
    // ServerReadinessProbe 是 final 类不可 override：替身=真实探针 + 可编程依赖组装。
    // ready=true：已配置地址 + probe() 立即成功 → awaitReady 首枪即通；
    // ready=false：未配置地址（currentServerUrl=null）→ pollUntilReady 首行短路立即 false。
    // 探针内部 async 挂与 Main 同调度器的 UnconfinedTestDispatcher：替身 probe 无挂起点，
    // awaitReady 同步完成、不悬 VM 协程，虚拟时间保持全确定。

    private fun stubReadinessProbe(ready: Boolean): ServerReadinessProbe {
        val api = object : AuthApi {
            override suspend fun probe() = Unit // 首枪即通：不抛 = healthz 200
            override suspend fun login(password: String): String = throw IOException("替身不触达")
            override suspend fun devLogin(): String = throw IOException("替身不触达")
            override suspend fun logout() = throw IOException("替身不触达")
        }
        return ServerReadinessProbe(
            serverConfig = object : ServerConfigDataSource {
                override val serverUrl: Flow<String> = MutableStateFlow(if (ready) TEST_FAKE_SERVER_URL else "")
                override val token: Flow<String?> = MutableStateFlow<String?>(null)
                override fun currentToken(): String? = null
                override fun currentServerUrl(): String? = if (ready) TEST_FAKE_SERVER_URL else null
                override suspend fun updateServerUrl(url: String) = Unit
                override suspend fun updateToken(token: String) = Unit
                override suspend fun clearToken() = Unit
            },
            authApiFactory = object : AuthApiFactory {
                override fun create(baseUrl: String): AuthApi = api
            },
            scope = CoroutineScope(UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler)),
        )
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
        probe: ServerReadinessProbe? = null,
    ): HomeViewModel = HomeViewModel(
        mediaRepository = repo,
        gridPrefs = FakeGridPrefsRepository(),
        batchIndex = MediaBatchIndex(),
        likeMutationTracker = likeTracker,
        // 默认注入「立即就绪」替身：既有用例时序与探针接线前逐位一致（2026-09-18）
        readinessProbe = probe ?: stubReadinessProbe(ready = true),
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

    // ---------- 任务J J3a：tab 切换后距底哨兵抑制窗口（台账 #35） ----------

    @Test
    fun `切tab后短窗内距底哨兵被抑制 - 不追加换seed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply {
                // ≤BATCH_SIZE 的小库：init 后 revealed=pulled.size，再触 onNearBottom 即走换 seed 分支
                recommendationsResult = listOf(asset("r1"), asset("r2"))
            }
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertEquals(listOf(1L), repo.recommendationsCalls) // init 一次拉满

            // 可控时钟：RANK→推荐 切换后窗口内（周期行移除→视口变高→哨兵噪声时点）
            var fakeNow = 1_000L
            viewModel.clockMs = { fakeNow }
            viewModel.switchTab(HomeTab.RANK) // 榜单懒加载挂 gate 在途，不影响推荐缓存
            viewModel.switchTab(HomeTab.RECOMMEND) // 时间戳刷新为 fakeNow=1000
            fakeNow += HomeViewModel.SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS - 1 // 窗口内最后一刻
            viewModel.onNearBottom()
            advanceUntilIdle()
            // 抑制生效：无第二次推荐请求（不追加换 seed——「切 tab 不重拉」）
            assertEquals(listOf(1L), repo.recommendationsCalls)
        }

    @Test
    fun `哨兵抑制窗口过后触底恢复正常追加`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply {
                recommendationsResult = listOf(asset("r1"), asset("r2"))
            }
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertEquals(listOf(1L), repo.recommendationsCalls)

            var fakeNow = 1_000L
            viewModel.clockMs = { fakeNow }
            viewModel.switchTab(HomeTab.RANK)
            viewModel.switchTab(HomeTab.RECOMMEND)
            fakeNow += HomeViewModel.SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS // 恰出窗口
            viewModel.onNearBottom()
            advanceUntilIdle()
            // 窗口外哨兵是真实触底：批次尽换 seed 追加（既有行为不回归）
            assertEquals(listOf(1L, 2L), repo.recommendationsCalls)
        }

    @Test
    fun `冷启动首布局不在抑制窗口 - init揭示后哨兵照常工作`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 初值 Long.MIN_VALUE（非 switchTab 写入）：冷启动布局回流的哨兵触发不被吞
            val repo = FakeMediaRepository().apply {
                recommendationsResult = listOf(asset("r1"), asset("r2"))
            }
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertEquals(listOf(1L), repo.recommendationsCalls)
            viewModel.onNearBottom() // 未经任何 switchTab（真实时钟差恒正且巨大）
            advanceUntilIdle()
            assertEquals(listOf(1L, 2L), repo.recommendationsCalls)
        }

    // ---------- 任务R R1：COS 流筛选代际防乱序（cosGeneration，Y4b 起） ----------

    @Test
    fun `筛选代际防乱序 - 筛选A在途时应用筛选B，A迟到响应被丢弃，B整页落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            repo.assetsGated = true // COS 流挂闸门：两次在途请求的归位顺序由测试决定
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.cos.loaded) // 懒加载：COS 未拉过

            // 应用筛选A（按观看数排序）：代际 0→1，请求1在途挂闸门
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(1, repo.assetsGateCalls.size)
            assertTrue(viewModel.uiState.value.cos.isLoading)

            // 筛选B（按文件名排序）紧接应用：代际 1→2；筛选重载 isInitial=true 不受在途
            // isLoading 拦截，新请求照发——在途旧请求交给代际校验兜底，而非防重拦截
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.NAME))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(2, repo.assetsGateCalls.size)
            assertTrue(viewModel.uiState.value.cos.isLoading)
            // 两次请求各自携带当次应用筛选的快照，不是「最终态重放」
            assertEquals(AssetSort.VIEW_COUNT, repo.assetsGateCalls[0].query.sort)
            assertEquals(AssetSort.NAME, repo.assetsGateCalls[1].query.sort)

            // A 的成功响应迟到归位：旧代整代丢弃——items 不被 A 污染、loading 不被 A 收走、
            // cursor 不被 A 改写
            repo.assetsGateCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("stale-a")), nextCursor = "stale-cursor", totalMatched = 1),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.cos.items.isEmpty())
            assertNull(viewModel.uiState.value.cos.nextCursor)
            assertTrue(viewModel.uiState.value.cos.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)

            // B 响应归位：整页替换落地，已应用态=B草稿、面板已关
            repo.assetsGateCalls[1].gate.complete(
                AssetPageResult(items = listOf(asset("fresh-b")), nextCursor = null, totalMatched = 1),
            )
            advanceUntilIdle()
            assertEquals(listOf("fresh-b"), viewModel.uiState.value.cos.items.map { it.id })
            assertNull(viewModel.uiState.value.cos.nextCursor)
            assertFalse(viewModel.uiState.value.cos.isLoading)
            assertTrue(viewModel.uiState.value.cos.loaded)
            assertEquals(AssetSort.NAME, viewModel.uiState.value.cosFilter.sort)
            assertFalse(viewModel.uiState.value.filterPanel.visible)
        }

    @Test
    fun `旧代筛选失败不污染新筛选态 - 失败迟到不弹错不收loading，新代成功照常落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            repo.assetsGated = true
            val viewModel = viewModel(repo)
            advanceUntilIdle()

            // 筛选A在途 → 应用筛选B（代际+1，isInitial=true 绕过防重拦截）
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.NAME))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(2, repo.assetsGateCalls.size)

            // 旧代A失败迟到：不弹错误、不收 loading（错误只属于当前代）
            repo.assetsGateCalls[0].gate.completeExceptionally(RuntimeException("stale failure"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.cos.isLoading)

            // 新代B成功落地：正常展示无错误
            repo.assetsGateCalls[1].gate.complete(
                AssetPageResult(items = listOf(asset("b")), nextCursor = null, totalMatched = 1),
            )
            advanceUntilIdle()
            assertEquals(listOf("b"), viewModel.uiState.value.cos.items.map { it.id })
            assertFalse(viewModel.uiState.value.cos.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `翻页在途时应用新筛选 - 翻页旧代响应被丢弃不追加，新代整页替换落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            repo.assetsGated = true
            val viewModel = viewModel(repo)
            advanceUntilIdle()

            // 可控时钟：绕开切 tab 后的距底哨兵抑制窗口（J3a 500ms），让触底翻页可控触发
            var fakeNow = 1_000L
            viewModel.clockMs = { fakeNow }

            // COS 首页落地（gen 快照=0；翻页/刷新不递增代际）
            viewModel.switchTab(HomeTab.COS)
            advanceUntilIdle()
            assertEquals(1, repo.assetsGateCalls.size)
            repo.assetsGateCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("c1")), nextCursor = "cur1", totalMatched = 1),
            )
            advanceUntilIdle()
            assertEquals(listOf("c1"), viewModel.uiState.value.cos.items.map { it.id })
            assertFalse(viewModel.uiState.value.cos.exhausted)

            // 触底翻页：isInitial=false 请求在途（携带 cur1 游标，代际仍是 0）
            fakeNow += HomeViewModel.SENTINEL_SUPPRESS_AFTER_TAB_SWITCH_MS
            viewModel.onNearBottom()
            advanceUntilIdle()
            assertEquals(2, repo.assetsGateCalls.size)
            assertEquals("cur1", repo.assetsGateCalls[1].query.cursor)
            assertTrue(viewModel.uiState.value.cos.isLoading)

            // 翻页在途时应用新筛选：代际 0→1，isInitial=true 绕过 isLoading 防重发出新请求
            //（cursor=null 整页首拉，携带新筛选快照）
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.PLAY_COUNT))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(3, repo.assetsGateCalls.size)
            assertNull(repo.assetsGateCalls[2].query.cursor)
            assertEquals(AssetSort.PLAY_COUNT, repo.assetsGateCalls[2].query.sort)

            // 翻页旧代响应迟到：丢弃——不追加 c2、nextCursor 不被改写成 cur2
            repo.assetsGateCalls[1].gate.complete(
                AssetPageResult(items = listOf(asset("c2")), nextCursor = "cur2", totalMatched = 2),
            )
            advanceUntilIdle()
            assertEquals(listOf("c1"), viewModel.uiState.value.cos.items.map { it.id })
            assertEquals("cur1", viewModel.uiState.value.cos.nextCursor)
            assertTrue(viewModel.uiState.value.cos.isLoading)

            // 新代整页替换落地（exhausted 随 nextCursor=null 置位）
            repo.assetsGateCalls[2].gate.complete(
                AssetPageResult(items = listOf(asset("n1"), asset("n2")), nextCursor = null, totalMatched = 2),
            )
            advanceUntilIdle()
            assertEquals(listOf("n1", "n2"), viewModel.uiState.value.cos.items.map { it.id })
            assertNull(viewModel.uiState.value.cos.nextCursor)
            assertTrue(viewModel.uiState.value.cos.exhausted)
            assertFalse(viewModel.uiState.value.cos.isLoading)
        }

    @Test
    fun `重置面板走同一应用链 - 草稿回默认代际推进，在途旧筛选响应被丢弃`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            repo.assetsGated = true
            val viewModel = viewModel(repo)
            advanceUntilIdle()

            // 先应用筛选A（在途挂闸门）
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT))
            viewModel.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(AssetSort.VIEW_COUNT, viewModel.uiState.value.cosFilter.sort)

            // 面板里再改成B后点「重置」：草稿回默认 + 走 applyPanelDraft 同链
            //（代际+1 + 已应用态写默认 + 重拉 + 关面板三合一）
            viewModel.openFilterSheet()
            viewModel.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.NAME))
            viewModel.resetPanelDraft()
            advanceUntilIdle()
            assertEquals(2, repo.assetsGateCalls.size)
            // 2026-09-17 晚拍板+ebc87c0：草稿/已应用态休息档=DEFAULT（面板选中判定），
            // 但出参经 withPanelDraft 单源翻译为 FILE_DATE（协议 default=入库时间）
            assertEquals(AssetSort.FILE_DATE, repo.assetsGateCalls[1].query.sort)
            assertEquals(AlbumPanelDraft(), viewModel.uiState.value.cosFilter) // 已应用态=默认草稿
            assertEquals(AlbumPanelDraft(), viewModel.uiState.value.filterPanel.draft)
            assertFalse(viewModel.uiState.value.filterPanel.visible)

            // 旧代A响应迟到：被代际校验丢弃
            repo.assetsGateCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("stale-a")), nextCursor = null, totalMatched = 1),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.cos.items.isEmpty())
            assertTrue(viewModel.uiState.value.cos.isLoading)

            // 新代（默认筛选）落地
            repo.assetsGateCalls[1].gate.complete(
                AssetPageResult(items = listOf(asset("default")), nextCursor = null, totalMatched = 1),
            )
            advanceUntilIdle()
            assertEquals(listOf("default"), viewModel.uiState.value.cos.items.map { it.id })
            assertFalse(viewModel.uiState.value.cos.isLoading)
        }

    // ---------- 首屏失败静默自动重试（2026-09-17 用户反馈「每次进入首页都闪加载失败」） ----------
    // 行为锁定：首屏路径失败且有重试预算=静默（不亮横幅、保持加载/空白态）；预算耗尽=亮真
    // 错误；用户主动刷新失败=立即亮牌、成功后清牌。修的是 77435fb「失败即亮横幅+自愈不清牌」。

    @Test
    fun `首屏首枪失败静默重试 - 不亮错误横幅，重试成功内容落地横幅保持空`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply {
                recommendationsFailFirstN = 1 // 首枪失败（冷启动服务端未就绪的确定性失败），退避后重试成功
                recommendationsResult = listOf(asset("r1"))
            }
            val viewModel = viewModel(repo)

            // 只推进到首枪失败落地（重试还在退避中——首档 300ms，不推进虚拟时间）
            runCurrent()
            assertFalse(viewModel.uiState.value.recommend.loaded)
            assertFalse(viewModel.uiState.value.recommend.isLoading) // 退避间隙，非在途
            assertNull(viewModel.uiState.value.errorMessage) // 静默：不闪「加载失败请下拉重试」

            // 退避到期自动重试：成功自愈，横幅全程未出现
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recommend.loaded)
            assertEquals(listOf("r1"), viewModel.uiState.value.recommend.pulled.map { it.id })
            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals(2, repo.recommendationsCalls.size) // 初次 + 重试一次，无多余请求
        }

    @Test
    fun `首屏重试预算耗尽 - 连续失败达上限后亮真错误横幅且不再多发请求`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply { recommendationsFailFirstN = 99 } // 恒失败=真故障
            val viewModel = viewModel(repo)
            advanceUntilIdle()

            // 上限封顶：初次 + INITIAL_RETRY_MAX 次退避重试，防无限循环
            assertEquals(1 + HomeViewModel.INITIAL_RETRY_MAX, repo.recommendationsCalls.size)
            // 真失败必须有反馈：预算耗尽才亮横幅（不再被自动重试掩盖）
            assertEquals(LIST_LOAD_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `刷新失败即时亮横幅再刷新成功清横幅 - 主动动作反馈与陈旧牌清理`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply { recommendationsResult = listOf(asset("r1")) }
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.recommend.loaded)

            // 用户主动下拉刷新失败（已 loaded，不走首屏静默）：立即亮牌
            repo.recommendationsFailFirstN = 1
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(LIST_LOAD_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.recommend.loaded) // 刷新失败不清已加载数据
            assertEquals(listOf("r1"), viewModel.uiState.value.recommend.pulled.map { it.id })

            // 再刷新成功：横幅清除（失败反馈不残留成假错误）
            viewModel.refresh()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.recommend.loaded)
        }

    // ---------- 探针接线（2026-09-18 单机形态冷启动）：init 先 awaitReady 再首拉 ----------

    @Test
    fun `探针就绪后首屏照常发起并落地 - 探针只等服务端不吞首拉`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository().apply { recommendationsResult = listOf(asset("r1")) }
            val viewModel = viewModel(repo, probe = stubReadinessProbe(ready = true))
            advanceUntilIdle()
            // 探针只负责「等服务端起来」：就绪（或超时）后首拉必须照常发出并落地，
            // 失败反馈链路（静默重试→预算耗尽亮横幅）不被探针截断
            assertEquals(listOf(1L), repo.recommendationsCalls)
            assertTrue(viewModel.uiState.value.recommend.loaded)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `探针未就绪也照常发起首屏 - awaitReady false 不阻塞首拉照常落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // ready=false 替身：未配置地址 → awaitReady 走 pollUntilReady 首行短路返回 false
            //（探针哨兵修复后此分支才可经真实探针触发），验证调用方契约「false 照常发起」
            val repo = FakeMediaRepository().apply { recommendationsResult = listOf(asset("r1")) }
            val viewModel = viewModel(repo, probe = stubReadinessProbe(ready = false))
            advanceUntilIdle()
            // 探针超时/未配置不吞首拉：业务请求照发，落地路径与就绪场景完全一致
            assertEquals(listOf(1L), repo.recommendationsCalls)
            assertTrue(viewModel.uiState.value.recommend.loaded)
            assertNull(viewModel.uiState.value.errorMessage)
        }
}
