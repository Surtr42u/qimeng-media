package media.qimeng.app.feature.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import media.qimeng.app.core.data.repository.MediaBatchIndex
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
import media.qimeng.app.core.model.SuggestionKind
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 搜索页 ViewModel 单测（M4-2A-B4 三态状态机）：入口→建议（300ms 防抖）→结果（提交）；
 * 返回链镜像旧版 handleBack（SearchFragment L253-270）：结果/建议态一律清词回入口态+重拉推荐词，
 * 仅入口态退页（UI 层 onBack，VM 无此职责）；「点搜索栏回建议态（词保留）」是点击路径单独锁定；
 * 查询=纯 q + 固定 includeCos=true（旧版搜索合并常规+COS，分区/类型筛选已删）；
 * 列数=页内存态不落盘（旧版 PinchZoomHelper 未传 onColumnsChanged + ColumnsRef(3) 每进页重置，
 * VM 构造签名已无持久化端口——持久化回归会直接编译失败）。
 * 时序构造与 AlbumViewModelTest 同款：assets 请求挂闸门，由测试决定放行顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runTest(TestDispatcher) 重载（P3-5 显式声明消除警告）
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeMediaRepository : MediaRepository {
        data class AssetsCall(val query: AssetQuery, val gate: CompletableDeferred<AssetPageResult>)
        data class SuggestCall(val q: String, val limit: Int, val recommend: Boolean)

        val assetsCalls = mutableListOf<AssetsCall>()
        val suggestCalls = mutableListOf<SuggestCall>()

        /** 建议固定返回值（sortedShortestFirst 断言用） */
        var suggestResult: List<NameSuggestion> = emptyList()

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            val call = AssetsCall(query, CompletableDeferred())
            assetsCalls += call
            return call.gate.await()
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult =
            FacetsResult(partitions = emptyList(), authors = emptyList(), characters = emptyList(), types = emptyList())

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> {
            suggestCalls += SuggestCall(q, limit, recommend)
            return suggestResult
        }

        override suspend fun assetOrigUrl(assetId: String): String? = null
    }

    private class FakeSearchHistoryRepository : SearchHistoryRepository {
        private val _history = MutableStateFlow<List<String>>(emptyList())
        val recorded = mutableListOf<String>()
        var cleared = 0

        override val history: Flow<List<String>> = _history

        override suspend fun record(query: String) {
            recorded += query
            _history.value = _history.value + query
        }

        override suspend fun clear() {
            cleared += 1
            _history.value = emptyList()
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
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
    ): SearchViewModel = SearchViewModel(
        mediaRepository = repo,
        searchHistoryRepository = historyRepo,
        batchIndex = batchIndex,
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    /** 推荐词调用次数（handleBack 重拉断言的基线） */
    private fun FakeMediaRepository.recommendCallCount() = suggestCalls.count { it.recommend }

    // ---------- 三态状态机 ----------

    @Test
    fun `三态状态机 - 入口初始化拉推荐词，输入防抖进建议态，提交进结果态锁定q加includeCos`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            repo.suggestResult = listOf(
                NameSuggestion(name = "测试作品MM", kind = SuggestionKind.COS_WORK),
                NameSuggestion(name = "测试作品M", kind = SuggestionKind.COS_WORK),
            )
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            // 入口态：初始化拉推荐词（q 空 + recommend=true + limit 10），无结果请求
            assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
            assertEquals(listOf(""), repo.suggestCalls.map { it.q })
            assertEquals(listOf(true), repo.suggestCalls.map { it.recommend })
            assertEquals(SearchViewModel.SUGGEST_LIMIT, repo.suggestCalls[0].limit)
            // 从短到长
            assertEquals(listOf("测试作品M", "测试作品MM"), viewModel.uiState.value.recommendWords.map { it.name })
            assertTrue(repo.assetsCalls.isEmpty())

            // 输入：立刻切建议态；防抖窗口内不出网
            viewModel.onQueryChange("M")
            assertEquals(SearchPhase.SUGGEST, viewModel.uiState.value.phase)
            assertEquals(1, repo.suggestCalls.size)

            // 防抖到点：拉建议（q=M + recommend=false + limit 10），从短到长回填
            advanceUntilIdle()
            assertEquals(2, repo.suggestCalls.size)
            assertEquals("M", repo.suggestCalls[1].q)
            assertFalse(repo.suggestCalls[1].recommend)
            assertEquals(SearchViewModel.SUGGEST_LIMIT, repo.suggestCalls[1].limit)
            assertEquals(listOf("测试作品M", "测试作品MM"), viewModel.uiState.value.suggestions.map { it.name })

            // 提交（建议行/词丸/搜索按钮共用 submit）：结果态 + q+includeCos=true 固定 + 记历史
            viewModel.submit("M")
            advanceUntilIdle()
            assertEquals(SearchPhase.RESULT, viewModel.uiState.value.phase)
            assertEquals(1, repo.assetsCalls.size)
            val query = repo.assetsCalls[0].query
            assertEquals("M", query.q)
            assertEquals(true, query.includeCos) // 旧版搜索=合并常规+COS
            assertNull(query.cosOnly)
            assertNull(query.mediaType)
            assertNull(query.cursor)
            assertEquals(listOf("M"), historyRepo.recorded)
            assertTrue(viewModel.uiState.value.isLoading)
            assertEquals(emptyList<NameSuggestion>(), viewModel.uiState.value.suggestions)

            // 结果归位
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a")), total = 1))
            advanceUntilIdle()
            assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })
            assertFalse(viewModel.uiState.value.isLoading)
        }

    // ---------- 返回族（镜像旧版 handleBack：结果/建议态一律清词回入口；点搜索栏路径另测） ----------

    @Test
    fun `返回清词链 - 结果态返回清词回入口并重拉推荐词，不发新结果请求`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()
            val recommendCountAtStart = repo.recommendCallCount()

            viewModel.submit("M")
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
            advanceUntilIdle()
            assertEquals(SearchPhase.RESULT, viewModel.uiState.value.phase)

            // 返回（系统返回/左上箭头同链）：清词回入口态，推荐词重拉（旧版 setText("")+STATE_EMPTY）
            viewModel.handleBack()
            advanceUntilIdle()
            assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
            assertEquals("", viewModel.uiState.value.query)
            assertEquals("", viewModel.uiState.value.submittedQuery)
            assertTrue(viewModel.uiState.value.suggestions.isEmpty())
            assertEquals(recommendCountAtStart + 1, repo.recommendCallCount())
            assertEquals("", repo.suggestCalls.last().q)
            assertTrue(repo.suggestCalls.last().recommend)
            // 清词不触发结果重查
            assertEquals(1, repo.assetsCalls.size)
        }

    @Test
    fun `返回清词链 - 建议态返回同样清词回入口，取消在途防抖`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val historyRepo = FakeSearchHistoryRepository()
        val viewModel = viewModel(repo, historyRepo)
        advanceUntilIdle()

        viewModel.onQueryChange("M")
        assertEquals(SearchPhase.SUGGEST, viewModel.uiState.value.phase)

        // 防抖在途时返回：取消建议请求，清词回入口
        viewModel.handleBack()
        advanceUntilIdle()
        assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        // 建议请求只有两条推荐词（初始化 + handleBack 重拉）；在途防抖被取消，无 q=M 的建议请求
        assertEquals(listOf("", ""), repo.suggestCalls.map { it.q })
        assertEquals(listOf(true, true), repo.suggestCalls.map { it.recommend })
    }

    @Test
    fun `结果态点搜索栏回建议态 - 词保留并按词重拉建议（旧版 onFocusChange 点击路径，非返回键）`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            viewModel.submit("M")
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
            advanceUntilIdle()
            val suggestCountAtResult = repo.suggestCalls.size

            // 点击路径：词保留回建议态，建议按词重拉（返回键不走此链，见返回清词链用例）
            viewModel.backToSuggest()
            advanceUntilIdle()
            assertEquals(SearchPhase.SUGGEST, viewModel.uiState.value.phase)
            assertEquals("M", viewModel.uiState.value.query)
            assertTrue(repo.suggestCalls.size > suggestCountAtResult)
            assertEquals("M", repo.suggestCalls.last().q)
            assertFalse(repo.suggestCalls.last().recommend)
        }

    @Test
    fun `结果态直接改词即切建议态 - 不经点击与返回，建议按新词重拉（P3-3 直测）`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            viewModel.submit("M")
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
            advanceUntilIdle()
            assertEquals(SearchPhase.RESULT, viewModel.uiState.value.phase)

            // 结果态直接改词（旧版 TextWatcher 语义）：切建议态 + 建议按新词出网
            viewModel.onQueryChange("MM")
            assertEquals(SearchPhase.SUGGEST, viewModel.uiState.value.phase)
            assertEquals("MM", viewModel.uiState.value.query)
            advanceUntilIdle()
            assertEquals("MM", repo.suggestCalls.last().q)
            assertFalse(repo.suggestCalls.last().recommend)
            // 结果请求不因改词重发（新查询须显式 submit）
            assertEquals(1, repo.assetsCalls.size)
        }

    @Test
    fun `输入防抖 - 新输入取消旧词在途防抖，空输入即回入口态`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val historyRepo = FakeSearchHistoryRepository()
        val viewModel = viewModel(repo, historyRepo)
        advanceUntilIdle()

        // 连续输入：旧词防抖被取消，只有新词出网
        viewModel.onQueryChange("M")
        viewModel.onQueryChange("MM")
        advanceUntilIdle()
        assertEquals(listOf("", "MM"), repo.suggestCalls.map { it.q })

        // 清空输入：回入口态、建议清空
        viewModel.onQueryChange("")
        assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `空词提交不生效 - 不切态不出网不记历史`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val historyRepo = FakeSearchHistoryRepository()
        val viewModel = viewModel(repo, historyRepo)
        advanceUntilIdle()

        viewModel.submit("   ")
        advanceUntilIdle()
        assertEquals(SearchPhase.EMPTY, viewModel.uiState.value.phase)
        assertTrue(repo.assetsCalls.isEmpty())
        assertTrue(historyRepo.recorded.isEmpty())
    }

    @Test
    fun `历史流 - 提交记历史，清除历史走仓库`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val historyRepo = FakeSearchHistoryRepository()
        val viewModel = viewModel(repo, historyRepo)
        advanceUntilIdle()

        viewModel.submit("M")
        advanceUntilIdle()
        assertEquals(listOf("M"), historyRepo.recorded)
        assertEquals(listOf("M"), viewModel.uiState.value.history)

        viewModel.clearHistory()
        advanceUntilIdle()
        assertEquals(1, historyRepo.cleared)
        assertTrue(viewModel.uiState.value.history.isEmpty())
    }

    // ---------- 代际防乱序 / 翻页防重（纯 submit 链路） ----------

    @Test
    fun `查询代际防乱序 - 在途A未归时提交B，A迟到响应被丢弃`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            // 提交 A 在途；再提交 B（重载不被防重拦截，新代际）
            viewModel.submit("a")
            advanceUntilIdle()
            viewModel.submit("b")
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
            assertEquals("b", repo.assetsCalls[1].query.q)

            // 旧代 A 迟到归位：不得落地；loading 不被旧代收走
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("old-a")), total = 1))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading)

            // 新代归位：落地
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("new-b")), total = 7))
            advanceUntilIdle()
            assertEquals(listOf("new-b"), viewModel.uiState.value.items.map { it.id })
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `旧代查询失败不污染新查询态 - 连续提交链的迟到失败均被丢弃`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            // 三代连续提交，前两代在途时已切新词
            viewModel.submit("a")
            advanceUntilIdle()
            viewModel.submit("b")
            advanceUntilIdle()
            viewModel.submit("c")
            advanceUntilIdle()
            assertEquals(3, repo.assetsCalls.size)
            assertEquals("c", repo.assetsCalls[2].query.q)

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

    // ---------- 双指缩放列数（页内存态：旧版 ColumnsRef(3) 每进页重置 3 列，从不落盘） ----------

    @Test
    fun `缩放列数 - 每进页3列起步clamp 2到5，手势结束落为页内存值不落盘`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val viewModel = viewModel(repo, historyRepo)
            advanceUntilIdle()

            // 每次进搜索页（=新 VM）重置 3 列起步（旧版 ColumnsRef(3)）
            assertEquals(SearchViewModel.SEARCH_DEFAULT_COLUMNS, viewModel.gridColumns.value)
            assertEquals(3, viewModel.gridColumns.value)

            // 放大减列：3→2（下界 clamp 不再减）
            viewModel.adjustColumnsLive(-1)
            assertEquals(2, viewModel.pinchColumns.value)
            viewModel.adjustColumnsLive(-1)
            assertEquals(2, viewModel.pinchColumns.value)

            // 缩小加列：连续步进到上界 clamp
            viewModel.adjustColumnsLive(1)
            viewModel.adjustColumnsLive(1)
            viewModel.adjustColumnsLive(1)
            viewModel.adjustColumnsLive(1)
            assertEquals(5, viewModel.pinchColumns.value)

            // 手势结束：瞬时值落为本页内存态；无任何落盘（构造签名无持久化端口，回归即编译失败）
            viewModel.commitPinchColumns()
            assertNull(viewModel.pinchColumns.value)
            assertEquals(5, viewModel.gridColumns.value)

            // 无手势时结束：幂等 no-op
            viewModel.commitPinchColumns()
            assertEquals(5, viewModel.gridColumns.value)
        }

    // ---------- 批次上下文（RES R3：搜索页进详情补批次，首页/收藏同款机制） ----------

    @Test
    fun `enterDetail批次上下文 - 清单未落地时空批次 落地后含翻页追加件且序号滑切可用`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val batchIndex = MediaBatchIndex()
            val viewModel = viewModel(repo, historyRepo, batchIndex)
            advanceUntilIdle()

            // 提交后首载在途（清单未落地）点卡：批次为空表——详情页序号区不显示（空批次语义）
            viewModel.submit("q")
            advanceUntilIdle()
            viewModel.enterDetail("x")
            assertTrue(batchIndex.ids.isEmpty())
            assertEquals(-1, batchIndex.indexOf("x"))

            // 首载落地两件（带 cursor 可翻页）+ 翻页追加一件后点卡：批次 = 当前显示清单（含追加件，显示顺序）
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"), asset("b")), nextCursor = "c1", total = 2))
            advanceUntilIdle()
            viewModel.onNearBottom()
            advanceUntilIdle() // 请求注册在 viewModelScope.launch 内，先推进调度再取 assetsCalls[1]
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("c"))))
            advanceUntilIdle()
            viewModel.enterDetail("b")
            assertEquals(listOf("a", "b", "c"), batchIndex.ids)
            assertEquals(1, batchIndex.indexOf("b"))
            assertEquals(3, batchIndex.size())

            // 滑切数据链（详情页 moveBy 消费）：邻位可达、尾件越界返回 null
            assertEquals("c", batchIndex.assetIdAt(batchIndex.indexOf("b"), +1))
            assertNull(batchIndex.assetIdAt(batchIndex.indexOf("c"), +1))
        }

    @Test
    fun `enterDetail批次上下文 - 换词重查后批次整体替换为新结果清单`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val historyRepo = FakeSearchHistoryRepository()
            val batchIndex = MediaBatchIndex()
            val viewModel = viewModel(repo, historyRepo, batchIndex)
            advanceUntilIdle()

            // 第一词落地后写入批次
            viewModel.submit("a")
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a1"), asset("a2")), total = 2))
            advanceUntilIdle()
            viewModel.enterDetail("a1")
            assertEquals(listOf("a1", "a2"), batchIndex.ids)

            // 换词重查（快照式整体替换语义）：新清单落地后点卡，批次不再含旧词结果
            viewModel.submit("b")
            advanceUntilIdle()
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b1")), total = 1))
            advanceUntilIdle()
            viewModel.enterDetail("b1")
            assertEquals(listOf("b1"), batchIndex.ids)
            assertEquals(-1, batchIndex.indexOf("a1"))
        }
}
