package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 资产编辑页 ViewModel 锁定（2026-09-25 上传挂靠退役批）：装配回显（作者全集 + 逐作者
 * 来源）/ 添加作者（仅既有作者）/ 移除与记脏 / 保存调用仓（全集 PUT + 脏来源逐 PUT）/
 * 错误与提示呈现 / canSave 门控。生产实现 = SdkAuthorRepository/SdkDetailRepository
 * （core:data），此处只锁 VM 编排。
 */
class AssetEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val authorA = DetailAuthor(id = "a-1", displayName = "作者A", isCos = false, followed = false)
    private val authorB = DetailAuthor(id = "a-2", displayName = "作者B", isCos = true, followed = true)

    private fun savedHandle(assetId: String? = "asset-1"): SavedStateHandle =
        if (assetId == null) {
            SavedStateHandle()
        } else {
            SavedStateHandle(mapOf(AssetEditRoutes.KEY_ASSET_ID to assetId))
        }

    private fun newViewModel(
        detailRepo: FakeEditDetailRepository = FakeEditDetailRepository().apply {
            detailResult = detail(authors = listOf(authorA, authorB))
        },
        authorRepo: FakeEditAuthorRepository = FakeEditAuthorRepository().apply {
            sourcesByAuthor["a-1"] = listOf("site-a")
        },
        suggestRepo: FakeUploadRepository = FakeUploadRepository(),
    ): Triple<AssetEditViewModel, FakeEditDetailRepository, FakeEditAuthorRepository> {
        val viewModel = AssetEditViewModel(
            savedStateHandle = savedHandle(),
            detailRepository = detailRepo,
            authorRepository = authorRepo,
            uploadRepository = suggestRepo,
        )
        driveIdle()
        return Triple(viewModel, detailRepo, authorRepo)
    }

    private fun detail(
        authors: List<DetailAuthor>,
        fileName: String = "asset-1.jpg",
        libraryId: String = "lib-1",
        directory: String? = "sub",
    ) = AssetDetail(
        id = "asset-1",
        fileName = fileName,
        title = fileName,
        mediaType = MediaKind.IMAGE,
        sizeBytes = null,
        modifiedAtMs = null,
        source = null,
        isFavorite = false,
        likeCount = 0,
        likedToday = false,
        thumbUrl = null,
        origUrl = null,
        durationMs = null,
        cosWork = null,
        lastPositionSeconds = null,
        viewCount = null,
        playCount = null,
        width = null,
        height = null,
        tags = emptyList(),
        authors = authors,
        libraryId = libraryId,
        directory = directory,
    )

    // ---- 装配回显 ----

    @Test
    fun `装配回显作者全集与逐作者来源`() {
        val (viewModel, _, authorRepo) = newViewModel()
        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("asset-1.jpg", state.assetTitle)
        assertEquals(listOf("a-1", "a-2"), state.authors.map { it.id })
        // a-1 服务端回显 [site-a]；a-2 无回显条目 → 空编辑副本
        assertEquals(listOf("site-a"), state.sourcesByAuthor["a-1"])
        assertEquals(emptyList<String>(), state.sourcesByAuthor["a-2"].orEmpty())
        assertTrue(state.sourceOptions.contains("pixiv"))
        assertFalse(state.canSave)
        assertNull(state.errorMessage)
        // 词表取自 GET /authors/source-vocabulary
        assertTrue(authorRepo.vocabularyCalled)
    }

    @Test
    fun `装配回显文件名基名与扩展名`() {
        val (viewModel, _, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply {
                detailResult = detail(authors = emptyList(), fileName = "测试视频.mp4", libraryId = "lib-test")
            },
        )
        val state = viewModel.uiState.value
        assertEquals("测试视频.mp4", state.assetTitle)
        assertEquals("测试视频", state.currentBaseName)
        assertEquals(".mp4", state.extension)
        assertEquals("lib-test", state.libraryId)
        assertFalse(state.fileNameChanged)
        assertFalse(state.canSave)
    }

    @Test
    fun `文件名输入变化支持清空且不自动回弹原名`() {
        val (viewModel, _, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply {
                detailResult = detail(authors = emptyList(), fileName = "原名.mp4")
            },
        )
        viewModel.onFileNameChange("")
        val state = viewModel.uiState.value
        assertEquals("", state.currentBaseName)
        // 基名清空时 effectiveFileName 安全回退原完整名，且空基名不解锁保存
        assertEquals("原名.mp4", state.effectiveFileName)
        assertFalse(state.canSave)

        viewModel.onFileNameChange("新名字")
        val state2 = viewModel.uiState.value
        assertEquals("新名字", state2.currentBaseName)
        assertEquals("新名字.mp4", state2.effectiveFileName)
        assertTrue(state2.fileNameChanged)
        assertTrue(state2.canSave)
    }

    @Test
    fun `文件名输入防抖拉取作品名序号联想并点选采用`() {
        val suggestRepo = FakeUploadRepository().apply {
            suggestNamesResult = { q -> if (q == "碧蓝航线") listOf("碧蓝航线 02", "碧蓝航线 (3)") else emptyList() }
        }
        val (viewModel, _, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply {
                detailResult = detail(authors = emptyList(), fileName = "01.mp4", libraryId = "lib-azur")
            },
            suggestRepo = suggestRepo,
        )

        viewModel.onFileNameChange("碧蓝航线")
        driveIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("碧蓝航线 02", "碧蓝航线 (3)"), state.nameSuggestions)

        viewModel.pickFileNameSuggestion("碧蓝航线 02")
        val pickedState = viewModel.uiState.value
        assertEquals("碧蓝航线 02", pickedState.currentBaseName)
        assertEquals("碧蓝航线 02.mp4", pickedState.effectiveFileName)
        assertTrue(pickedState.nameSuggestions.isEmpty())
        assertTrue(pickedState.canSave)
    }

    @Test
    fun `仅修改文件名时触发 moveAsset 保存且成功返回`() {
        val (viewModel, detailRepo, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply {
                detailResult = detail(authors = emptyList(), fileName = "旧名.mp4", directory = "movies")
            },
        )
        viewModel.onFileNameChange("新名")
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.save()
        driveIdle()

        val state = viewModel.uiState.value
        assertTrue(state.saved)
        assertFalse(state.saving)
        assertEquals(1, detailRepo.moveCalls.size)
        assertEquals(Triple("asset-1", "movies", "新名.mp4"), detailRepo.moveCalls.single())
    }

    @Test
    fun `修改文件名同名冲突抛出 MoveConflictException 时呈现针对性错误提示`() {
        val (viewModel, _, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply {
                detailResult = detail(authors = emptyList(), fileName = "旧名.mp4")
                moveError = media.qimeng.app.core.data.repository.MoveConflictException()
            },
        )
        viewModel.onFileNameChange("冲突名")
        viewModel.save()
        driveIdle()

        val state = viewModel.uiState.value
        assertFalse(state.saved)
        assertEquals("目标目录已存在同名文件", state.errorMessage)
    }

    @Test
    fun `加载失败呈现错误横幅`() {
        val (viewModel, _, _) = newViewModel(
            detailRepo = FakeEditDetailRepository().apply { detailError = IllegalStateException("500") },
        )
        // newViewModel 内已 driveIdle：装配协程已跑完并失败
        assertEquals("加载资产信息失败，请重试", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.saved)
    }

    // ---- 添加作者 ----

    @Test
    fun `添加既有作者进入全集并解锁保存`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.addAuthor(AuthorSuggestion("a-3", "作者C", 1))
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("a-1", "a-2", "a-3"), state.authors.map { it.id })
        assertTrue(state.authorsChanged)
        assertTrue(state.canSave)
        assertEquals("", state.authorQuery)
    }

    @Test
    fun `重复添加同一作者给提示不进全集`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.addAuthor(AuthorSuggestion("a-1", "作者A", 3))
        driveIdle()
        assertEquals("该作者已关联", viewModel.uiState.value.noticeMessage)
        assertEquals(2, viewModel.uiState.value.authors.size)
    }

    @Test
    fun `联想未命中回车提示仅能关联既有作者`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.onAddAuthorQueryChange("全新作者")
        driveIdle() // 防抖后联想返回空（默认 suggestResult 空）
        viewModel.commitAddAuthor()
        driveIdle()
        assertEquals(
            "未找到该作者：编辑页仅能关联已有作者，请从联想中选择",
            viewModel.uiState.value.noticeMessage,
        )
        assertFalse(viewModel.uiState.value.authorsChanged)
    }

    @Test
    fun `移除作者连带清来源脏标记`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.toggleSource("a-1", "site-a") // 记脏
        viewModel.removeAuthor("a-1")
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("a-2"), state.authors.map { it.id })
        assertFalse("a-1" in state.dirtySourceAuthorIds)
        assertFalse("a-1" in state.sourcesByAuthor)
        assertTrue(state.canSave) // 作者全集已变
    }

    // ---- 来源编辑与保存 ----

    @Test
    fun `toggleSource记脏且保存逐作者PUT与全集PUT`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.toggleSource("a-1", "site-f") // 回显 [site-a] + site-f
        viewModel.toggleSource("a-2", "自定义站")
        viewModel.addAuthor(AuthorSuggestion("a-3", "作者C", 1))
        driveIdle()
        viewModel.save()
        driveIdle()
        // 全集 PUT：作者全集整体替换（含新增 a-3）
        assertEquals(listOf("a-1", "a-2", "a-3"), authorRepo.assetAuthorsCalls.single().second)
        // 逐作者 PUT：只覆盖脏作者
        assertEquals(
            listOf("site-a", "site-f"),
            authorRepo.sourceReplaceCalls.single { it.first == "a-1" }.second,
        )
        assertEquals(
            listOf("自定义站"),
            authorRepo.sourceReplaceCalls.single { it.first == "a-2" }.second,
        )
        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `保存失败呈现错误横幅且不置saved`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.toggleSource("a-1", "site-f")
        driveIdle()
        authorRepo.replaceError = IllegalStateException("500")
        viewModel.save()
        driveIdle()
        assertEquals("保存失败：请重试", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saved)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `无变更时保存门控拦截`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.save()
        driveIdle()
        assertTrue(authorRepo.assetAuthorsCalls.isEmpty())
        assertTrue(authorRepo.sourceReplaceCalls.isEmpty())
    }

    @Test
    fun `详情暂不可达时保存挂起不置saved`() {
        val gate = CompletableDeferred<Unit>()
        val authorRepo = FakeEditAuthorRepository().apply { replaceAssetAuthorsGate = gate }
        val (viewModel, _, _) = newViewModel(authorRepo = authorRepo)
        viewModel.toggleSource("a-1", "site-f")
        driveIdle()
        viewModel.save()
        driveIdle() // 保存挂起在 gate
        assertTrue(viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.saved)
        gate.complete(Unit)
        driveIdle()
        assertTrue(viewModel.uiState.value.saved)
    }
}

/**
 * 编辑页作者仓测试替身：来源词表/逐作者回显/两个 PUT 全部记账（可编程异常与挂起门控）。
 * 接口新增方法带默认实现（DetailViewModelTest.FakeAuthorRepository 不受影响），本替身
 * 显式全量实现供编辑页用例断言。
 */
private class FakeEditAuthorRepository : media.qimeng.app.core.data.repository.AuthorRepository {
    override suspend fun authors(): List<media.qimeng.app.core.model.AuthorSummary> = emptyList()
    override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit

    var vocabularyCalled = false
    val sourcesByAuthor = mutableMapOf<String, List<String>>()
    val sourceReplaceCalls = mutableListOf<Pair<String, List<String>>>()
    val assetAuthorsCalls = mutableListOf<Pair<String, List<String>>>()
    var replaceError: Exception? = null
    /** 置非空则 replaceAssetAuthors 挂起直至放行（保存中状态断言） */
    var replaceAssetAuthorsGate: CompletableDeferred<Unit>? = null

    override suspend fun sourceVocabulary(): List<String> {
        vocabularyCalled = true
        return listOf("site-a", "pixiv", "site-f")
    }

    override suspend fun authorSourcesById(authorId: String): List<String> =
        sourcesByAuthor[authorId].orEmpty()

    override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
        replaceError?.let { throw it }
        sourceReplaceCalls += authorId to sources
    }

    override suspend fun replaceAssetAuthors(assetId: String, authorIds: List<String>) {
        replaceAssetAuthorsGate?.await()
        replaceError?.let { throw it }
        assetAuthorsCalls += assetId to authorIds
    }
}

/** 编辑页详情仓测试替身：只实现 assetDetail（其余接口方法无操作）。 */
private class FakeEditDetailRepository : DetailRepository {
    var detailResult: AssetDetail? = null
    var detailError: Exception? = null

    override suspend fun assetDetail(assetId: String): AssetDetail {
        detailError?.let { throw it }
        return detailResult ?: throw IllegalStateException("未配置详情")
    }

    override suspend fun toggleLike(assetId: String): LikeToggleResult =
        LikeToggleResult(likedToday = false, likeCount = 0)

    override suspend fun setFavorite(assetId: String, favorite: Boolean) = Unit
    override suspend fun allTags(): List<TagChip> = emptyList()
    override suspend fun createTag(name: String): TagChip = TagChip(id = "t", name = name)
    override suspend fun replaceAssetTags(assetId: String, tagIds: List<String>) = Unit
    override suspend fun unbindTag(assetId: String, tagName: String) = Unit
    override suspend fun reportProgress(assetId: String, positionSeconds: Double) = Unit
    override suspend fun reportViewEvent(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        sessionId: String,
        dwellSeconds: Long?,
    ) = Unit

    override suspend fun timelineTags(assetId: String): List<TimelineTag> = emptyList()
    override suspend fun addTimelineTag(assetId: String, timeMillis: Long, name: String): TimelineTag =
        TimelineTag(id = "t", timeMillis = timeMillis, name = name, color = null)

    override suspend fun deleteTimelineTag(assetId: String, tagId: String) = Unit
    val moveCalls = mutableListOf<Triple<String, String, String?>>()
    var moveError: Exception? = null

    override suspend fun moveAsset(assetId: String, targetDir: String, newName: String?) {
        moveError?.let { throw it }
        moveCalls += Triple(assetId, targetDir, newName)
    }

    override suspend fun deleteAsset(assetId: String) = Unit
}
