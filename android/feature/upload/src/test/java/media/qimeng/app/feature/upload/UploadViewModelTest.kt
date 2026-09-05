package media.qimeng.app.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 上传流表单状态机锁定（M4-5）：超限本地拦截 / 入队参数与顺序 / 新建目录校验 / 分享接收 / 队列透传。
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
}
