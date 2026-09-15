package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
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
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 作者集合页 ViewModel 单测（任务G G1b 建；任务I I6 扩四维芯片子集）：
 * 锁定取数参数口径（authorId 固定收窄 + includeCos 恒 true）、维度子集（常规=作品/角色/
 * 类型、COS=角色/类型）、筛选→协议参数映射（作品→source、角色 kind 分派 character|work、
 * 类型→mediaType）、药丸展开/收起语义（拍板②）与列数共用全部页档（pinch 2~5）。
 */
class AuthorCollectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMediaRepository(private val endOfList: Boolean = false) : MediaRepository {
        val assetsCalls = mutableListOf<AssetQuery>()
        val facetsCalls = mutableListOf<FacetsQuery>()
        var assetsError: Exception? = null

        /** facets 可配置回包（默认空；作品维 SOURCE 子集裁剪测试用） */
        var facetsResult: FacetsResult = FacetsResult(
            partitions = emptyList(),
            authors = emptyList(),
            characters = emptyList(),
            types = emptyList(),
        )

        /** 造数在 fake 内（非 inner class 不能用外层实例方法） */
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

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            assetsCalls += query
            assetsError?.let { throw it }
            return AssetPageResult(
                items = listOf(asset("a-${assetsCalls.size}")),
                nextCursor = if (endOfList) null else "c${assetsCalls.size}",
                totalMatched = 42,
            )
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult {
            facetsCalls += query
            return facetsResult
        }

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null
    }

    /** 列数档替身：StateFlow 直存的内存档（模拟 grid_columns_all 键的读写，I5 同款） */
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

    private fun viewModel(
        repo: FakeMediaRepository,
        gridPrefs: FakeGridPrefsRepository = FakeGridPrefsRepository(),
        authorId: String? = "22222222-2222-2222-2222-222222222222",
        authorName: String? = "蠢沫",
        batchIndex: MediaBatchIndex = MediaBatchIndex(),
    ): AuthorCollectionViewModel = AuthorCollectionViewModel(
        mediaRepository = repo,
        gridPrefs = gridPrefs,
        batchIndex = batchIndex,
        origUrlResolver = object : media.qimeng.app.core.data.repository.AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
        savedStateHandle = SavedStateHandle(
            buildMap {
                authorId?.let { put(AuthorCollectionRoutes.KEY_AUTHOR_ID, it) }
                authorName?.let { put(AuthorCollectionRoutes.KEY_AUTHOR_NAME, it) }
            },
        ),
    )

    // ---------- 任务G G1b：取数/分页/容错（既有口径保持） ----------

    @Test
    fun `取数参数 - authorId精确过滤 includeCos恒true 分页大小单源`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        viewModel(repo)
        advanceUntilIdle()
        assertEquals(1, repo.assetsCalls.size)
        val query = repo.assetsCalls.first()
        assertEquals("22222222-2222-2222-2222-222222222222", query.authorId)
        assertEquals(true, query.includeCos)
        assertEquals(LIST_PAGE_SIZE, query.limit)
        assertNull(query.cursor) // 首载无 cursor
    }

    @Test
    fun `onNearBottom - 带nextCursor增量取数 追加不替换`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onNearBottom()
        advanceUntilIdle()
        val second = repo.assetsCalls[1]
        assertEquals("c1", second.cursor)
        assertEquals(listOf("a-1", "a-2"), vm.uiState.value.items.map { it.id })
        assertEquals(42, vm.uiState.value.totalMatched)
    }

    @Test
    fun `onNearBottom - 无nextCursor不取数`() = runTest(mainDispatcherRule.testDispatcher) {
        // 末页语义：fake 回 nextCursor=null，到底后 onNearBottom 不再发请求
        val repo = FakeMediaRepository(endOfList = true)
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onNearBottom()
        assertEquals(1, repo.assetsCalls.size) // 只有 init 首载
    }

    @Test
    fun `加载失败 - 列表失败文案 兜底不清空已加载项`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        repo.assetsError = RuntimeException("boom")
        vm.onNearBottom() // 第二页失败
        advanceUntilIdle()
        assertEquals("加载失败，请下拉重试", vm.uiState.value.errorMessage)
        assertEquals(listOf("a-1"), vm.uiState.value.items.map { it.id }) // 首页内容保留
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `缺参容错 - authorId缺失不发请求空态`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo, authorId = null, authorName = null)
        advanceUntilIdle()
        assertTrue(repo.assetsCalls.isEmpty())
        assertTrue(repo.facetsCalls.isEmpty())
        assertEquals("", vm.authorName)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `路由名透传 - authorName不参与取数仅展示`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo, authorName = "某作者")
        advanceUntilIdle()
        assertEquals("某作者", vm.authorName)
        assertEquals("22222222-2222-2222-2222-222222222222", repo.assetsCalls.first().authorId)
    }

    // ---------- 任务I I6：维度子集（GUIDE_UI §芯片栏配置对比 L68-69） ----------

    @Test
    fun `维度子集纯函数 - 常规=作品角色类型 COS=角色类型`() {
        assertEquals(
            listOf(AlbumDim.AUTHOR, AlbumDim.CHARACTER, AlbumDim.TYPE),
            authorCollectionDims(isCos = false),
        )
        assertEquals(
            listOf(AlbumDim.CHARACTER, AlbumDim.TYPE),
            authorCollectionDims(isCos = true),
        )
    }

    @Test
    fun `COS判定 - cos_前缀 openapi Author id 口径`() {
        assertTrue(isCosAuthorId("cos_某某"))
        assertFalse(isCosAuthorId("22222222-2222-2222-2222-222222222222"))
        // 边界：纯前缀本身也是 COS（generateAuthorId 规则不产生该 id 形态）
        assertTrue(isCosAuthorId("cos_"))
        assertFalse(isCosAuthorId("cos"))
    }

    @Test
    fun `VM维度子集 - 常规作者激活维默认作品 COS作者激活维默认角色`() = runTest(mainDispatcherRule.testDispatcher) {
        val regular = viewModel(FakeMediaRepository())
        assertEquals(listOf(AlbumDim.AUTHOR, AlbumDim.CHARACTER, AlbumDim.TYPE), regular.dims)
        assertEquals(AlbumDim.AUTHOR, regular.uiState.value.activeDim)

        val cos = viewModel(FakeMediaRepository(), authorId = "cos_某某")
        assertEquals(listOf(AlbumDim.CHARACTER, AlbumDim.TYPE), cos.dims)
        assertEquals(AlbumDim.CHARACTER, cos.uiState.value.activeDim)
    }

    @Test
    fun `COS作者 - 不拉作品维facets 三维查询恒带固定authorId收窄`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        viewModel(repo, authorId = "cos_某某")
        advanceUntilIdle()
        // 常规作者三路（作品/角色/类型）；COS 两路（角色/类型）——作品维不适用
        assertEquals(2, repo.facetsCalls.size)
        repo.facetsCalls.forEach { query ->
            assertEquals("cos_某某", query.authorId) // 固定集合作者收窄
            assertEquals(media.qimeng.app.core.model.Zone.ALL, query.partition) // 本页无分区芯片
        }
    }

    // ---------- 任务I I6：筛选→协议参数映射（collectionAssetQuery） ----------

    @Test
    fun `筛选映射 - 选作品走source 选角色按kind分派 选类型走mediaType authorId恒定`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        // 作品维（常规作者，候选恒 kind=SOURCE）→ source 参数
        vm.selectAuthor(FacetOption("幻想乡", "幻想乡", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()
        run {
            val q = repo.assetsCalls.last()
            assertEquals(listOf("幻想乡"), q.source)
            assertEquals("22222222-2222-2222-2222-222222222222", q.authorId) // 固定作者不被覆盖
            assertEquals(true, q.includeCos)
            assertNull(q.character)
            assertNull(q.work)
        }

        // 角色维 kind=CHARACTER（常规作者文件的角色）→ character 参数
        vm.selectCharacter(FacetOption("灵梦", "灵梦", 5, FacetParamKind.CHARACTER))
        advanceUntilIdle()
        run {
            val q = repo.assetsCalls.last()
            assertEquals(listOf("灵梦"), q.character)
            assertEquals(listOf("幻想乡"), q.source) // 作品维选择保持（递归筛选）
            assertNull(q.work)
        }

        // 角色维 kind=WORK（COS 作者文件按作品名分组）→ work 参数（常规作者页不可达，映射仍锁定）；
        // 角色行多选（N4）：kind=CHARACTER 与 kind=WORK 同行共存不互斥（同维 OR）
        vm.selectCharacter(FacetOption("作品A", "作品A", 7, FacetParamKind.WORK))
        advanceUntilIdle()
        run {
            val q = repo.assetsCalls.last()
            assertEquals(listOf("灵梦"), q.character)
            assertEquals(listOf("作品A"), q.work)
        }

        // 类型维 → mediaType；再点同档=取消（selectMediaType toggle）
        vm.selectMediaType(MediaKind.VIDEO)
        advanceUntilIdle()
        assertEquals(MediaKind.VIDEO, repo.assetsCalls.last().mediaType)
        vm.selectMediaType(MediaKind.VIDEO)
        advanceUntilIdle()
        assertNull(repo.assetsCalls.last().mediaType)
    }

    @Test
    fun `筛选重拉 - 维选择后回第一页重拉 items替换不追加`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onNearBottom() // 先翻一页
        advanceUntilIdle()
        assertEquals(2, repo.assetsCalls.size)
        assertEquals(listOf("a-1", "a-2"), vm.uiState.value.items.map { it.id })

        vm.selectMediaType(MediaKind.VIDEO) // 筛选变化
        advanceUntilIdle()
        val reload = repo.assetsCalls.last()
        assertNull(reload.cursor) // 回第一页
        assertEquals(listOf("a-3"), vm.uiState.value.items.map { it.id }) // items 替换不追加
    }

    @Test
    fun `facets联动 - 选作品后角色类型候选带source收窄 作品维候选排自身`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val before = repo.facetsCalls.size

        vm.selectAuthor(FacetOption("幻想乡", "幻想乡", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()
        // 新一轮三路（计数断言不依赖并发完成序；角色/类型两路在无其他选择时同形）：
        // 作品维排自身（source 不传）恰好 1 路，角色/类型维带 source 收窄恰 2 路
        val round = repo.facetsCalls.drop(before)
        assertEquals(3, round.size)
        assertEquals(1, round.count { it.source == null })
        assertEquals(2, round.count { it.source == "幻想乡" })
        assertTrue(round.all { it.character == null && it.work == null }) // 无角色选择外泄
        assertTrue(round.all { it.authorId == "22222222-2222-2222-2222-222222222222" }) // 恒收窄
    }

    @Test
    fun `facets联动 - 选角色后作品维候选带character收窄 角色维排自身`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val before = repo.facetsCalls.size

        vm.selectCharacter(FacetOption("灵梦", "灵梦", 5, FacetParamKind.CHARACTER))
        advanceUntilIdle()
        val round = repo.facetsCalls.drop(before)
        assertEquals(3, round.size)
        // 角色维排自身（character/work 不传）恰好 1 路；作品/类型维带 character 收窄恰 2 路
        assertEquals(1, round.count { it.character == null && it.work == null })
        assertEquals(2, round.count { it.character == "灵梦" })
    }

    @Test
    fun `作品维候选 - 直接消费authors桶 其他沉底（N34兜底解除）`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        // #34 已修（facets 作者行按固定 authorId 收窄）：消费侧 kind=SOURCE 兜底过滤
        // 已解除——authors 桶原样透出，仅「其他」恒沉底（withOtherBucketLast）
        repo.facetsResult = FacetsResult(
            partitions = emptyList(),
            authors = listOf(
                FacetOption("幻想乡", "幻想乡", 12, FacetParamKind.SOURCE),
                FacetOption("其他", "其他", 18, FacetParamKind.SOURCE),
            ),
            characters = listOf(FacetOption("测试作品M", "测试作品M", 3, FacetParamKind.WORK)),
            types = emptyList(),
        )
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(
            listOf("幻想乡", "其他"), // 「其他」恒沉底
            vm.uiState.value.authorOptions.map { it.name },
        )
        assertEquals(listOf("测试作品M"), vm.uiState.value.characterOptions.map { it.name })
    }

    @Test
    fun `行内全部 - payload=null清本行选择`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.selectAuthor(FacetOption("幻想乡", "幻想乡", 12, FacetParamKind.SOURCE))
        advanceUntilIdle()
        assertEquals(listOf("幻想乡"), repo.assetsCalls.last().source)
        vm.selectAuthor(null) // 「全部」胶囊 = 清作品行
        advanceUntilIdle()
        assertNull(repo.assetsCalls.last().source)
    }

    // ---------- 任务I I6：药丸展开/收起（拍板②） ----------

    @Test
    fun `药丸容器 - 进页默认收起 切维度强制展开 再点同维收起`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(FakeMediaRepository())
        advanceUntilIdle()
        // 进页默认收起（拍板② 覆盖 GUIDE_UI B8）
        assertFalse(vm.uiState.value.filter.expanded)

        // 点已激活维（作品）= 切换展开
        vm.onDimChipClicked(AlbumDim.AUTHOR)
        assertTrue(vm.uiState.value.filter.expanded)
        // 再点同维 = 收起
        vm.onDimChipClicked(AlbumDim.AUTHOR)
        assertFalse(vm.uiState.value.filter.expanded)

        // 点其他维 = 切维并强制展开
        vm.onDimChipClicked(AlbumDim.TYPE)
        assertEquals(AlbumDim.TYPE, vm.uiState.value.activeDim)
        assertTrue(vm.uiState.value.filter.expanded)
    }

    // ---------- 任务I I6：列数共用全部页档 + 双指缩放（I5 同款） ----------

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

    // ---------- 批次上下文（RES R4：作者集合页进详情补批次，台账 #21 余量清偿） ----------

    @Test
    fun `enterDetail批次上下文 - 清单落地后批次含翻页追加件 序号滑切可用 筛选重拉整体替换`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val batchIndex = MediaBatchIndex()
            val viewModel = viewModel(repo, batchIndex = batchIndex)
            advanceUntilIdle()

            // 首载落地一件（a-1）+ 翻页追加一件（a-2）后点卡：批次 = 当前显示清单（显示顺序）
            viewModel.onNearBottom()
            advanceUntilIdle()
            assertEquals(listOf("a-1", "a-2"), viewModel.uiState.value.items.map { it.id })
            viewModel.enterDetail("a-2")
            assertEquals(listOf("a-1", "a-2"), batchIndex.ids)
            assertEquals(1, batchIndex.indexOf("a-2"))
            assertEquals(2, batchIndex.size())

            // 滑切数据链（详情页 moveBy 消费）：邻位可达、首件向前越界返回 null
            assertEquals("a-1", batchIndex.assetIdAt(batchIndex.indexOf("a-2"), -1))
            assertNull(batchIndex.assetIdAt(batchIndex.indexOf("a-1"), -1))

            // 筛选重拉（清单整体换血为 a-3）：快照式整体替换——再次点卡批次不再含旧清单
            viewModel.selectMediaType(MediaKind.VIDEO)
            advanceUntilIdle()
            viewModel.enterDetail("a-3")
            assertEquals(listOf("a-3"), batchIndex.ids)
            assertEquals(-1, batchIndex.indexOf("a-1"))
        }
}
