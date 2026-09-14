package media.qimeng.app.feature.upload

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.upload.FolderScanResult
import media.qimeng.app.core.data.upload.FolderScanner
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.testing.FakeUploadRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 上传流表单状态机锁定（M4-5）：超限本地拦截 / 入队参数与顺序 / 新建目录校验 / 分享接收 / 队列透传。
 * U10-6c 追加：选文件夹扫描合并（去重/相对目录入队映射/截断跳过提示/扫描中防抖）。
 * 队列串行执行本身由 WorkManager unique 链官方语义保证（UploadWorkSpec 注释），
 * 实机串行时间线走模拟器文本证据（HANDOVER_APP §4.7）。
 */
class UploadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val libraryA = LibraryChoice(id = "lib-a", name = "测试库A")
    private val libraryB = LibraryChoice(id = "lib-b", name = "测试库B")

    private lateinit var scanner: FakeFolderScanner

    private fun newViewModel(repository: FakeUploadRepository = FakeUploadRepository().apply {
        librariesResult = listOf(libraryA, libraryB)
    }): Pair<UploadViewModel, FakeUploadRepository> {
        scanner = FakeFolderScanner()
        val viewModel = UploadViewModel(repository, scanner)
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

    // ---- U10-6c：选文件夹上传 ----

    private fun folderItem(uri: String, name: String, relativeDir: String) =
        UploadItem(uri = uri, displayName = name, sizeBytes = 100L, relativeDir = relativeDir)

    private fun queueEntry(status: UploadStatus) = UploadQueueEntry(
        localId = status.name,
        displayName = "f.jpg",
        status = status,
        progressPercent = null,
        finalFileName = null,
        errorMessage = null,
    )

    @Test
    fun `acceptFolder合并进待传并按uri去重`() {
        val (viewModel, _) = newViewModel()
        viewModel.acceptFolder(
            FolderScanResult(files = listOf(folderItem("u1", "a.jpg", "作者A")), skippedCount = 0, truncated = false, totalUploadable = 1),
        )
        viewModel.acceptFolder(
            FolderScanResult(
                files = listOf(folderItem("u1", "a.jpg", "作者A"), folderItem("u2", "b.jpg", "作者A/子")),
                skippedCount = 0,
                truncated = false,
                totalUploadable = 2,
            ),
        )
        driveIdle() // uiState 经 combine().stateIn 异步传播，直调后需推进调度器
        val pending = viewModel.uiState.value.pendingItems
        assertEquals(2, pending.size)
        assertEquals("作者A", pending[0].relativeDir)
        assertEquals("作者A/子", pending[1].relativeDir)
    }

    @Test
    fun `acceptFolderTree走扫描并把相对目录带到入队`() {
        val (viewModel, repository) = newViewModel()
        scanner.result = FolderScanResult(
            files = listOf(folderItem("content://doc/1", "a.jpg", "作者A")),
            skippedCount = 0,
            truncated = false,
            totalUploadable = 1,
        )
        viewModel.selectDir("photos")
        viewModel.acceptFolderTree("content://tree/x")
        driveIdle()
        assertEquals(listOf("content://tree/x"), scanner.scannedUris)
        viewModel.enqueue()
        driveIdle()
        val call = repository.enqueueCalls.single()
        assertEquals("photos", call.dir)
        val enqueued = call.items.single()
        assertEquals("作者A", enqueued.relativeDir)
        // per-item spec dir（口径①：所选文件夹名作为 dir 首段，叠加页面已选目录）
        assertEquals("photos/作者A", UploadRules.joinUploadDirPath(call.dir, enqueued.relativeDir))
        assertNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `文件夹截断与跳过提示文案`() {
        val (viewModel, _) = newViewModel()
        viewModel.acceptFolder(
            FolderScanResult(
                files = listOf(folderItem("u1", "a.jpg", "作者A")),
                skippedCount = 2,
                truncated = true,
                totalUploadable = 1001,
            ),
        )
        driveIdle() // uiState 异步传播
        val message = viewModel.uiState.value.blockMessage
        assertTrue(message?.contains("已选前 1000 个") == true)
        assertTrue(message?.contains("共 1001 个") == true)
        assertTrue(message?.contains("已跳过 2 个非媒体文件") == true)
    }

    @Test
    fun `空文件夹扫描给空提示`() {
        val (viewModel, _) = newViewModel()
        viewModel.acceptFolder(FolderScanResult.EMPTY)
        driveIdle() // uiState 异步传播
        assertEquals("所选文件夹中没有可上传的媒体文件", viewModel.uiState.value.blockMessage)
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }

    @Test
    fun `扫描失败给错误横幅且不进待传`() {
        val (viewModel, _) = newViewModel()
        scanner.error = IllegalStateException("provider 失效")
        viewModel.acceptFolderTree("content://tree/x")
        driveIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
        assertFalse(viewModel.uiState.value.scanningFolder)
    }

    @Test
    fun `扫描中状态防抖且完成后恢复`() {
        val (viewModel, _) = newViewModel()
        scanner.gate = CompletableDeferred()
        viewModel.acceptFolderTree("content://tree/x")
        driveIdle() // scan 挂起在 gate：扫描中状态可见
        assertTrue(viewModel.uiState.value.scanningFolder)
        viewModel.acceptFolderTree("content://tree/x") // 扫描中再点不重复触发
        driveIdle()
        assertEquals(1, scanner.scannedUris.size)
        scanner.gate?.complete(Unit)
        driveIdle()
        assertFalse(viewModel.uiState.value.scanningFolder)
        assertEquals(1, scanner.scannedUris.size)
    }

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
}

/** [FolderScanner] 测试替身：结果可编程、可挂起在 gate 模拟扫描中（JVM 纯 Kotlin）。 */
private class FakeFolderScanner : FolderScanner {

    var result: FolderScanResult = FolderScanResult.EMPTY

    var error: Exception? = null

    /** 置非空则 scan 挂起直至手动放行（驱动"扫描中"状态与防抖断言） */
    var gate: CompletableDeferred<Unit>? = null

    val scannedUris = mutableListOf<String>()

    override suspend fun scan(treeUri: String): FolderScanResult {
        scannedUris += treeUri
        gate?.await()
        error?.let { throw it }
        return result
    }
}
