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
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 上传流表单状态机锁定（M4-5）：超限本地拦截 / 入队参数与顺序 / 新建目录校验 / 分享接收 / 队列透传。
 * 流程重排（2026-09-25 拍板）：先选文件进暂存区、库在暂存区手选——init 不再自动选库/加载目录树，
 * enqueue 保留目标库必填兜底（未选库拦截 + 文案），门禁状态由 canEnqueue/enqueueGateHint 派生。
 * 挂靠批：批次默认（作者联想 + 来源 + 应用到全部；新进项继承）/ 逐项编辑（作品名/作者/来源，
 * 清作者连带清来源）/ 入队载荷携带编辑值。队列串行执行本身由 WorkManager unique 链官方
 * 语义保证（UploadWorkSpec 注释），实机串行时间线走模拟器文本证据。
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
        },
        authorRepository: FakeAuthorRepository = FakeAuthorRepository(),
    ): Triple<UploadViewModel, FakeUploadRepository, FakeAuthorRepository> {
        val viewModel = UploadViewModel(repository, authorRepository)
        driveIdle()
        return Triple(viewModel, repository, authorRepository)
    }

    /** 流程重排（2026-09-25）：库改在暂存区手选，入队类用例先选库再 enqueue */
    private fun selectDefaultLibrary(viewModel: UploadViewModel) {
        viewModel.selectLibrary(libraryA)
        driveIdle()
    }

    @Test
    fun `init加载库列表但不自动选库`() {
        val (viewModel, _, _) = newViewModel()
        val state = viewModel.uiState.value
        assertEquals(listOf(libraryA, libraryB), state.libraries)
        assertNull(state.selectedLibrary)
        assertNull(state.dirTree)
        assertNull(state.errorMessage)
    }

    @Test
    fun `选库后才加载目录树`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.selectLibrary(libraryA)
        driveIdle()
        assertEquals(libraryA, viewModel.uiState.value.selectedLibrary)
        assertNotNull(viewModel.uiState.value.dirTree)
    }

    // ---- 流程重排（2026-09-25）：未选库直接选文件 + 开始上传门禁 ----

    @Test
    fun `acceptUris去重进待上传列表`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1"))
        driveIdle()
        viewModel.acceptUris(listOf("content://x/1", "content://x/2"))
        driveIdle()
        assertEquals(2, viewModel.uiState.value.pendingItems.size)
    }

    @Test
    fun `未选库enqueue被拦截并提示选库后正常入队`() {
        val (viewModel, repository, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        viewModel.enqueue()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals("先选择目标库", viewModel.uiState.value.blockMessage)
        // 同一批待传项，选库后正常入队
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        assertEquals(1, repository.enqueueCalls.size)
        assertEquals("lib-a", repository.enqueueCalls.single().libraryId)
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }

    @Test
    fun `开始上传门禁派生未选库禁用并提示`() {
        val (viewModel, _, _) = newViewModel()
        // 无待传项：门禁关闭且不提示
        assertFalse(viewModel.uiState.value.canEnqueue)
        assertNull(viewModel.uiState.value.enqueueGateHint)
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        // 有待传项未选库：禁用 + 提示
        assertFalse(viewModel.uiState.value.canEnqueue)
        assertEquals("先选择目标库", viewModel.uiState.value.enqueueGateHint)
        selectDefaultLibrary(viewModel)
        // 选库后门禁放行、提示消失
        assertTrue(viewModel.uiState.value.canEnqueue)
        assertNull(viewModel.uiState.value.enqueueGateHint)
    }

    @Test
    fun `enqueue正常入队并清空待上传`() {
        val (viewModel, repository, _) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        val state = viewModel.uiState.value
        assertTrue(state.pendingItems.isEmpty())
        assertEquals(1, repository.enqueueCalls.size)
        assertEquals("lib-a", repository.enqueueCalls.single().libraryId)
        assertEquals("", repository.enqueueCalls.single().dir)
        assertNull(state.blockMessage)
    }

    @Test
    fun `enqueue一次批量入队且顺序保持用户添加顺序`() {
        val (viewModel, repository, _) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1", "content://x/2"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        val names = repository.enqueueCalls.single().items.map { it.displayName }
        assertEquals(
            listOf("file-${"content://x/1".hashCode()}", "file-${"content://x/2".hashCode()}"),
            names,
        )
    }

    @Test
    fun `全部超限时拦截文案生成且不入队`() {
        val (viewModel, repository, _) = newViewModel(
            FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                limitsResult = UploadLimits(maxBytesMb = 64, autoAccept = true)
                describedItem = { uri ->
                    UploadItem(uri = uri, displayName = "big.jpg", sizeBytes = 65L * 1024 * 1024)
                }
            },
        )
        viewModel.acceptUris(listOf("content://x/big"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        val state = viewModel.uiState.value
        assertTrue(repository.enqueueCalls.isEmpty())
        assertTrue(state.blockMessage?.contains("超过服务端上限 64 MB") == true)
        assertTrue(state.blockMessage?.contains("big.jpg") == true)
    }

    @Test
    fun `部分超限时被拦项不出网其余照常入队`() {
        val (viewModel, repository, _) = newViewModel(
            FakeUploadRepository().apply {
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
        viewModel.acceptUris(listOf("content://x/big", "content://x/small"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        val state = viewModel.uiState.value
        val enqueued = repository.enqueueCalls.single().items
        assertEquals(listOf("small.jpg"), enqueued.map { it.displayName })
        assertNotNull(state.blockMessage)
        assertTrue(state.pendingItems.isEmpty())
    }

    @Test
    fun `未超限边界值恰好等于上限不拦`() {
        val (viewModel, repository, _) = newViewModel(
            FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                limitsResult = UploadLimits(maxBytesMb = 64, autoAccept = true)
                describedItem = { uri ->
                    UploadItem(uri = uri, displayName = "edge.jpg", sizeBytes = 64L * 1024 * 1024)
                }
            },
        )
        viewModel.acceptUris(listOf("content://x/edge"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        assertTrue(repository.enqueueCalls.isNotEmpty())
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `新建子目录非法名不发起请求`() {
        val (viewModel, repository, _) = newViewModel()
        viewModel.createSubDir("../escape")
        driveIdle()
        assertTrue(repository.createDirCalls.isEmpty())
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `新建子目录成功后选中新路径`() {
        val (viewModel, repository, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.selectDir("photos")
        viewModel.createSubDir("2026")
        driveIdle()
        assertEquals(listOf("lib-a" to "photos/2026"), repository.createDirCalls)
        assertEquals("photos/2026", viewModel.uiState.value.selectedDirPath)
    }

    @Test
    fun `队列状态透传到UI状态`() {
        val (viewModel, repository, _) = newViewModel()
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

    @Test
    fun `切库重载目录树且目标目录回库根`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.selectDir("photos")
        viewModel.selectLibrary(libraryB)
        driveIdle()
        assertEquals(libraryB, viewModel.uiState.value.selectedLibrary)
        assertEquals("", viewModel.uiState.value.selectedDirPath)
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
        val (viewModel, repository, _) = newViewModel()
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

    // ---- 批C 任务Q C-2：单任务取消 ----

    @Test
    fun `取消透传localId到仓库且无需二次确认`() {
        val (viewModel, repository, _) = newViewModel()
        val entry = queueEntry(UploadStatus.UPLOADING).copy(localId = "local-42")
        viewModel.cancel(entry)
        driveIdle()
        assertEquals(listOf("local-42"), repository.cancelCalls)
    }

    @Test
    fun `排队中任务同样可取消`() {
        val (viewModel, repository, _) = newViewModel()
        val entry = queueEntry(UploadStatus.QUEUED).copy(localId = "local-7")
        viewModel.cancel(entry)
        driveIdle()
        assertEquals(listOf("local-7"), repository.cancelCalls)
    }

    @Test
    fun `取消不计入聚合行失败数`() {
        val (viewModel, repository, _) = newViewModel()
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
    fun `取消态不算活跃任务`() {
        val (viewModel, repository, _) = newViewModel()
        repository.pushQueue(listOf(queueEntry(UploadStatus.CANCELLED)))
        driveIdle()
        assertFalse(viewModel.uiState.value.hasActiveWork)
    }

    // ---- 2026-09-25：内置相册式选择器选中项合并（acceptPickedItems） ----

    private fun picked(uri: String, name: String, size: Long) =
        LocalMediaItem(uri = uri, displayName = name, sizeBytes = size, isVideo = false)

    @Test
    fun `acceptPickedItems元数据直用不查describe并按uri去重`() {
        val (viewModel, repository, _) = newViewModel()
        viewModel.acceptPickedItems(
            listOf(picked("content://media/img/1", "IMG_1.jpg", 2048L)),
        )
        driveIdle()
        viewModel.acceptPickedItems(
            listOf(
                picked("content://media/img/1", "IMG_1.jpg", 2048L),
                picked("content://media/img/2", "IMG_2.jpg", 4096L),
            ),
        )
        driveIdle()
        val pending = viewModel.uiState.value.pendingItems
        assertEquals(2, pending.size)
        // MediaStore 已给出元数据：不再触发 describe 重查（SAF/分享路径才走 describe）
        assertEquals(0, repository.describeCalls)
        assertEquals("IMG_1.jpg", pending[0].displayName)
        assertEquals(2048L, pending[0].sizeBytes)
        assertEquals("", pending[0].relativeDir)
    }

    @Test
    fun `acceptPickedItems空列表忽略`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(emptyList())
        driveIdle()
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }

    // ---- 挂靠批：批次默认 + 逐项编辑 ----

    private val authorA = AuthorSuggestion(id = "author-a", displayName = "作者A", fileCount = 3)
    private val authorB = AuthorSuggestion(id = "author-b", displayName = "作者B", fileCount = 5)

    /** 装配：批次作者已选（可带来源）→ 加入一个待传项；库为入队前置，一并选中 */
    private fun seededWithBatch(
        sources: List<String> = emptyList(),
    ): Triple<UploadViewModel, FakeUploadRepository, FakeAuthorRepository> {
        val (viewModel, repository, authorRepository) = newViewModel(
            authorRepository = FakeAuthorRepository().apply { vocabularyResult = listOf("kemono") },
        )
        selectDefaultLibrary(viewModel)
        viewModel.pickBatchAuthor(authorA)
        sources.forEach { viewModel.toggleBatchSource(it) }
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        return Triple(viewModel, repository, authorRepository)
    }

    @Test
    fun `新进项继承批次默认作者与来源`() {
        val (viewModel, _, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        assertEquals("author-a", item.attachAuthorId)
        assertEquals("作者A", item.attachAuthorName)
        assertEquals(listOf("kemono"), item.attachSources)
    }

    @Test
    fun `未设批次默认时新进项不带挂靠`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        assertNull(item.attachAuthorId)
        assertNull(item.attachSources)
    }

    @Test
    fun `应用到全部覆盖既有待传项`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(
            listOf(
                picked("content://media/img/1", "IMG_1.jpg", 100L),
                picked("content://media/img/2", "IMG_2.jpg", 200L),
            ),
        )
        driveIdle()
        viewModel.pickBatchAuthor(authorB)
        viewModel.toggleBatchSource("kemono")
        viewModel.applyBatchToAll()
        driveIdle()
        viewModel.uiState.value.pendingItems.forEach { item ->
            assertEquals("author-b", item.attachAuthorId)
            assertEquals(listOf("kemono"), item.attachSources)
        }
    }

    @Test
    fun `批次未选来源时应用到全部不清既有逐项来源`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        // 来源 toggle 以作者为前提：先挂作者再设逐项来源
        viewModel.pickItemAuthor(viewModel.uiState.value.pendingItems.single(), authorA)
        viewModel.toggleItemSource(viewModel.uiState.value.pendingItems.single(), "旧来源")
        driveIdle()
        viewModel.pickBatchAuthor(authorA)
        viewModel.applyBatchToAll()
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        assertEquals("author-a", item.attachAuthorId)
        // 「应用」只写批次已配置的维度：批次未选来源时保留逐项来源
        assertEquals(listOf("旧来源"), item.attachSources)
    }

    @Test
    fun `逐项覆盖作者按uri定点更新`() {
        val (viewModel, _, _) = seededWithBatch()
        viewModel.toggleItemExpanded(viewModel.uiState.value.pendingItems.first())
        viewModel.pickItemAuthor(viewModel.uiState.value.pendingItems.first(), authorB)
        driveIdle()
        assertEquals("author-b", viewModel.uiState.value.pendingItems.single().attachAuthorId)
        assertEquals("作者B", viewModel.uiState.value.pendingItems.single().attachAuthorName)
    }

    @Test
    fun `清空某项作者连带清来源为不带挂靠`() {
        val (viewModel, _, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.clearItemAuthor(item)
        driveIdle()
        val cleared = viewModel.uiState.value.pendingItems.single()
        assertNull(cleared.attachAuthorId)
        assertNull(cleared.attachAuthorName)
        assertNull(cleared.attachSources)
    }

    @Test
    fun `作品名编辑生效并入队载荷携带编辑值与挂靠`() {
        val (viewModel, repository, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.setItemUploadName(item, "我的作品名.jpg")
        driveIdle()
        assertEquals("我的作品名.jpg", viewModel.uiState.value.pendingItems.single().effectiveUploadName)
        viewModel.enqueue()
        driveIdle()
        val enqueued = repository.enqueueCalls.single().items.single()
        assertEquals("我的作品名.jpg", enqueued.effectiveUploadName)
        assertEquals("author-a", enqueued.attachAuthorId)
        assertEquals(listOf("kemono"), enqueued.attachSources)
    }

    @Test
    fun `作品名清空回退展示名`() {
        val (viewModel, repository, _) = seededWithBatch()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.setItemUploadName(item, "   ")
        driveIdle()
        assertEquals("IMG_1.jpg", viewModel.uiState.value.pendingItems.single().effectiveUploadName)
        viewModel.enqueue()
        driveIdle()
        assertEquals("IMG_1.jpg", repository.enqueueCalls.single().items.single().effectiveUploadName)
    }

    @Test
    fun `批次作者联想防抖回填且回车精确命中`() {
        val (viewModel, repository, _) = newViewModel()
        repository.suggestResult = { q -> if (q == "作者A") listOf(authorA) else emptyList() }
        viewModel.onBatchAuthorQueryChange("作者A")
        driveIdle()
        assertEquals(listOf(authorA), viewModel.uiState.value.batchAuthorSuggestions)
        viewModel.commitBatchAuthor()
        driveIdle()
        assertEquals(authorA, viewModel.uiState.value.batchAuthor)
        assertEquals("", viewModel.uiState.value.batchAuthorQuery)
    }

    @Test
    fun `批次作者联想未命中提示且不选中`() {
        val (viewModel, repository, _) = newViewModel()
        repository.suggestResult = { emptyList() }
        viewModel.onBatchAuthorQueryChange("不存在")
        driveIdle()
        viewModel.commitBatchAuthor()
        driveIdle()
        assertNull(viewModel.uiState.value.batchAuthor)
        assertNotNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `清空批次作者连带清批次来源`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.pickBatchAuthor(authorA)
        viewModel.toggleBatchSource("kemono")
        viewModel.clearBatchAuthor()
        assertNull(viewModel.uiState.value.batchAuthor)
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
    }

    @Test
    fun `批次来源toggle需先选作者`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
        viewModel.pickBatchAuthor(authorA)
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        assertEquals(listOf("kemono"), viewModel.uiState.value.batchSources)
    }

    @Test
    fun `逐项来源toggle需该项已有作者`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.toggleItemSource(item, "kemono")
        driveIdle()
        assertNull(viewModel.uiState.value.pendingItems.single().attachSources)
        // 挂上作者后 toggle 生效
        viewModel.pickItemAuthor(viewModel.uiState.value.pendingItems.single(), authorA)
        viewModel.toggleItemSource(viewModel.uiState.value.pendingItems.single(), "kemono")
        driveIdle()
        assertEquals(listOf("kemono"), viewModel.uiState.value.pendingItems.single().attachSources)
    }

    @Test
    fun `入队后清空待传并复位编辑态`() {
        val (viewModel, repository, _) = seededWithBatch()
        viewModel.toggleItemExpanded(viewModel.uiState.value.pendingItems.first())
        viewModel.enqueue()
        driveIdle()
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
        assertNull(viewModel.uiState.value.editingUri)
        assertEquals(1, repository.enqueueCalls.size)
    }

    @Test
    fun `队列聚合行挂靠失败单独计数`() {
        val (viewModel, repository, _) = newViewModel()
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
        val (viewModel, repository, _) = newViewModel()
        repository.pushQueue(listOf(queueEntry(UploadStatus.ATTACH_FAILED)))
        driveIdle()
        assertFalse(viewModel.uiState.value.hasActiveWork)
    }
}
