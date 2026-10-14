package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.AssetAuthorsRejectedException
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.app.core.testing.FakeStagingRepository
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 资产编辑页 ViewModel 锁定（2026-09-25 上传挂靠退役批）：装配回显（作者全集 + 逐作者
 * 来源）/ 添加作者（联想命中=关联既有；未命中=进待新建名单，2026-10-11 按名新建修订）/
 * 移除与记脏 / 保存调用仓（全集 PUT 含 newAuthorNames 透传 + 脏来源逐 PUT）/
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
            stagingRepository = FakeStagingRepository(),
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
        assertFalse(state.hasUnsavedChanges)
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
        assertFalse(state.hasUnsavedChanges)

        viewModel.onFileNameChange("新名字")
        val state2 = viewModel.uiState.value
        assertEquals("新名字", state2.currentBaseName)
        assertEquals("新名字.mp4", state2.effectiveFileName)
        assertTrue(state2.fileNameChanged)
        assertTrue(state2.canSave)
        assertTrue(state2.hasUnsavedChanges)
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
    fun `联想精确命中回车直接关联既有作者`() {
        val suggestRepo = FakeUploadRepository().apply {
            suggestResult = { q ->
                if (q.equals("作者c", ignoreCase = true)) listOf(AuthorSuggestion("a-3", "作者C", 1)) else emptyList()
            }
        }
        val (viewModel, _, _) = newViewModel(suggestRepo = suggestRepo)
        viewModel.onAddAuthorQueryChange("作者c") // 大小写不同仍命中（忽略大小写精确匹配）
        driveIdle()
        viewModel.commitAddAuthor()
        val state = viewModel.uiState.value
        // 命中通道行为不变：直接进全集，不走待新建
        assertEquals(listOf("a-1", "a-2", "a-3"), state.authors.map { it.id })
        assertTrue(state.pendingNewAuthorNames.isEmpty())
        assertTrue(state.canSave)
        assertEquals("", state.authorQuery)
    }

    @Test
    fun `联想未命中回车进入待新建不报错并解锁保存`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.onAddAuthorQueryChange("全新作者")
        driveIdle() // 防抖后联想返回空（默认 suggestResult 空）
        viewModel.commitAddAuthor()
        val state = viewModel.uiState.value
        assertEquals(listOf("全新作者"), state.pendingNewAuthorNames)
        assertNull(state.noticeMessage) // 旧「仅能关联已有作者」提示退役
        assertEquals("", state.authorQuery) // 入列后输入态复位
        assertTrue(state.pendingNewAuthorNames.isNotEmpty() && state.canSave)
        assertEquals(2, state.authors.size) // 待新建不进「已关联作者」全集
    }

    @Test
    fun `待新建忽略大小写去重且与已关联显示名去重`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.onAddAuthorQueryChange("NewName")
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals(listOf("NewName"), viewModel.uiState.value.pendingNewAuthorNames)

        // 名单内部去重：大小写变体不重复入列（静默 no-op，不报错）
        viewModel.onAddAuthorQueryChange("newname")
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals(listOf("NewName"), viewModel.uiState.value.pendingNewAuthorNames)
        assertNull(viewModel.uiState.value.noticeMessage)

        // 与已关联作者显示名同名：给既有「已关联」提示，不入待新建
        viewModel.onAddAuthorQueryChange("作者A")
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals(listOf("NewName"), viewModel.uiState.value.pendingNewAuthorNames)
        assertEquals("该作者已关联", viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `待新建胶囊点按移除`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.onAddAuthorQueryChange("甲作者")
        driveIdle()
        viewModel.commitAddAuthor()
        viewModel.onAddAuthorQueryChange("乙作者")
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals(listOf("甲作者", "乙作者"), viewModel.uiState.value.pendingNewAuthorNames)

        viewModel.removePendingNewAuthor("甲作者")
        assertEquals(listOf("乙作者"), viewModel.uiState.value.pendingNewAuthorNames)

        viewModel.removePendingNewAuthor("乙作者")
        // 名单清空且无其它改动：保存门控收回到不可保存
        assertTrue(viewModel.uiState.value.pendingNewAuthorNames.isEmpty())
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `作者名超长提示且不入列恰200仍可入列`() {
        val (viewModel, _, _) = newViewModel()
        val overLong = "长".repeat(201)
        viewModel.onAddAuthorQueryChange(overLong)
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals("作者名过长（最多 200 字）", viewModel.uiState.value.noticeMessage)
        assertTrue(viewModel.uiState.value.pendingNewAuthorNames.isEmpty())
        assertEquals(overLong, viewModel.uiState.value.authorQuery) // 输入保留供修改

        // 边界恰 200（协议 maxLength 上限）可入列
        val atLimit = "长".repeat(200)
        viewModel.onAddAuthorQueryChange(atLimit)
        driveIdle()
        viewModel.commitAddAuthor()
        assertEquals(listOf(atLimit), viewModel.uiState.value.pendingNewAuthorNames)
    }

    @Test
    fun `待新建最多20个第21个被拦并提示`() {
        val (viewModel, _, _) = newViewModel()
        repeat(20) { i ->
            viewModel.onAddAuthorQueryChange("新作者$i")
            driveIdle()
            viewModel.commitAddAuthor()
        }
        // 恰 20（协议 maxItems 上限）全部入列且解锁保存
        assertEquals(20, viewModel.uiState.value.pendingNewAuthorNames.size)
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.onAddAuthorQueryChange("新作者20")
        driveIdle()
        viewModel.commitAddAuthor()
        // 第 21 个不入列并给明确提示（本地拦截，不把必 400 的载荷发出去）
        assertEquals(20, viewModel.uiState.value.pendingNewAuthorNames.size)
        assertEquals(
            "待新建作者最多 20 个，请先保存本页再继续添加",
            viewModel.uiState.value.noticeMessage,
        )
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
    fun `读取作者来源失败时禁止编辑并可重试回显完整旧值`() {
        val authorRepo = FakeEditAuthorRepository().apply {
            sourcesByAuthor["a-1"] = listOf("site-a")
            sourceErrorsById["a-1"] = IllegalStateException("source endpoint unavailable")
        }
        val (viewModel, _, repo) = newViewModel(authorRepo = authorRepo)
        val failedState = viewModel.uiState.value
        assertTrue("a-1" in failedState.sourceLoadFailedAuthorIds)
        assertFalse("a-1" in failedState.sourcesByAuthor)

        // 失败状态下即使有过期 UI 事件也必须 fail-closed，不得以空集合做整体替换。
        viewModel.toggleSource("a-1", "site-f")
        viewModel.addCustomSource("a-1", "new-site")
        assertFalse("a-1" in viewModel.uiState.value.dirtySourceAuthorIds)

        repo.sourceErrorsById.remove("a-1")
        viewModel.retryAuthorSources("a-1")
        driveIdle()

        val recovered = viewModel.uiState.value
        assertFalse("a-1" in recovered.sourceLoadFailedAuthorIds)
        assertFalse("a-1" in recovered.sourceLoadingAuthorIds)
        assertEquals(listOf("site-a"), recovered.sourcesByAuthor["a-1"])
        viewModel.toggleSource("a-1", "site-f")
        assertEquals(listOf("site-a", "site-f"), viewModel.uiState.value.sourcesByAuthor["a-1"])
    }

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
    fun `保存透传待新建名并成功后清空`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.onAddAuthorQueryChange("全新作者")
        driveIdle()
        viewModel.commitAddAuthor()
        assertTrue(viewModel.uiState.value.canSave) // 仅待新建名也解锁保存

        viewModel.save()
        driveIdle()

        val call = authorRepo.assetAuthorsCalls.single()
        assertEquals("asset-1", call.first)
        assertEquals(listOf("a-1", "a-2"), call.second) // 既有作者 id 全集
        assertEquals(listOf("全新作者"), call.third) // 待新建名随请求交服务端复用/新建
        assertTrue(viewModel.uiState.value.saved)
        // 成功后清空待新建：名字已实体化，回读即为正常作者
        assertTrue(viewModel.uiState.value.pendingNewAuthorNames.isEmpty())
    }

    @Test
    fun `保存失败不清空待新建可重试`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.onAddAuthorQueryChange("全新作者")
        driveIdle()
        viewModel.commitAddAuthor()
        authorRepo.replaceError = IllegalStateException("500")

        viewModel.save()
        driveIdle()

        assertEquals("保存失败：请重试", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saved)
        assertEquals(listOf("全新作者"), viewModel.uiState.value.pendingNewAuthorNames)
        assertTrue(viewModel.uiState.value.canSave) // 可重按重试
    }

    @Test
    fun `保存被服务端400拒绝时透出原因且待新建保留`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.onAddAuthorQueryChange("[3D]")
        driveIdle()
        viewModel.commitAddAuthor()
        // 服务端 400 原因（core:data 解析 Error{code,message} 后的领域化文案）
        authorRepo.replaceError = AssetAuthorsRejectedException(
            serverMessage = "INVALID_PARAM newAuthorNames 第 1 项 \"[3D]\" 非法：规范化后无有效别名",
            statusCode = 400,
        )

        viewModel.save()
        driveIdle()

        val message = viewModel.uiState.value.errorMessage
        // 原因随文案透出（不再落笼统「保存失败：请重试」），用户可自助改名后重按
        assertEquals(
            "保存失败：INVALID_PARAM newAuthorNames 第 1 项 \"[3D]\" 非法：规范化后无有效别名",
            message,
        )
        assertFalse(viewModel.uiState.value.saved)
        assertEquals(listOf("[3D]"), viewModel.uiState.value.pendingNewAuthorNames)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `保存网络失败仍落通用文案`() {
        val (viewModel, _, authorRepo) = newViewModel()
        viewModel.onAddAuthorQueryChange("全新作者")
        driveIdle()
        viewModel.commitAddAuthor()
        authorRepo.replaceError = java.io.IOException("connection reset")

        viewModel.save()
        driveIdle()

        // 网络类失败（非 4xx 语义化拒绝）保持通用文案与可重试语义
        assertEquals("保存失败：请重试", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saved)
        assertEquals(listOf("全新作者"), viewModel.uiState.value.pendingNewAuthorNames)
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
    val sourceErrorsById = mutableMapOf<String, Exception>()
    val sourceReplaceCalls = mutableListOf<Pair<String, List<String>>>()

    /** 全集 PUT 记账（assetId、作者 id 全集、待新建名列表——协议 2026-10-11 新增字段透传断言） */
    val assetAuthorsCalls = mutableListOf<Triple<String, List<String>, List<String>>>()
    var replaceError: Exception? = null
    /** 置非空则 replaceAssetAuthors 挂起直至放行（保存中状态断言） */
    var replaceAssetAuthorsGate: CompletableDeferred<Unit>? = null

    override suspend fun sourceVocabulary(): List<String> {
        vocabularyCalled = true
        return listOf("site-a", "pixiv", "site-f")
    }

    override suspend fun authorSourcesById(authorId: String): List<String> {
        sourceErrorsById[authorId]?.let { throw it }
        return sourcesByAuthor[authorId].orEmpty()
    }

    override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
        replaceError?.let { throw it }
        sourceReplaceCalls += authorId to sources
    }

    override suspend fun replaceAssetAuthors(
        assetId: String,
        authorIds: List<String>,
        newAuthorNames: List<String>,
    ) {
        replaceAssetAuthorsGate?.await()
        replaceError?.let { throw it }
        assetAuthorsCalls += Triple(assetId, authorIds, newAuthorNames)
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
        fileName: String,
        relPath: String,
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
