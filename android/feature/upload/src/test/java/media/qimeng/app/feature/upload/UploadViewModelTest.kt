package media.qimeng.app.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 上传流表单状态机锁定（M4-5）：超限本地拦截 / 入队参数与顺序 / 新建目录校验 / 分享接收 / 队列透传。
 * 协议批 2026-09-25：上传挂靠（作者/来源）随协议退役——挂靠批用例随之删除；新增内置
 * 相册选择器选中项合并（acceptPickedItems）用例；文件夹上传（U10-6c）整体退役，
 * 扫描合并/防抖用例随之删除。
 * 队列串行执行本身由 WorkManager unique 链官方语义保证（UploadWorkSpec 注释），
 * 实机串行时间线走模拟器文本证据（HANDOVER_APP §4.7）。
 */
class UploadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val libraryA = LibraryChoice(id = "lib-a", name = "测试库A")
    private val libraryB = LibraryChoice(id = "lib-b", name = "测试库B")

    private fun newViewModel(repository: FakeUploadRepository = FakeUploadRepository().apply {
        librariesResult = listOf(libraryA, libraryB)
    }): Pair<UploadViewModel, FakeUploadRepository> {
        val viewModel = UploadViewModel(repository)
        driveIdle()
        return viewModel to repository
    }

    @Test
    fun `init加载库列表并默认选第一个库`() {
        val (viewModel, _) = newViewModel()
        val state = viewModel.uiState.value
        assertEquals(listOf(libraryA, libraryB), state.libraries)
        assertEquals(libraryA, state.selectedLibrary)
        assertNotNull(state.dirTree)
        assertNull(state.errorMessage)
    }

    @Test
    fun `acceptUris去重进待上传列表`() {
        val (viewModel, _) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1"))
        driveIdle()
        viewModel.acceptUris(listOf("content://x/1", "content://x/2"))
        driveIdle()
        assertEquals(2, viewModel.uiState.value.pendingItems.size)
    }

    @Test
    fun `enqueue正常入队并清空待上传`() {
        val (viewModel, repository) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1"))
        driveIdle()
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
        val (viewModel, repository) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1", "content://x/2"))
        driveIdle()
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
        val (viewModel, repository) = newViewModel(
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
        viewModel.enqueue()
        driveIdle()
        val state = viewModel.uiState.value
        assertTrue(repository.enqueueCalls.isEmpty())
        assertTrue(state.blockMessage?.contains("超过服务端上限 64 MB") == true)
        assertTrue(state.blockMessage?.contains("big.jpg") == true)
    }

    @Test
    fun `部分超限时被拦项不出网其余照常入队`() {
        val (viewModel, repository) = newViewModel(
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
        val (viewModel, repository) = newViewModel(
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
        viewModel.enqueue()
        driveIdle()
        assertTrue(repository.enqueueCalls.isNotEmpty())
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `新建子目录非法名不发起请求`() {
        val (viewModel, repository) = newViewModel()
        viewModel.createSubDir("../escape")
        driveIdle()
        assertTrue(repository.createDirCalls.isEmpty())
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `新建子目录成功后选中新路径`() {
        val (viewModel, repository) = newViewModel()
        viewModel.selectDir("photos")
        viewModel.createSubDir("2026")
        driveIdle()
        assertEquals(listOf("lib-a" to "photos/2026"), repository.createDirCalls)
        assertEquals("photos/2026", viewModel.uiState.value.selectedDirPath)
    }

    @Test
    fun `队列状态透传到UI状态`() {
        val (viewModel, repository) = newViewModel()
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
        val (viewModel, _) = newViewModel()
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
        val (viewModel, repository) = newViewModel()
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
        val (viewModel, _) = newViewModel()
        assertNull(viewModel.uiState.value.queueSummary)
    }

    // ---- 批C 任务Q C-2：单任务取消 ----

    @Test
    fun `取消透传localId到仓库且无需二次确认`() {
        val (viewModel, repository) = newViewModel()
        val entry = queueEntry(UploadStatus.UPLOADING).copy(localId = "local-42")
        viewModel.cancel(entry)
        driveIdle()
        assertEquals(listOf("local-42"), repository.cancelCalls)
    }

    @Test
    fun `排队中任务同样可取消`() {
        val (viewModel, repository) = newViewModel()
        val entry = queueEntry(UploadStatus.QUEUED).copy(localId = "local-7")
        viewModel.cancel(entry)
        driveIdle()
        assertEquals(listOf("local-7"), repository.cancelCalls)
    }

    @Test
    fun `取消不计入聚合行失败数`() {
        val (viewModel, repository) = newViewModel()
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
        val (viewModel, repository) = newViewModel()
        repository.pushQueue(listOf(queueEntry(UploadStatus.CANCELLED)))
        driveIdle()
        assertFalse(viewModel.uiState.value.hasActiveWork)
    }

    // ---- 2026-09-25：内置相册式选择器选中项合并（acceptPickedItems） ----

    private fun picked(uri: String, name: String, size: Long) =
        LocalMediaItem(uri = uri, displayName = name, sizeBytes = size, isVideo = false)

    @Test
    fun `acceptPickedItems元数据直用不查describe并按uri去重`() {
        val (viewModel, repository) = newViewModel()
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
        val (viewModel, _) = newViewModel()
        viewModel.acceptPickedItems(emptyList())
        driveIdle()
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }
}
