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
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchThrottle
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.APPEND_RETRY_DELAYS_MS
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.FakeUploadRepository
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

        // M4-2A-B3 万能筛选面板标签族：可变存储，供面板行为用例断言增删后候选刷新
        val tagsStore = mutableListOf(TagSummary(id = "t1", name = "测试标签一"))

        private var tagSeq = 2

        override suspend fun tags(): List<TagSummary> = tagsStore.toList()

        override suspend fun createTag(name: String): TagSummary =
            TagSummary(id = "t${tagSeq++}", name = name).also { tagsStore += it }

        override suspend fun deleteTag(tagId: String) {
            tagsStore.removeAll { it.id == tagId }
        }

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

    // 预取避让替身（问题B 2026-09-28 构造签名适配）：单测不触达预取器，空实现即可
    private object NoopPrefetchThrottle : ThumbnailPrefetchThrottle {
        override fun pauseForForegroundRefresh() = Unit
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
        prefs: FakeGridPrefs = FakeGridPrefs(),
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
        authorRepo: media.qimeng.app.core.testing.FakeAuthorRepository = media.qimeng.app.core.testing.FakeAuthorRepository(),
        uploadRepo: media.qimeng.app.core.testing.FakeUploadRepository = media.qimeng.app.core.testing.FakeUploadRepository(),
    ): AlbumViewModel = AlbumViewModel(
        mediaRepository = repo,
        gridPrefs = prefs,
        batchIndex = batchIndex,
        prefetchThrottle = NoopPrefetchThrottle,
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
        authorRepository = authorRepo,
        uploadRepository = uploadRepo,
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

    @Test
    fun `翻页时服务端返回 null totalMatched 保持首屏统计总数不被覆盖冲掉`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val viewModel = viewModel(repo)
        advanceUntilIdle()

        // 首屏返回 totalMatched = 50
        repo.assetsCalls[0].gate.complete(
            AssetPageResult(items = listOf(asset("a")), nextCursor = "c1", totalMatched = 50),
        )
        repo.completeFacetsBatch(batch = 0, result = facets(total = 50))
        advanceUntilIdle()
        assertEquals(50, viewModel.uiState.value.totalMatched)

        // 翻页触发：服务端优化性能翻页 totalMatched 为 null
        viewModel.onNearBottom()
        advanceUntilIdle()
        repo.assetsCalls[1].gate.complete(
            AssetPageResult(items = listOf(asset("b")), nextCursor = null, totalMatched = null),
        )
        advanceUntilIdle()

        // 翻页后 totalMatched 依然保留 50，不被冲为 null
        assertEquals(50, viewModel.uiState.value.totalMatched)
    }

    @Test
    fun `双指缩放列数越界 clamp 2 到 5 手势结束持久化一次`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val prefs = FakeGridPrefs() // 初始 3 列
        val viewModel = viewModel(repo, prefs)
        advanceUntilIdle()

        // 放大方向越界：clamp 到上限 5
        viewModel.adjustColumnsLive(+99)
        assertEquals(5, viewModel.pinchColumns.value)
        viewModel.commitPinchColumns()
        advanceUntilIdle()
        assertEquals(5, prefs.albumColumns.value)
        assertNull(viewModel.pinchColumns.value) // 手势结束即复位，展示回落到持久化值

        // 缩小方向越界：clamp 到下限 2
        viewModel.adjustColumnsLive(-99)
        assertEquals(2, viewModel.pinchColumns.value)
        viewModel.commitPinchColumns()
        advanceUntilIdle()
        assertEquals(2, prefs.albumColumns.value)
    }

    @Test
    fun `无步进手势结束不落盘`() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakeGridPrefs()
        val viewModel = viewModel(FakeMediaRepository(), prefs)
        advanceUntilIdle()

        // 手势结束但没有任何步进（pinchColumns 为 null）：不触发仓库写
        viewModel.commitPinchColumns()
        advanceUntilIdle()
        assertEquals(3, prefs.albumColumns.value)
        assertNull(viewModel.pinchColumns.value)
    }

    @Test
    fun `维度芯片点击 进页默认收起 已激活维切换展开收起 其他维切维并展开`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(FakeMediaRepository())
        advanceUntilIdle()

        // 进页默认收起（用户 2026-09-07 覆盖 B8）：点已激活维 → 展开；再点 → 收起
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

    // ---------- 批次上下文（详情页 i/N 与左右滑数据链，收藏/历史/首页同款机制） ----------

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

            // 首载落地两件（带 cursor 可翻页）+ 翻页追加一件后点卡：批次 = 当前筛选后显示清单（含追加件，显示顺序）
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

    // ---------- 问题A（2026-09-28）：翻页失败自动重试（APPEND_RETRY_DELAYS_MS 退避封顶） ----------

    @Test
    fun `翻页失败自动重试 - 两次退避重试仍失败后停手，错误横幅保留且不再多发请求`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val viewModel = viewModel(repo)
            advanceUntilIdle()

            // 首页落地一件（带 cursor 可翻页）
            repo.assetsCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("a")), nextCursor = "c1", totalMatched = 1),
            )
            advanceUntilIdle()
            assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })

            // 触底翻页：首次 + 两次退避重试（1s/3s，虚拟时间即刻跳过）全部失败
            viewModel.onNearBottom()
            advanceUntilIdle() // 请求注册在 viewModelScope.launch 内，先推进调度再取 gate
            val firstAppendIndex = 1
            repeat(1 + APPEND_RETRY_DELAYS_MS.size) { attempt ->
                // 逐发失败：每轮失败→delay 退避（advanceUntilIdle 跳过）→ 下一请求注册
                repo.assetsCalls[firstAppendIndex + attempt].gate.completeExceptionally(
                    RuntimeException("injected append failure $attempt"),
                )
                advanceUntilIdle()
            }

            // 上限封顶：首载 1 + 翻页 3 次尝试（1 首发 + 2 重试），无无限锤击
            assertEquals(1 + 1 + APPEND_RETRY_DELAYS_MS.size, repo.assetsCalls.size)
            // 仍失败停手：错误横幅保留（真故障反馈），isLoading 收圈，已载数据不被清
            assertEquals(LIST_LOAD_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(listOf("a"), viewModel.uiState.value.items.map { it.id })
        }

    @Test
    fun `多选状态机 - startSelection进入多选、toggle切换、全选与退出多选`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val viewModel = viewModel(repo)
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("a1"), asset("a2"), asset("a3")), nextCursor = null, totalMatched = 3),
            )
            advanceUntilIdle()

            // 1. 长按进入多选
            viewModel.startSelection("a1")
            assertTrue(viewModel.uiState.value.isSelectionMode)
            assertEquals(setOf("a1"), viewModel.uiState.value.selectedAssetIds)

            // 2. 点击切换选中
            viewModel.toggleAssetSelection("a2")
            assertEquals(setOf("a1", "a2"), viewModel.uiState.value.selectedAssetIds)
            viewModel.toggleAssetSelection("a1")
            assertEquals(setOf("a2"), viewModel.uiState.value.selectedAssetIds)

            // 3. 全选
            viewModel.selectAll()
            assertEquals(setOf("a1", "a2", "a3"), viewModel.uiState.value.selectedAssetIds)

            // 4. 退出多选
            viewModel.exitSelectionMode()
            assertFalse(viewModel.uiState.value.isSelectionMode)
            assertTrue(viewModel.uiState.value.selectedAssetIds.isEmpty())
        }

    @Test
    fun `批量设置作者 - 提交后调用replaceAssetAuthors并追加来源，成功后退出多选并刷新相册`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val authorRepo = FakeAuthorRepository()
            val viewModel = viewModel(repo, authorRepo = authorRepo)
            advanceUntilIdle()
            repo.assetsCalls[0].gate.complete(
                AssetPageResult(items = listOf(asset("a1"), asset("a2")), nextCursor = null, totalMatched = 2),
            )
            advanceUntilIdle()

            // 选中 a1, a2
            viewModel.startSelection("a1")
            viewModel.toggleAssetSelection("a2")

            // 打开抽屉并配置作者和来源
            viewModel.openBatchAuthorSheet()
            assertTrue(viewModel.batchAuthorState.value.visible)

            val chosenAuthor = media.qimeng.app.core.model.AuthorSuggestion(id = "auth-1", displayName = "TestAuthor", fileCount = 5)
            viewModel.onPickBatchAuthor(chosenAuthor)
            viewModel.onToggleBatchSource("Pixiv")

            // 提交批量设置
            viewModel.submitBatchAuthor()
            advanceUntilIdle()

            // 验证作者挂靠和来源并入调用
            assertEquals(2, authorRepo.replaceAssetAuthorCalls.size)
            assertTrue(authorRepo.replaceAssetAuthorCalls.contains("a1" to listOf("auth-1")))
            assertTrue(authorRepo.replaceAssetAuthorCalls.contains("a2" to listOf("auth-1")))
            assertEquals(listOf("auth-1" to listOf("Pixiv")), authorRepo.appendSourceCalls)

            // 验证状态重置与提示
            assertFalse(viewModel.batchAuthorState.value.visible)
            assertFalse(viewModel.uiState.value.isSelectionMode)
            assertTrue(viewModel.uiState.value.selectedAssetIds.isEmpty())
            assertTrue(viewModel.uiState.value.userNoticeMessage?.contains("TestAuthor") == true)
        }
}

