package media.qimeng.app.feature.history

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
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.HistoryRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.HistoryEntry
import media.qimeng.app.core.model.HistoryPageResult
import media.qimeng.app.core.model.HistoryQuery
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.app.feature.history.R

/**
 * 历史页 ViewModel 单测（2026-09-06 审查清偿）：与相册页同族的筛选代际防乱序——
 * 在途旧代响应（含失败）不覆盖新筛选态；分页防重语义保持原状。
 * 时序构造与 AlbumViewModelTest 同款：每个仓库请求挂闸门，由测试决定放行顺序。
 */
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（只满足本页编排断言；网络行为在 :core:data 全链路测试） ----------

    private class FakeHistoryRepository : HistoryRepository {
        data class Call(val query: HistoryQuery, val gate: CompletableDeferred<HistoryPageResult>)

        val calls = mutableListOf<Call>()

        override suspend fun history(query: HistoryQuery): HistoryPageResult {
            val call = Call(query, CompletableDeferred())
            calls += call
            return call.gate.await()
        }
    }

    private class FakeMediaRepository : MediaRepository {
        data class FacetsCall(val query: FacetsQuery, val gate: CompletableDeferred<FacetsResult>)

        val facetsCalls = mutableListOf<FacetsCall>()

        override suspend fun assets(query: AssetQuery): AssetPageResult =
            AssetPageResult(items = emptyList(), nextCursor = null, totalMatched = 0)

        override suspend fun facets(query: FacetsQuery): FacetsResult {
            val call = FacetsCall(query, CompletableDeferred())
            facetsCalls += call
            return call.gate.await()
        }

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null

        // M4-2A-B3 万能筛选面板标签族（历史页 /history 无筛选参数，替身只补空实现满足接口）
        override suspend fun tags(): List<TagSummary> = emptyList()

        override suspend fun createTag(name: String): TagSummary = TagSummary(id = name, name = name)

        override suspend fun deleteTag(tagId: String) = Unit

        /** 整批放行某一代的四维候选请求（loadFacets 每批固定并行 4 个——分区/作品/角色/类型，实现细节只在此替身内约定） */
        fun completeFacetsBatch(batch: Int, result: FacetsResult) {
            val base = batch * FACETS_PER_BATCH
            repeat(FACETS_PER_BATCH) { facetsCalls[base + it].gate.complete(result) }
        }

        companion object {
            const val FACETS_PER_BATCH = 4
        }
    }

    /** 列数档替身：StateFlow 直存的内存档（模拟 grid_columns_all 键的读写，与本页 VM 无关） */
    private class FakeGridPrefsRepository : GridPrefsRepository {
        val homeFlow = MutableStateFlow(DataStoreGridPrefsRepository.DEFAULT_HOME_COLUMNS)
        val albumFlow = MutableStateFlow(DataStoreGridPrefsRepository.DEFAULT_ALBUM_COLUMNS)

        override val homeColumns: Flow<Int> = homeFlow
        override val albumColumns: Flow<Int> = albumFlow

        override suspend fun setHomeColumns(columns: Int) {
            homeFlow.value = columns
        }

        override suspend fun setAlbumColumns(columns: Int) {
            albumFlow.value = columns
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

    private fun entry(id: String) = HistoryEntry(asset = asset(id))

    private fun historyPage(entries: List<HistoryEntry>, nextCursor: String? = null) =
        HistoryPageResult(items = entries, nextCursor = nextCursor)

    private fun facets(total: Int) = FacetsResult(
        partitions = listOf(FacetOption(key = "all", name = "全部", fileCount = total, kind = FacetParamKind.SOURCE)),
        authors = emptyList(),
        characters = emptyList(),
        types = emptyList(),
    )

    private fun viewModel(
        historyRepo: FakeHistoryRepository,
        mediaRepo: FakeMediaRepository,
        gridPrefs: FakeGridPrefsRepository = FakeGridPrefsRepository(),
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
    ): HistoryViewModel = HistoryViewModel(
        historyRepository = historyRepo,
        mediaRepository = mediaRepo,
        gridPrefs = gridPrefs,
        batchIndex = batchIndex,
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    // ---------- 用例 ----------

    @Test
    fun `筛选请求代际防乱序 - 在途A未归时切分区COS，A迟到响应被丢弃，COS结果落地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val historyRepo = FakeHistoryRepository()
            val mediaRepo = FakeMediaRepository()
            val viewModel = viewModel(historyRepo, mediaRepo)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoading) // init 首屏请求在途
            assertNull(historyRepo.calls[0].query.includeCos) // 初始「全部」：includeCos/cosOnly 均不传
            assertNull(historyRepo.calls[0].query.cosOnly)

            // 切 COS：旧代 A 仍在途（修复前此处会被 isLoading 拦截，第二次请求根本不发出）
            viewModel.selectPartition(Zone.COS)
            advanceUntilIdle()
            assertEquals(2, historyRepo.calls.size)
            assertEquals(true, historyRepo.calls[1].query.cosOnly)

            // 旧代 A 迟到归位：不得落地
            historyRepo.calls[0].gate.complete(historyPage(listOf(entry("old-a"))))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading) // 新代仍在途，loading 不被旧代收走

            // 新代归位：落地
            historyRepo.calls[1].gate.complete(historyPage(listOf(entry("new-b"))))
            advanceUntilIdle()
            assertEquals(listOf("new-b"), viewModel.uiState.value.items.map { it.id })
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `旧代请求失败不污染新筛选态`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()

        viewModel.selectMediaType(MediaKind.VIDEO)
        advanceUntilIdle()
        assertEquals(MediaKind.VIDEO, historyRepo.calls[1].query.mediaType)

        // 旧代失败迟到：不弹错误、不收 loading（错误只属于当前代）
        historyRepo.calls[0].gate.completeExceptionally(RuntimeException("stale failure"))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.isLoading)

        // 新代成功落地：正常展示
        historyRepo.calls[1].gate.complete(historyPage(listOf(entry("b"))))
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `四维候选旧代响应不覆盖新筛选候选`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()

        viewModel.selectPartition(Zone.REGULAR)
        advanceUntilIdle()

        // 旧代候选整批迟到：计数不落地
        mediaRepo.completeFacetsBatch(batch = 0, result = facets(total = 9))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.totalForAllPill)

        // 新代候选整批落地
        mediaRepo.completeFacetsBatch(batch = 1, result = facets(total = 3))
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.totalForAllPill)
    }

    // ---------- N4 消费批：历史页「作品」维行（出处分组多选，/history source 数组） ----------

    @Test
    fun `作品行多选映射 - 出处分组投影为 source 数组 同维OR`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()

        // 双选两个出处（旧版「支持多选作品筛选」语义）→ /history source 数组
        viewModel.selectAuthor(FacetOption("出处A", "出处A", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()
        viewModel.selectAuthor(FacetOption("出处B", "出处B", 7, FacetParamKind.SOURCE))
        advanceUntilIdle()
        assertEquals(3, historyRepo.calls.size)
        assertEquals(setOf("出处A", "出处B"), historyRepo.calls.last().query.source?.toSet())

        // 再点已选=取消单个；「全部」胶囊（null）= 清本行
        viewModel.selectAuthor(FacetOption("出处A", "出处A", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()
        assertEquals(listOf("出处B"), historyRepo.calls.last().query.source)
        viewModel.selectAuthor(null)
        advanceUntilIdle()
        assertNull(historyRepo.calls.last().query.source)
    }

    @Test
    fun `作品行候选 - facets作者行按SOURCE桶收口 kind=author不入候选 其他沉底`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()

        // 作品行候选请求（排自身）：作者维 source/authorId 不传，history=1 子集约束在位
        val authorCall = mediaRepo.facetsCalls[1] // 每批第 2 路 = authorFacetsQuery
        assertNull(authorCall.query.source)
        assertNull(authorCall.query.authorId)
        assertEquals(true, authorCall.query.history)

        // 候选收口：kind=author 的 COS 作者桶不进「作品」行（/history 无 authorId 数组位）；
        // source 桶照出，「其他」恒沉底
        mediaRepo.completeFacetsBatch(
            batch = 0,
            result = FacetsResult(
                partitions = listOf(FacetOption("all", "全部", 23, FacetParamKind.SOURCE)),
                authors = listOf(
                    FacetOption("出处B", "出处B", 7, FacetParamKind.SOURCE),
                    FacetOption("cos_测试作者一", "测试作者一", 5, FacetParamKind.AUTHOR),
                    FacetOption("出处A", "出处A", 12, FacetParamKind.SOURCE),
                    FacetOption("其他", "其他", 4, FacetParamKind.SOURCE),
                ),
                characters = emptyList(),
                types = emptyList(),
            ),
        )
        advanceUntilIdle()
        // VM 只兜底「其他」沉底（排序=服务端 fileCount 降序原样透出）；kind=author 桶已收口
        assertEquals(
            listOf("出处B", "出处A", "其他"),
            viewModel.uiState.value.authorOptions.map { it.name },
        )
    }

    @Test
    fun `作品行激活与分区联动 - 切分区清作品行`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()
        viewModel.selectAuthor(FacetOption("出处A", "出处A", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()

        viewModel.selectPartition(Zone.COS)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.filter.authors.isEmpty())
        assertNull(historyRepo.calls.last().query.source)
    }

    @Test
    fun `分页在途防重保持 - 同代在途不重复请求，追加不丢页`() = runTest(mainDispatcherRule.testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val mediaRepo = FakeMediaRepository()
        val viewModel = viewModel(historyRepo, mediaRepo)
        advanceUntilIdle()

        // 首屏落地（带 cursor，可翻页）
        historyRepo.calls[0].gate.complete(historyPage(listOf(entry("a")), nextCursor = "c1"))
        mediaRepo.completeFacetsBatch(batch = 0, result = facets(total = 1))
        advanceUntilIdle()
        assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })

        // 翻页在途时再次触发：被防重拦截，不重复请求
        viewModel.onNearBottom()
        viewModel.onNearBottom()
        advanceUntilIdle()
        assertEquals(2, historyRepo.calls.size)
        assertEquals("c1", historyRepo.calls[1].query.cursor)

        // 翻页归位：追加不丢页
        historyRepo.calls[1].gate.complete(historyPage(listOf(entry("b")), nextCursor = null))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.nextCursor)
    }

    @Test
    fun `维度芯片点击 进页默认收起 已激活维切换展开收起 其他维切维并展开`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(FakeHistoryRepository(), FakeMediaRepository())
        advanceUntilIdle()

        // 进页默认收起（用户 2026-09-07 覆盖 B8）：点已激活维 → 展开；再点 → 收起
        // （镜像 AlbumViewModelTest 同名用例；历史页维度子集=分区/角色·作品/类型，无作者行）
        assertFalse(viewModel.uiState.value.filter.expanded)
        viewModel.onDimChipClicked(AlbumDim.PARTITION)
        assertTrue(viewModel.uiState.value.filter.expanded)
        assertEquals(AlbumDim.PARTITION, viewModel.uiState.value.activeDim)
        viewModel.onDimChipClicked(AlbumDim.PARTITION)
        assertFalse(viewModel.uiState.value.filter.expanded)

        // 点其他维（角色·作品）→ 切维并展开（切维强制展开保持，旧仓库实录四 Fragment 齐证）
        viewModel.onDimChipClicked(AlbumDim.CHARACTER)
        assertEquals(AlbumDim.CHARACTER, viewModel.uiState.value.activeDim)
        assertTrue(viewModel.uiState.value.filter.expanded)
    }

    @Test
    fun `空态文案分支 - 默认选没有浏览记录资源 COS分区选COS资源`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(FakeHistoryRepository(), FakeMediaRepository())
        advanceUntilIdle()

        // 默认分区 → 「没有浏览记录」分支；文案本体由 strings.xml history_empty_default 逐字锁定，
        // 本断言锁定分支选择（VM 只发资源 id 结构化语义，镜像 FavoriteViewModelTest 同名用例）
        assertEquals(R.string.history_empty_default, viewModel.uiState.value.emptyTextRes)

        // COS 分区 → 「没有COS浏览记录」分支（规格书 §浏览历史）
        viewModel.selectPartition(Zone.COS)
        advanceUntilIdle()
        assertEquals(R.string.history_empty_cos, viewModel.uiState.value.emptyTextRes)
    }

    // ---------- 任务I I5：列数共用全部页档 + 双指缩放 + resume 重拉 ----------

    @Test
    fun `网格列数初始值读共用全部页档`() = runTest(mainDispatcherRule.testDispatcher) {
        // 档位预置 4（全部页此前调过）：进页 gridColumns 应读档为 4，而非缺省值
        val gridPrefs = FakeGridPrefsRepository()
        gridPrefs.albumFlow.value = 4
        val viewModel = viewModel(FakeHistoryRepository(), FakeMediaRepository(), gridPrefs)
        advanceUntilIdle()
        assertEquals(4, viewModel.gridColumns.value)
    }

    @Test
    fun `双指缩放步进clamp2到5 手势结束持久化到共用全部页档`() = runTest(mainDispatcherRule.testDispatcher) {
        val gridPrefs = FakeGridPrefsRepository()
        val viewModel = viewModel(FakeHistoryRepository(), FakeMediaRepository(), gridPrefs)
        advanceUntilIdle()

        // 放大减列到下界：clamp 在 2（MIN_ALBUM_COLUMNS）
        viewModel.adjustColumnsLive(-1)
        viewModel.adjustColumnsLive(-1)
        assertEquals(2, viewModel.pinchColumns.value)

        // 缩小加列到上界：clamp 在 5（MAX_ALBUM_COLUMNS）
        repeat(4) { viewModel.adjustColumnsLive(+1) }
        assertEquals(5, viewModel.pinchColumns.value)

        // 手势结束：内存值落盘到共用档（grid_columns_all），瞬时值归位
        viewModel.commitPinchColumns()
        advanceUntilIdle()
        assertNull(viewModel.pinchColumns.value)
        assertEquals(5, gridPrefs.albumFlow.value)
        assertEquals(5, viewModel.gridColumns.value)

        // 无进行中手势时 commit 幂等 no-op（不重复写档）
        viewModel.commitPinchColumns()
        advanceUntilIdle()
        assertEquals(5, gridPrefs.albumFlow.value)
    }

    // 任务V V1（2026-09-10）：「详情返回 ON_RESUME 自动重拉」两用例随机制整体移除而删除
    // （用户拍板「返回时不要刷新界面…应该是原来的不变」，lastViewedAt 重排滞后靠下拉
    // 刷新/下次冷启动收敛——取舍见 HistoryViewModel 类 KDoc 与 CHANGELOG 第一百八十八笔）。

    // ---------- 批次上下文（2026-09-09 拍板：收藏/历史进详情补批次，首页同款机制） ----------

    @Test
    fun `enterDetail批次上下文 - 清单未落地时空批次 落地后含追加件且序号滑切可用`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val historyRepo = FakeHistoryRepository()
            val mediaRepo = FakeMediaRepository()
            val batchIndex = MediaBatchIndex()
            val viewModel = viewModel(historyRepo, mediaRepo, batchIndex = batchIndex)
            advanceUntilIdle()

            // 首载在途（清单未落地）点卡：批次为空表——详情页序号区不显示（空批次语义）
            viewModel.enterDetail("x")
            assertTrue(batchIndex.ids.isEmpty())
            assertEquals(-1, batchIndex.indexOf("x"))

            // 首载落地两件（带 cursor 可翻页）+ 翻页追加一件后点卡：批次 = 当前显示清单（含追加件，lastViewedAt 倒序）
            historyRepo.calls[0].gate.complete(historyPage(listOf(entry("a"), entry("b")), nextCursor = "c1"))
            advanceUntilIdle()
            viewModel.onNearBottom()
            advanceUntilIdle() // 请求注册在 viewModelScope.launch 内，先推进调度再取 calls[1]
            historyRepo.calls[1].gate.complete(historyPage(listOf(entry("c"))))
            advanceUntilIdle()
            viewModel.enterDetail("b")
            assertEquals(listOf("a", "b", "c"), batchIndex.ids)
            assertEquals(1, batchIndex.indexOf("b"))
            assertEquals(3, batchIndex.size())

            // 滑切数据链（详情页 moveBy 消费）：邻位可达、尾件越界返回 null
            assertEquals("c", batchIndex.assetIdAt(batchIndex.indexOf("b"), +1))
            assertNull(batchIndex.assetIdAt(batchIndex.indexOf("c"), +1))
        }
}
