package media.qimeng.app.feature.favorite

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
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchThrottle
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.FavoriteMutationTracker
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.STALE_AFTER_MS
import media.qimeng.app.core.model.AlbumDim
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
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.app.feature.favorite.R

/**
 * 收藏页 ViewModel 单测（2026-09-06 审查清偿）：与相册页同族的筛选代际防乱序——
 * 在途旧代响应（含失败）不覆盖新筛选态；分页防重语义保持原状。
 * 时序构造与 AlbumViewModelTest 同款：每个仓库请求挂闸门，由测试决定放行顺序。
 */
class FavoriteViewModelTest {

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

        // M4-2A-B3 万能筛选面板标签族（本页不用面板，替身只补空实现满足接口）
        override suspend fun tags(): List<TagSummary> = emptyList()

        override suspend fun createTag(name: String): TagSummary = TagSummary(id = name, name = name)

        override suspend fun deleteTag(tagId: String) = Unit

        /** 整批放行某一代的四维候选请求（loadFacets 每批固定并行 4 个——实现细节，只在此替身内约定） */
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

    private fun page(items: List<MediaAsset>, total: Int? = null) =
        AssetPageResult(items = items, nextCursor = null, totalMatched = total)

    private fun facets(total: Int) = FacetsResult(
        partitions = listOf(FacetOption(key = "all", name = "全部", fileCount = total, kind = FacetParamKind.SOURCE)),
        authors = emptyList(),
        characters = emptyList(),
        types = emptyList(),
    )

    // 预取避让替身（问题B 2026-09-28 构造签名适配）：单测不触达预取器，空实现即可
    private object NoopPrefetchThrottle : ThumbnailPrefetchThrottle {
        override fun pauseForForegroundRefresh() = Unit
    }

    private fun viewModel(
        repo: FakeMediaRepository,
        gridPrefs: FakeGridPrefsRepository = FakeGridPrefsRepository(),
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
        favoriteTracker: FavoriteMutationTracker = FavoriteMutationTracker(),
    ): FavoriteViewModel = FavoriteViewModel(
        mediaRepository = repo,
        gridPrefs = gridPrefs,
        batchIndex = batchIndex,
        favoriteMutationTracker = favoriteTracker,
        prefetchThrottle = NoopPrefetchThrottle,
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

            // 旧代 A 迟到归位：不得落地（修复前会整包覆盖 items；收藏页无总数展示面）
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("old-a")), total = 1))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading) // 新代 B 仍在途，loading 不被旧代收走

            // 新代 B 归位：落地
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("new-b")), total = 7))
            advanceUntilIdle()
            assertEquals(listOf("new-b"), viewModel.uiState.value.items.map { it.id })
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

    @Test
    fun `维度芯片点击 进页默认收起 已激活维切换展开收起 其他维切维并展开`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(FakeMediaRepository())
        advanceUntilIdle()

        // 进页默认收起（用户 2026-09-07 覆盖 B8）：点已激活维 → 展开；再点 → 收起
        // （镜像 AlbumViewModelTest 同名用例）
        assertFalse(viewModel.uiState.value.filter.expanded)
        viewModel.onDimChipClicked(AlbumDim.PARTITION)
        assertTrue(viewModel.uiState.value.filter.expanded)
        assertEquals(AlbumDim.PARTITION, viewModel.uiState.value.activeDim)
        viewModel.onDimChipClicked(AlbumDim.PARTITION)
        assertFalse(viewModel.uiState.value.filter.expanded)

        // 点其他维 → 切维并展开（切维强制展开保持，旧仓库实录四 Fragment 齐证）
        viewModel.onDimChipClicked(AlbumDim.TYPE)
        assertEquals(AlbumDim.TYPE, viewModel.uiState.value.activeDim)
        assertTrue(viewModel.uiState.value.filter.expanded)
    }

    @Test
    fun `空态文案分支 - 默认选双行逐字资源 COS分区选单行资源`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(FakeMediaRepository())
        advanceUntilIdle()

        // 默认分区 → 双行逐字空态（旧仓库 FavoriteFragment.kt L376）；文案本体由 strings.xml
        // favorite_empty_default 逐字锁定，本断言锁定分支选择（VM 只发资源 id 结构化语义）
        assertEquals(R.string.favorite_empty_default, viewModel.uiState.value.emptyTextRes)

        // COS 分区 → 单行「没有COS收藏」分支（L373-374）
        viewModel.selectPartition(Zone.COS)
        advanceUntilIdle()
        assertEquals(R.string.favorite_empty_cos, viewModel.uiState.value.emptyTextRes)
    }

    // ---------- 任务I I5：列数共用全部页档 + 双指缩放 + resume 重拉 ----------

    @Test
    fun `网格列数初始值读共用全部页档`() = runTest(mainDispatcherRule.testDispatcher) {
        // 档位预置 4（全部页此前调过）：进页 gridColumns 应读档为 4，而非缺省值
        val gridPrefs = FakeGridPrefsRepository()
        gridPrefs.albumFlow.value = 4
        val viewModel = viewModel(FakeMediaRepository(), gridPrefs)
        advanceUntilIdle()
        assertEquals(4, viewModel.gridColumns.value)
    }

    @Test
    fun `双指缩放步进clamp2到5 手势结束持久化到共用全部页档`() = runTest(mainDispatcherRule.testDispatcher) {
        val gridPrefs = FakeGridPrefsRepository()
        val viewModel = viewModel(FakeMediaRepository(), gridPrefs)
        advanceUntilIdle()

        // 放大减列到下界：2 → clamp 在 2（MIN_ALBUM_COLUMNS）
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

    @Test
    fun `收藏指纹门控 - 首次基线不误刷 纯浏览返回不重拉 收藏变更返回静默重拉第一页`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            // 任务V V1（2026-09-10）：ON_RESUME 无条件重拉改为 FavoriteMutationTracker 指纹门控
            val tracker = FavoriteMutationTracker()
            val viewModel = viewModel(repo, favoriteTracker = tracker)
            advanceUntilIdle()
            assertEquals(1, repo.assetsCalls.size) // init 首载在途

            // 首个 ON_RESUME 与 init 首载重叠：只采纳基线，不发起第二载
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(1, repo.assetsCalls.size)

            // 首载落地
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
            repo.completeFacetsBatch(batch = 0, result = facets(total = 1))
            advanceUntilIdle()

            // 纯浏览返回（无收藏变更）：指纹不变不重拉——零网络零重组（缺陷修复点）
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(1, repo.assetsCalls.size)

            // 详情页 toggleFavorite 成功（上报点）→ 返回：指纹变化 → 静默重拉第一页
            tracker.onFavoriteMutated()
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
            assertNull(repo.assetsCalls[1].query.cursor) // 重拉回第一页
            assertTrue(viewModel.uiState.value.isLoading) // 重拉在途
            assertFalse(viewModel.uiState.value.isRefreshing) // 静默：不置 isRefreshing（morph 期间指示器不闪）

            repo.completeFacetsBatch(batch = 1, result = facets(total = 1))
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"), asset("a"))))
            advanceUntilIdle()
            assertEquals(listOf("b", "a"), viewModel.uiState.value.items.map { it.id })
            assertFalse(viewModel.uiState.value.isLoading)

            // 重拉落地后的再次返回（无新变更）：不重拉
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
        }

    @Test
    fun `收藏指纹门控在途防重 - 重拉在途时新变更的resume不叠加 下次resume补拉`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val tracker = FavoriteMutationTracker()
            val viewModel = viewModel(repo, favoriteTracker = tracker)
            advanceUntilIdle()
            viewModel.onResumed() // 首个采纳基线
            repo.assetsCalls[0].gate.complete(page(items = emptyList()))
            repo.completeFacetsBatch(batch = 0, result = facets(total = 0))
            advanceUntilIdle()

            // 第一次变更触发静默重拉（闸门挂着不放）
            tracker.onFavoriteMutated()
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)

            // 在途期间再次变更+resume：被 isLoading 防重拦截且不采纳指纹，等下次 resume 重试
            tracker.onFavoriteMutated()
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)

            // 在途重拉落地后：未采纳的变更由下一次 resume 补拉
            repo.completeFacetsBatch(batch = 1, result = facets(total = 0))
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"))))
            advanceUntilIdle()
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(3, repo.assetsCalls.size)

            repo.completeFacetsBatch(batch = 2, result = facets(total = 0))
            repo.assetsCalls[2].gate.complete(page(items = listOf(asset("c"))))
            advanceUntilIdle()
            assertEquals(listOf("c"), viewModel.uiState.value.items.map { it.id })
        }

    // ---------- 2026-10-01 跳过门 TTL 化：跨端改动（Web/另一设备收藏）陈旧有界化 ----------
    // 指纹只感知本进程本端变更，跨端改动指纹永不变化——跳过条件叠加 STALE_AFTER_MS
    // staleTime 上界兜底（TanStack Query refetchOnWindowFocus 同款语义）。「指纹变了→
    // 立即重拉」半边由上方既有指纹门控用例继续锁定，此处只锁 TTL 半边。

    @Test
    fun `收藏跳过门TTL兜底 - 指纹未变TTL内返回不重拉 恰越过STALE_AFTER_MS静默重拉自愈`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val tracker = FavoriteMutationTracker()
            var fakeNow = 1_000_000L
            tracker.clockMs = { fakeNow } // Tracker 时钟注入：TTL 判定假钟推进全确定
            val viewModel = viewModel(repo, favoriteTracker = tracker)
            advanceUntilIdle()

            // 首载成功落地：noteListFetchCompleted 打点 = TTL 计时起点
            repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
            repo.completeFacetsBatch(batch = 0, result = facets(total = 1))
            advanceUntilIdle()
            viewModel.onResumed() // 基线采纳

            // 指纹未变且未过 TTL（纯浏览返回）：不重拉——「零网络零重组」优化保留
            fakeNow += STALE_AFTER_MS - 1
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(1, repo.assetsCalls.size)

            // 指纹未变但恰好越过 TTL：放行静默重拉（跨端改动无事件可感知，时间上界兜底）
            fakeNow += 1
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
            assertNull(repo.assetsCalls[1].query.cursor) // 静默重拉回第一页
            assertFalse(viewModel.uiState.value.isRefreshing) // 静默路径：指示器不闪

            // 重拉成功落地重新打点：回到新鲜窗口，再次返回不重拉（计时已重置）
            fakeNow += 1
            repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"))))
            repo.completeFacetsBatch(batch = 1, result = facets(total = 1))
            advanceUntilIdle()
            viewModel.onResumed()
            advanceUntilIdle()
            assertEquals(2, repo.assetsCalls.size)
        }

    // ---------- 批次上下文（2026-09-09 拍板：收藏/历史进详情补批次，首页同款机制） ----------

    @Test
    fun `enterDetail批次上下文 - 清单未落地时空批次 落地后含追加件且序号滑切可用`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val batchIndex = MediaBatchIndex()
            val viewModel = viewModel(repo, batchIndex = batchIndex)
            advanceUntilIdle()

            // 首载在途（清单未落地）点卡：批次为空表——详情页序号区不显示（空批次语义）
            viewModel.enterDetail("x")
            assertTrue(batchIndex.ids.isEmpty())
            assertEquals(-1, batchIndex.indexOf("x"))

            // 首载落地两件（带 cursor 可翻页）+ 翻页追加一件后点卡：批次 = 当前显示清单（含追加件，显示顺序）
            repo.assetsCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("a"), asset("b")), nextCursor = "c1", totalMatched = 2),
            )
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
}
