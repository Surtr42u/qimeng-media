package media.qimeng.app.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.StagingBatchConfig
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.FakeStagingRepository
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 上传流表单状态机锁定（M4-5；2026-09-29 直传化）：
 * - 直传管道（暂存区退役，选完即传）：describe 解元数据 → 未选库门禁 → 逐项判超限
 *   （超限项本地拦截不出网给 blockText 文案，未超限项照常入队）→ enqueue 继承批次默认
 *   快照（批次库 + 已选目录 + 作者/来源 + 库名）；
 * - 批次默认持久化：库/作者/来源走 StagingRepository.batchConfig（写路径断言
 *   batchConfigCalls），跨进程恢复语义由持久层单测锁定（StagingJson/DataStoreStagingRepository）；
 * - 批次作者联想：防抖 + 回车精确命中 + 未命中提示；
 * - 队列：状态透传、聚合行计数（取消不计失败、挂靠失败分列）、取消透传。
 */
class UploadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val libraryA = LibraryChoice(id = "lib-a", name = "测试库A")
    private val libraryB = LibraryChoice(id = "lib-b", name = "测试库B")

    private fun newViewModel(
        repository: FakeUploadRepository = FakeUploadRepository().apply {
            librariesResult = listOf(libraryA, libraryB)
            // 直传管道元数据单源（describe 是唯一入队前置）：默认按 uri 尾段给 IMG_N.jpg
            // 形态展示名——超限拦截文案断言（big.jpg 等）的前提
            describedItem = { uri ->
                UploadItem(uri = uri, displayName = "IMG_${uri.substringAfterLast('/')}.jpg", sizeBytes = 100L)
            }
        },
        authorRepository: FakeAuthorRepository = FakeAuthorRepository(),
        staging: FakeStagingRepository = FakeStagingRepository(),
    ): Quad<UploadViewModel, FakeUploadRepository, FakeAuthorRepository, FakeStagingRepository> {
        val viewModel = UploadViewModel(repository, authorRepository, staging)
        driveIdle()
        return Quad(viewModel, repository, authorRepository, staging)
    }

    /** 装配记录四元组（库/作者/批次默认仓三依赖 + VM） */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** 批次库是直传门禁前置（持久化批次配置），入队类用例先选库再 submitUris */
    private fun selectDefaultLibrary(viewModel: UploadViewModel) {
        viewModel.selectLibrary(libraryA)
        driveIdle()
    }

    // ---- 首屏加载 ----

    @Test
    fun `init加载库列表但不自动选库`() {
        val (viewModel, _, _) = newViewModel()
        val state = viewModel.uiState.value
        assertEquals(listOf(libraryA, libraryB), state.libraries)
        assertNull(state.selectedLibrary)
        assertNull(state.dirTree)
        assertNull(state.errorMessage)
    }

    // ---- 来源词表建议过滤（2026-09-28：仅单独词，组合条目不出现在快捷 chip） ----

    @Test
    fun `init拉来源词表拆词提取只出单独词`() {
        val authorRepository = FakeAuthorRepository().apply {
            // hanime1 只存在于组合里：拆词提取后同样必须出现在建议中（用户实测反馈漏词）
            vocabularyResult = listOf("kemono", "kemono  小红车", "hanime1  小红车", "")
        }
        val (viewModel, _, _, _) = newViewModel(authorRepository = authorRepository)
        // 组合按空白拆词去重、空串过滤；作者联想为百度式（空输入无建议列表）
        assertEquals(listOf("kemono", "小红车", "hanime1"), viewModel.uiState.value.sourceOptions)
        assertTrue(viewModel.uiState.value.batchAuthorSuggestions.isEmpty())
    }

    @Test
    fun `选库写入持久批次配置并加载目录树`() {
        val (viewModel, _, _, staging) = newViewModel()
        selectDefaultLibrary(viewModel)
        assertEquals(libraryA, viewModel.uiState.value.selectedLibrary)
        assertEquals("lib-a", staging.batchConfigCalls.single().libraryId)
        assertNotNull(viewModel.uiState.value.dirTree)
    }

    @Test
    fun `持久化批次库在库列表就绪后回放目录树`() {
        val staging = FakeStagingRepository().apply { seedBatch(StagingBatchConfig(libraryId = "lib-b")) }
        val (viewModel, _, _) = newViewModel(staging = staging)
        driveIdle()
        assertEquals(libraryB, viewModel.uiState.value.selectedLibrary)
        assertNotNull(viewModel.uiState.value.dirTree)
    }

    @Test
    fun `切库重载目录树且目标目录回库根`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.selectDir("photos")
        viewModel.selectLibrary(libraryB)
        driveIdle()
        assertEquals(libraryB, viewModel.uiState.value.selectedLibrary)
        assertEquals("", viewModel.uiState.value.selectedDirPath)
    }

    // ---- 直传管道（2026-09-29：选完即传，无暂存/无开始上传按钮） ----

    @Test
    fun `submitUris选完即传入队并继承批次默认`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.selectDir("photos")
        viewModel.pickBatchAuthor(AuthorSuggestion(id = "author-a", displayName = "作者A", fileCount = 3))
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()

        // 单次入队：批次库 + 已选目录；载荷继承批次默认（作者/来源）与库名（归档分派用）
        val call = repository.enqueueCalls.single()
        assertEquals("lib-a", call.libraryId)
        assertEquals("photos", call.dir)
        assertEquals(listOf("IMG_1.jpg"), call.items.map { it.displayName })
        assertEquals("author-a", call.items.single().attachAuthorId)
        assertEquals("作者A", call.items.single().attachAuthorName)
        assertEquals(listOf("kemono"), call.items.single().attachSources)
        assertEquals("测试库A", call.items.single().libraryName)
        // 直传化：无拦截横幅残留
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `submitUris经describe解元数据入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://media/img/1", "content://media/img/2"))
        driveIdle()
        val enqueued = repository.enqueueCalls.single().items
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), enqueued.map { it.displayName })
        // describe 是直传管道唯一元数据来源：逐 uri 调用过
        assertEquals(2, repository.describeCalls)
    }

    @Test
    fun `submitUris空列表忽略`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(emptyList())
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals(0, repository.describeCalls)
    }

    @Test
    fun `未选库submitUris被拦截并提示选库后正常入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()
        // 未选库：不 describe 不入队，横幅提示
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals(0, repository.describeCalls)
        assertEquals("先选择目标库", viewModel.uiState.value.blockMessage)
        // 选库后同一批文件正常入队
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()
        assertEquals(1, repository.enqueueCalls.size)
        assertEquals("lib-a", repository.enqueueCalls.single().libraryId)
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `未设批次默认时入队项不带挂靠`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()
        val item = repository.enqueueCalls.single().items.single()
        assertNull(item.attachAuthorId)
        assertNull(item.attachSources)
        // 库名仍随载荷入队（worker 归档分派用）
        assertEquals("测试库A", item.libraryName)
    }

    @Test
    fun `全部超限时拦截文案生成且不出网`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                limitsResult = UploadLimits(maxBytesMb = 64, autoAccept = true)
                describedItem = { uri ->
                    UploadItem(uri = uri, displayName = "big.jpg", sizeBytes = 65L * 1024 * 1024)
                }
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://x/big"))
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        val state = viewModel.uiState.value
        assertTrue(state.blockMessage?.contains("超过服务端上限 64 MB") == true)
        assertTrue(state.blockMessage?.contains("big.jpg") == true)
    }

    @Test
    fun `部分超限时被拦项拦下其余照常入队`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                limitsResult = UploadLimits(maxBytesMb = 64, autoAccept = true)
                describedItem = { uri ->
                    if (uri.endsWith("big")) {
                        UploadItem(uri = uri, displayName = "big.jpg", sizeBytes = 65L * 1024 * 1024)
                    } else {
                        UploadItem(uri = uri, displayName = "small.jpg", sizeBytes = 100L)
                    }
                }
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://x/big", "content://x/small"))
        driveIdle()
        // 未超限项照常入队，超限项本地拦截（文案列名）
        val enqueued = repository.enqueueCalls.single().items
        assertEquals(listOf("small.jpg"), enqueued.map { it.displayName })
        assertNotNull(viewModel.uiState.value.blockMessage)
        assertTrue(viewModel.uiState.value.blockMessage?.contains("big.jpg") == true)
    }

    @Test
    fun `未超限边界值恰好等于上限不拦`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                limitsResult = UploadLimits(maxBytesMb = 64, autoAccept = true)
                describedItem = { uri ->
                    UploadItem(uri = uri, displayName = "edge.jpg", sizeBytes = 64L * 1024 * 1024)
                }
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://x/edge"))
        driveIdle()
        assertTrue(repository.enqueueCalls.isNotEmpty())
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `describe失败给错误横幅且不入队`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                describedItem = { throw IllegalStateException("describe boom") }
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()
        assertEquals(DESCRIBE_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
        assertTrue(repository.enqueueCalls.isEmpty())
    }

    @Test
    fun `入队失败给错误横幅`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                enqueueError = IllegalStateException("enqueue boom")
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.submitUris(listOf("content://media/img/1"))
        driveIdle()
        assertEquals(ENQUEUE_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
    }

    // ---- 新建子目录 ----

    @Test
    fun `新建子目录非法名不发起请求`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.createSubDir("../escape")
        driveIdle()
        assertTrue(repository.createDirCalls.isEmpty())
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `新建子目录成功后选中新路径`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.selectDir("photos")
        viewModel.createSubDir("2026")
        driveIdle()
        assertEquals(listOf("lib-a" to "photos/2026"), repository.createDirCalls)
        assertEquals("photos/2026", viewModel.uiState.value.selectedDirPath)
    }

    // ---- 队列（状态透传/聚合行/取消） ----

    @Test
    fun `队列状态透传到UI状态`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.pushQueue(
            listOf(
                UploadQueueEntry(
                    localId = "l1",
                    displayName = "a.jpg",
                    status = UploadStatus.UPLOADING,
                    progressPercent = 42,
                    finalFileName = null,
                    errorMessage = null,
                ),
            ),
        )
        driveIdle()
        val queue = viewModel.uiState.value.queue
        assertEquals(1, queue.size)
        assertEquals(UploadStatus.UPLOADING, queue.single().status)
        assertEquals(42, queue.single().progressPercent)
        assertTrue(viewModel.uiState.value.hasActiveWork)
    }

    private fun queueEntry(status: UploadStatus) = UploadQueueEntry(
        localId = status.name,
        displayName = "f.jpg",
        status = status,
        progressPercent = null,
        finalFileName = null,
        errorMessage = null,
    )

    @Test
    fun `队列聚合行按状态计数`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.pushQueue(
            listOf(
                queueEntry(UploadStatus.SUCCEEDED),
                queueEntry(UploadStatus.SUCCEEDED),
                queueEntry(UploadStatus.FAILED),
                queueEntry(UploadStatus.QUEUED),
            ),
        )
        driveIdle()
        assertEquals("共 4 个 · 成功 2 · 失败 1", viewModel.uiState.value.queueSummary)
    }

    @Test
    fun `空队列为null聚合行`() {
        val (viewModel, _, _) = newViewModel()
        assertNull(viewModel.uiState.value.queueSummary)
    }

    @Test
    fun `取消透传localId到仓库且无需二次确认`() {
        val (viewModel, repository, _, _) = newViewModel()
        val entry = queueEntry(UploadStatus.UPLOADING).copy(localId = "local-42")
        viewModel.cancel(entry)
        driveIdle()
        assertEquals(listOf("local-42"), repository.cancelCalls)
    }

    @Test
    fun `取消不计入聚合行失败数`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.pushQueue(
            listOf(
                queueEntry(UploadStatus.SUCCEEDED),
                queueEntry(UploadStatus.CANCELLED),
                queueEntry(UploadStatus.FAILED),
            ),
        )
        driveIdle()
        assertEquals("共 3 个 · 成功 1 · 失败 1", viewModel.uiState.value.queueSummary)
    }

    @Test
    fun `队列聚合行挂靠失败单独计数`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.pushQueue(
            listOf(
                queueEntry(UploadStatus.SUCCEEDED),
                queueEntry(UploadStatus.ATTACH_FAILED),
                queueEntry(UploadStatus.FAILED),
            ),
        )
        driveIdle()
        assertEquals("共 3 个 · 成功 1 · 失败 1 · 挂靠失败 1", viewModel.uiState.value.queueSummary)
    }

    @Test
    fun `挂靠失败态不算活跃任务`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.pushQueue(listOf(queueEntry(UploadStatus.ATTACH_FAILED)))
        driveIdle()
        assertFalse(viewModel.uiState.value.hasActiveWork)
    }

    // ---- 批次作者联想（口径保持：防抖 + 回车精确命中 + 未命中提示） ----

    private val authorA = AuthorSuggestion(id = "author-a", displayName = "作者A", fileCount = 3)

    @Test
    fun `批次作者联想防抖回填且回车精确命中`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.suggestResult = { q -> if (q == "作者A") listOf(authorA) else emptyList() }
        viewModel.onBatchAuthorQueryChange("作者A")
        driveIdle()
        assertEquals(listOf(authorA), viewModel.uiState.value.batchAuthorSuggestions)
        viewModel.commitBatchAuthor()
        driveIdle()
        assertEquals("author-a", viewModel.uiState.value.batchAuthorId)
        assertEquals("作者A", viewModel.uiState.value.batchAuthorName)
        assertEquals("", viewModel.uiState.value.batchAuthorQuery)
    }

    @Test
    fun `批次作者联想未命中提示且不选中`() {
        val (viewModel, repository, _, _) = newViewModel()
        repository.suggestResult = { emptyList() }
        viewModel.onBatchAuthorQueryChange("不存在")
        driveIdle()
        viewModel.commitBatchAuthor()
        driveIdle()
        assertNull(viewModel.uiState.value.batchAuthorId)
        assertNotNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `清空批次作者连带清批次来源`() {
        val (viewModel, _, _, _) = newViewModel()
        viewModel.pickBatchAuthor(authorA)
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        viewModel.clearBatchAuthor()
        driveIdle()
        assertNull(viewModel.uiState.value.batchAuthorId)
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
    }

    @Test
    fun `批次来源toggle需先选作者`() {
        val (viewModel, _, _, _) = newViewModel()
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
        viewModel.pickBatchAuthor(authorA)
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        assertEquals(listOf("kemono"), viewModel.uiState.value.batchSources)
    }
}
