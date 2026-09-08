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
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaRepository
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

    private fun viewModel(
        repo: FakeMediaRepository,
        gridPrefs: FakeGridPrefsRepository = FakeGridPrefsRepository(),
    ): FavoriteViewModel = FavoriteViewModel(
        mediaRepository = repo,
        gridPrefs = gridPrefs,
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
    fun `resume重拉 首次跳过不与首载叠加 后续每次resume重拉第一页`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()
        assertEquals(1, repo.assetsCalls.size) // init 首载在途

        // 首个 ON_RESUME 与 init 首载重叠：跳过，不发起第二载
        viewModel.onResumed()
        advanceUntilIdle()
        assertEquals(1, repo.assetsCalls.size)

        // 首载落地后，模拟详情页 toggleFavorite 后返回（第二次 ON_RESUME）：重拉第一页+候选
        repo.assetsCalls[0].gate.complete(page(items = listOf(asset("a"))))
        repo.completeFacetsBatch(batch = 0, result = facets(total = 1))
        advanceUntilIdle()

        viewModel.onResumed()
        advanceUntilIdle()
        assertEquals(2, repo.assetsCalls.size)
        assertNull(repo.assetsCalls[1].query.cursor) // 重拉回第一页

        repo.completeFacetsBatch(batch = 1, result = facets(total = 1))
        repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"), asset("a"))))
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), viewModel.uiState.value.items.map { it.id })
    }

    @Test
    fun `resume重拉在途防重 刷新在途时再次resume不叠加请求`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()
        viewModel.onResumed() // 首个跳过
        repo.assetsCalls[0].gate.complete(page(items = emptyList()))
        repo.completeFacetsBatch(batch = 0, result = facets(total = 0))
        advanceUntilIdle()

        // 第一次 resume 发起重拉（闸门挂着不放）；在途期间再次 resume：被 refresh 防重丢弃
        viewModel.onResumed()
        advanceUntilIdle()
        viewModel.onResumed()
        advanceUntilIdle()
        assertEquals(2, repo.assetsCalls.size)

        repo.assetsCalls[1].gate.complete(page(items = listOf(asset("b"))))
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.uiState.value.items.map { it.id })
    }
}
