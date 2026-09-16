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
import media.qimeng.app.core.data.repository.TagNameConflictException
import media.qimeng.app.core.model.AlbumPanelDraft
import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.PanelCountRange
import media.qimeng.app.core.model.PanelFeedback
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 万能筛选面板（M4-2A-B3）ViewModel 行为测试：
 * 打开=草稿拷贝、应用=已应用态更新+走刷新链、重置=回默认+立即应用+关面板（旧版三合一口径）、
 * 关闭=丢弃草稿、标签增删后候选刷新、删除已选标签从 tagIds 移除、
 * 重名/失败反馈分流（修复轮 P2-1）。
 */
class AlbumFilterPanelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（跟随 AlbumViewModelTest 既有 Fake 模式；标签存储可变） ----------

    private class FakeMediaRepository : MediaRepository {
        data class AssetsCall(val query: AssetQuery, val gate: CompletableDeferred<AssetPageResult>)

        val assetsCalls = mutableListOf<AssetsCall>()

        override suspend fun assets(query: AssetQuery): AssetPageResult {
            val call = AssetsCall(query, CompletableDeferred())
            assetsCalls += call
            return call.gate.await()
        }

        override suspend fun facets(query: FacetsQuery): FacetsResult = emptyFacets

        override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> = emptyList()

        override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> = emptyList()

        override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> = emptyList()

        override suspend fun assetOrigUrl(assetId: String): String? = null

        val tagsStore = mutableListOf(TagSummary(id = "t1", name = "测试标签一"))

        private var tagSeq = 2

        /** 模拟服务端 409 判重（httpapi tags_test 口径）：集合内的名字 createTag 必抛领域化冲突异常 */
        val conflictNames = mutableSetOf<String>()

        /** 模拟删除失败（网络错误等非重名失败） */
        val failDeletes = mutableSetOf<String>()

        override suspend fun tags(): List<TagSummary> = tagsStore.toList()

        override suspend fun createTag(name: String): TagSummary {
            if (name in conflictNames) throw TagNameConflictException(name)
            return TagSummary(id = "t${tagSeq++}", name = name).also { tagsStore += it }
        }

        override suspend fun deleteTag(tagId: String) {
            if (tagId in failDeletes) throw java.io.IOException("delete failed")
            tagsStore.removeAll { it.id == tagId }
        }

        companion object {
            val emptyFacets = FacetsResult(
                partitions = listOf(
                    FacetOption(key = "all", name = "全部", fileCount = 0, kind = FacetParamKind.SOURCE),
                ),
                authors = emptyList(),
                characters = emptyList(),
                types = emptyList(),
            )
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

    private fun viewModel(repo: FakeMediaRepository): AlbumViewModel = AlbumViewModel(
        mediaRepository = repo,
        gridPrefs = FakeGridPrefs(),
        origUrlResolver = object : AssetOrigUrlResolver {
            override suspend fun origUrl(assetId: String): String? = null
        },
    )

    // ---------- 用例 ----------

    @Test
    fun `打开面板 - 草稿等于当前已应用面板值`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        // 先应用一版面板值（观看=未观看 + 已选标签）
        vm.openFilterSheet()
        vm.updatePanelDraft(AlbumPanelDraft(viewRange = PanelCountRange.NONE, tagIds = listOf("t1")))
        vm.applyPanelDraft()
        advanceUntilIdle()
        vm.dismissFilterSheet()

        vm.openFilterSheet()
        advanceUntilIdle()
        assertEquals(PanelCountRange.NONE, vm.panelState.value.draft.viewRange)
        assertEquals(listOf("t1"), vm.panelState.value.draft.tagIds)
        assertTrue(vm.panelState.value.visible)
    }

    @Test
    fun `应用筛选 - 草稿写入已应用态并触发刷新，面板关闭`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        vm.updatePanelDraft(AlbumPanelDraft(viewRange = PanelCountRange.NONE))
        vm.applyPanelDraft()
        advanceUntilIdle()

        assertFalse(vm.panelState.value.visible)
        assertEquals(PanelCountRange.NONE, vm.uiState.value.filter.viewRange)
        // 刷新链：应用后发出新的第一页请求，且 query 携带面板参数
        val appliedQuery = repo.assetsCalls.last().query
        assertEquals(PanelCountRange.NONE, appliedQuery.viewRange)
        assertNull(appliedQuery.cursor)
    }

    @Test
    fun `重置 - 草稿与已应用态回默认并触发刷新，面板关闭（旧版三合一口径）`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle() // 先放行 init 首屏请求等在途协程，再取基线，避免把 init 请求误记为刷新
        vm.updatePanelDraft(AlbumPanelDraft(viewRange = PanelCountRange.HIGH))
        val callsBeforeReset = repo.assetsCalls.size
        vm.resetPanelDraft()
        advanceUntilIdle()

        assertEquals(AlbumPanelDraft(), vm.panelState.value.draft)
        // 旧版重置=「dismiss+apply(默认)」三合一（旧仓库 MediaFilterSheet.kt L266-269）：立即关面板
        assertFalse(vm.panelState.value.visible)
        assertEquals(PanelCountRange.ALL, vm.uiState.value.filter.viewRange) // 已应用态同步回默认
        assertEquals(callsBeforeReset + 1, repo.assetsCalls.size) // 重置触发一次刷新
        val appliedQuery = repo.assetsCalls.last().query
        assertNull(appliedQuery.viewRange) // 刷新携带默认参数
        assertNull(appliedQuery.cursor)
    }

    @Test
    fun `关闭面板 - 丢弃草稿，已应用态不变`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        vm.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT))
        vm.dismissFilterSheet()
        advanceUntilIdle()

        assertFalse(vm.panelState.value.visible)
        assertEquals(AssetSort.DEFAULT, vm.uiState.value.filter.sort) // 2026-09-17 拍板变更：默认档=「默认」
        // 重开面板 = 重新拷贝已应用值
        vm.openFilterSheet()
        assertEquals(AlbumPanelDraft(), vm.panelState.value.draft)
    }

    @Test
    fun `标签候选 - 打开面板即拉取，新建后刷新`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        assertEquals(listOf("测试标签一"), vm.panelState.value.tags.map { it.name })

        vm.addTag("测试标签二")
        advanceUntilIdle()
        assertEquals(listOf("测试标签一", "测试标签二"), vm.panelState.value.tags.map { it.name })
    }

    @Test
    fun `删除已选标签 - 候选刷新且从草稿 tagIds 移除`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        vm.updatePanelDraft(AlbumPanelDraft(tagIds = listOf("t1", "t2")))
        vm.addTag("临时标签") // 生成 t2
        advanceUntilIdle()
        vm.updatePanelDraft(vm.panelState.value.draft.copy(tagIds = listOf("t1", "t2")))

        vm.deleteTag("t2")
        advanceUntilIdle()
        assertEquals(listOf("测试标签一"), vm.panelState.value.tags.map { it.name })
        assertEquals(listOf("t1"), vm.panelState.value.draft.tagIds)
    }

    @Test
    fun `删除未选标签 - 不动草稿 tagIds`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        vm.updatePanelDraft(AlbumPanelDraft(tagIds = listOf("t1")))
        vm.deleteTag("tX") // 不存在的 id
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.panelState.value.draft.tagIds)
    }

    // ---------- 排序经面板（任务L L4：页头排序行删除，排序唯一编辑入口回归面板） ----------

    @Test
    fun `面板选排序 - 观看次数档 sort=view_count 写入已应用态且请求携带`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val vm = viewModel(repo)
            vm.openFilterSheet()
            // 面板三档之一「观看次数」（2026-09-17 精简：默认/观看次数/文件大小），顺位=升序验证透传
            vm.updatePanelDraft(
                AlbumPanelDraft(sort = AssetSort.VIEW_COUNT, order = SortOrder.ASC),
            )
            vm.applyPanelDraft()
            advanceUntilIdle()

            assertEquals(AssetSort.VIEW_COUNT, vm.uiState.value.filter.sort)
            assertEquals(SortOrder.ASC, vm.uiState.value.filter.order)
            val appliedQuery = repo.assetsCalls.last().query
            assertEquals(AssetSort.VIEW_COUNT, appliedQuery.sort)
            assertEquals(SortOrder.ASC, appliedQuery.order)
        }

    @Test
    fun `重置清空排序 - 回默认档降序（2026-09-17 拍板）且刷新请求不携带非默认排序`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repo = FakeMediaRepository()
            val vm = viewModel(repo)
            // 先经面板应用非默认排序（观看次数为三档之一）
            vm.openFilterSheet()
            vm.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT, order = SortOrder.ASC))
            vm.applyPanelDraft()
            advanceUntilIdle()
            assertEquals(AssetSort.VIEW_COUNT, vm.uiState.value.filter.sort)

            vm.openFilterSheet()
            advanceUntilIdle() // 放行在途请求后再取基线，避免误记
            val callsBeforeReset = repo.assetsCalls.size
            vm.resetPanelDraft()
            advanceUntilIdle()

            assertEquals(AssetSort.DEFAULT, vm.uiState.value.filter.sort) // 2026-09-17 拍板变更
            assertEquals(SortOrder.DESC, vm.uiState.value.filter.order)
            assertEquals(AlbumPanelDraft(), vm.panelState.value.draft)
            val resetQuery = repo.assetsCalls.last().query
            assertEquals(AssetSort.DEFAULT, resetQuery.sort) // 2026-09-17 拍板变更
            assertEquals(SortOrder.DESC, resetQuery.order)
            assertEquals(callsBeforeReset + 1, repo.assetsCalls.size) // 重置触发一次刷新
        }

    @Test
    fun `面板内改排序只动草稿 - 未应用前列表请求不携带`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        val callsBeforeEdit = repo.assetsCalls.size

        vm.updatePanelDraft(AlbumPanelDraft(sort = AssetSort.VIEW_COUNT, order = SortOrder.ASC))
        advanceUntilIdle()

        // 草稿态：已应用态与请求不变（面板内点选不触发刷新——编辑态语义）
        assertEquals(AssetSort.DEFAULT, vm.uiState.value.filter.sort) // 2026-09-17 拍板变更
        assertEquals(callsBeforeEdit, repo.assetsCalls.size)
        assertEquals(AssetSort.VIEW_COUNT, vm.panelState.value.draft.sort)
    }

    // ---------- 面板操作反馈分流（修复轮 P2-1） ----------

    @Test
    fun `新建重名标签 - 候选预查重拦下并给已存在反馈，不发请求`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        val assetsCallsBefore = repo.assetsCalls.size
        vm.addTag("测试标签一") // 候选中已存在同名
        advanceUntilIdle()

        assertEquals(PanelFeedback.TagExists("测试标签一"), vm.panelState.value.message)
        assertEquals(assetsCallsBefore, repo.assetsCalls.size) // 预查重拦截，未触达服务端
    }

    @Test
    fun `新建标签 - 服务端 409 判重兜底同样给已存在反馈`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        // 预查重不命中（候选无此名），由服务端判重兜底
        repo.conflictNames += "撞名标签"
        vm.addTag("撞名标签")
        advanceUntilIdle()

        assertEquals(PanelFeedback.TagExists("撞名标签"), vm.panelState.value.message)
        assertFalse(vm.panelState.value.tags.any { it.name == "撞名标签" }) // 未被误加入候选
    }

    @Test
    fun `新建标签成功 - 清除面板反馈并刷新候选`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        // 先注册服务端冲突再调 addTag（复审 N1：原写法先 addTag 后注册冲突，第一次实为创建成功，
        // 反馈从未产生，末尾 assertNull 恒真假锁）
        repo.conflictNames += "先撞一次"
        vm.addTag("先撞一次")
        advanceUntilIdle()
        // 前置断言：TagExists 反馈确已产生，否则末尾的「清除」断言无锁力
        assertEquals(PanelFeedback.TagExists("先撞一次"), vm.panelState.value.message)

        vm.addTag("正常新标签")
        advanceUntilIdle()

        assertNull(vm.panelState.value.message) // 成功操作清除上一条真实存在的反馈
        assertTrue(vm.panelState.value.tags.any { it.name == "正常新标签" })
    }

    @Test
    fun `删除标签失败 - 给中性操作失败反馈而非加载失败`() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = FakeMediaRepository()
        val vm = viewModel(repo)
        vm.openFilterSheet()
        advanceUntilIdle()
        repo.failDeletes += "t1"
        vm.deleteTag("t1")
        advanceUntilIdle()

        assertEquals(PanelFeedback.OpFailed, vm.panelState.value.message)
        assertEquals(listOf("测试标签一"), vm.panelState.value.tags.map { it.name }) // 候选未变
    }
}
