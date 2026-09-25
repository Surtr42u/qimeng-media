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
import media.qimeng.app.core.model.StagedUpload
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
 * 上传流表单状态机锁定（M4-5；2026-09-25 暂存区重做）：
 * - 暂存持久化：暂存条目/批次默认全部走 StagingRepository（写路径断言 add/update/remove/
 *   batchConfig 调用），跨进程恢复语义由持久层单测锁定（StagingJson/InboxFileStore）；
 * - 超限本地拦截：被拦项保留在暂存区，其余照常入队；
 * - 门禁：批次库必选——逐项全覆盖时可缺省；分组入队（批次项走已选目录、覆盖项走库根）；
 * - 收件箱导入：去重 + 批次默认继承 + 未设收件箱/无新文件提示；
 * - 失效探测：路径类条目源不存在标「文件已不存在」，移除通道与普通移除同源；
 * - 作品名联想：防抖 + 建议回填（基名回填，扩展名锁定拼接）；
 * - 挂靠批：批次默认（作者/来源/应用到全部，新进项继承）/ 逐项编辑（清作者连带清来源）。
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
        staging: FakeStagingRepository = FakeStagingRepository(),
    ): Quad<UploadViewModel, FakeUploadRepository, FakeAuthorRepository, FakeStagingRepository> {
        val viewModel = UploadViewModel(repository, authorRepository, staging)
        driveIdle()
        return Quad(viewModel, repository, authorRepository, staging)
    }

    /** 装配记录四元组（原 Triple 因暂存仓注入扩为四元） */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** 流程重排（2026-09-25）：库改在暂存区手选（持久化批次配置），入队类用例先选库再 enqueue */
    private fun selectDefaultLibrary(viewModel: UploadViewModel) {
        viewModel.selectLibrary(libraryA)
        driveIdle()
    }

    private fun picked(uri: String, name: String, size: Long, isVideo: Boolean = false) =
        LocalMediaItem(uri = uri, displayName = name, sizeBytes = size, isVideo = isVideo)

    // ---- 流程重排（2026-09-25）：未选库直接选文件 + 开始上传门禁 ----

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
    fun `acceptUris去重进持久暂存区`() {
        val (viewModel, _, _, staging) = newViewModel()
        viewModel.acceptUris(listOf("content://x/1"))
        driveIdle()
        viewModel.acceptUris(listOf("content://x/1", "content://x/2"))
        driveIdle()
        assertEquals(2, viewModel.uiState.value.pendingItems.size)
        assertEquals(2, staging.addCalls.size)
    }

    @Test
    fun `未选库enqueue被拦截并提示选库后正常入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        viewModel.enqueue()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals("先选择目标库", viewModel.uiState.value.blockMessage)
        // 同一批暂存项，选库后正常入队
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
        // 无暂存项：门禁关闭且不提示
        assertFalse(viewModel.uiState.value.canEnqueue)
        assertNull(viewModel.uiState.value.enqueueGateHint)
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        // 有暂存项未选库：禁用 + 提示
        assertFalse(viewModel.uiState.value.canEnqueue)
        assertEquals("先选择目标库", viewModel.uiState.value.enqueueGateHint)
        selectDefaultLibrary(viewModel)
        // 选库后门禁放行、提示消失
        assertTrue(viewModel.uiState.value.canEnqueue)
        assertNull(viewModel.uiState.value.enqueueGateHint)
    }

    @Test
    fun `逐项全覆盖时未选批次库门禁放行`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.toggleItemLibrary(item, libraryA)
        driveIdle()
        assertTrue(viewModel.uiState.value.canEnqueue)
        assertNull(viewModel.uiState.value.enqueueGateHint)
    }

    @Test
    fun `覆盖项与批次项混合时未选库仍拦截`() {
        val (viewModel, _, _) = newViewModel()
        viewModel.acceptPickedItems(
            listOf(
                picked("content://media/img/1", "IMG_1.jpg", 100L),
                picked("content://media/img/2", "IMG_2.jpg", 100L),
            ),
        )
        driveIdle()
        viewModel.toggleItemLibrary(viewModel.uiState.value.pendingItems.first(), libraryA)
        driveIdle()
        assertFalse(viewModel.uiState.value.canEnqueue)
        assertNotNull(viewModel.uiState.value.enqueueGateHint)
    }

    @Test
    fun `enqueue分组入队批次项走已选目录覆盖项走库根`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.selectDir("photos")
        viewModel.acceptPickedItems(
            listOf(
                picked("content://media/img/1", "IMG_1.jpg", 100L),
                picked("content://media/img/2", "IMG_2.jpg", 100L),
            ),
        )
        driveIdle()
        viewModel.toggleItemLibrary(viewModel.uiState.value.pendingItems.first(), libraryB)
        driveIdle()
        viewModel.enqueue()
        driveIdle()
        // 两条分组：批次组（lib-a + photos）与覆盖组（lib-b + 库根）
        val calls = repository.enqueueCalls
        assertEquals(2, calls.size)
        val batchCall = calls.first { it.libraryId == "lib-a" }
        assertEquals("photos", batchCall.dir)
        assertEquals(listOf("IMG_2.jpg"), batchCall.items.map { it.displayName })
        val overrideCall = calls.first { it.libraryId == "lib-b" }
        assertEquals("", overrideCall.dir)
        assertEquals(listOf("IMG_1.jpg"), overrideCall.items.map { it.displayName })
        // 入队项全部移出暂存区
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }

    @Test
    fun `enqueue正常入队并清空暂存区`() {
        val (viewModel, repository, _, _) = newViewModel()
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
    fun `全部超限时拦截文案生成且不入队`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
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
    fun `部分超限时被拦项保留在暂存区其余照常入队`() {
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
        viewModel.acceptUris(listOf("content://x/big", "content://x/small"))
        driveIdle()
        selectDefaultLibrary(viewModel)
        viewModel.enqueue()
        driveIdle()
        val state = viewModel.uiState.value
        val enqueued = repository.enqueueCalls.single().items
        assertEquals(listOf("small.jpg"), enqueued.map { it.displayName })
        assertNotNull(state.blockMessage)
        // 持久层语义：入队成功的项移除、被拦项保留（用户可移除或下次再传）
        assertEquals(listOf("big.jpg"), state.pendingItems.map { it.displayName })
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

    // ---- 2026-09-25 暂存区重做：收件箱导入 ----

    private fun inboxItem(path: String, name: String, video: Boolean = false) = StagedUpload(
        source = path,
        isPathSource = true,
        displayName = name,
        sizeBytes = 2048L,
        isVideo = video,
        addedAtMs = 1_730_000_000_000L,
    )

    @Test
    fun `收件箱导入按批次默认进暂存区`() {
        val staging = FakeStagingRepository()
        val (viewModel, _, _, _) = newViewModel(
            authorRepository = FakeAuthorRepository(),
            staging = staging,
        )
        staging.seedInboxPath("/storage/emulated/0/.dl")
        staging.scannedItems = listOf(inboxItem("/storage/emulated/0/.dl/a.jpg", "a.jpg"))
        selectDefaultLibrary(viewModel)
        viewModel.importFromInbox()
        driveIdle()
        val staged = viewModel.uiState.value.pendingItems.single()
        assertEquals("a.jpg", staged.displayName)
        assertTrue(staged.isPathSource)
        assertNull(staged.attachAuthorId)
    }

    @Test
    fun `收件箱导入继承批次默认作者与来源`() {
        val staging = FakeStagingRepository().apply { seedInboxPath("/storage/emulated/0/.dl") }
        val (viewModel, _, _, _) = newViewModel(staging = staging)
        staging.scannedItems = listOf(inboxItem("/storage/emulated/0/.dl/a.jpg", "a.jpg"))
        viewModel.pickBatchAuthor(AuthorSuggestion(id = "author-a", displayName = "作者A", fileCount = 1))
        viewModel.toggleBatchSource("kemono")
        driveIdle()
        viewModel.importFromInbox()
        driveIdle()
        val staged = viewModel.uiState.value.pendingItems.single()
        assertEquals("author-a", staged.attachAuthorId)
        assertEquals("作者A", staged.attachAuthorName)
        assertEquals(listOf("kemono"), staged.attachSources)
    }

    @Test
    fun `收件箱导入按source去重不重复入暂存`() {
        val staging = FakeStagingRepository().apply { seedInboxPath("/storage/emulated/0/.dl") }
        val (viewModel, _, _, _) = newViewModel(staging = staging)
        staging.scannedItems = listOf(inboxItem("/storage/emulated/0/.dl/a.jpg", "a.jpg"))
        viewModel.importFromInbox()
        driveIdle()
        viewModel.importFromInbox()
        driveIdle()
        assertEquals(1, viewModel.uiState.value.pendingItems.size)
        assertEquals(2, staging.scanCalls)
    }

    @Test
    fun `收件箱无新文件给提示不写暂存`() {
        val staging = FakeStagingRepository().apply { seedInboxPath("/storage/emulated/0/.dl") }
        val (viewModel, _, _, _) = newViewModel(staging = staging)
        staging.scannedItems = listOf(inboxItem("/storage/emulated/0/.dl/a.jpg", "a.jpg"))
        viewModel.importFromInbox()
        driveIdle()
        staging.scannedItems = emptyList()
        viewModel.importFromInbox()
        driveIdle()
        assertEquals("收件箱里没有新文件", viewModel.uiState.value.noticeMessage)
        assertEquals(1, viewModel.uiState.value.pendingItems.size)
    }

    @Test
    fun `未设收件箱导入给设置引导提示`() {
        val (viewModel, _, _, staging) = newViewModel()
        viewModel.importFromInbox()
        driveIdle()
        assertEquals("尚未设置下载收件箱：请到 设置 → 下载收件箱 选择文件夹", viewModel.uiState.value.noticeMessage)
        assertEquals(0, staging.scanCalls)
    }

    // ---- 失效探测（暂存区重做）：文件已不存在可清除 ----

    @Test
    fun `路径类条目源不存在标记失效且不阻塞其他项`() {
        val staging = FakeStagingRepository().apply {
            missingSources = setOf("/storage/emulated/0/.dl/gone.jpg")
            seedItems(
                listOf(
                    inboxItem("/storage/emulated/0/.dl/gone.jpg", "gone.jpg"),
                    inboxItem("/storage/emulated/0/.dl/here.jpg", "here.jpg"),
                ),
            )
        }
        val (viewModel, _, _, _) = newViewModel(staging = staging)
        driveIdle()
        val missing = viewModel.uiState.value.missingSources
        assertEquals(setOf("/storage/emulated/0/.dl/gone.jpg"), missing)
        assertEquals(2, viewModel.uiState.value.pendingItems.size)
    }

    @Test
    fun `失效条目清除与普通移除同一通道`() {
        val gone = inboxItem("/storage/emulated/0/.dl/gone.jpg", "gone.jpg")
        val staging = FakeStagingRepository().apply { seedItems(listOf(gone)) }
        val (viewModel, _, _, repo) = newViewModel(staging = staging)
        driveIdle()
        viewModel.removeItem(gone)
        driveIdle()
        assertEquals(listOf(listOf(gone.source)), repo.removeCalls)
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
    }

    @Test
    fun `content类条目不做失效探测`() {
        val staging = FakeStagingRepository().apply {
            seedItems(
                listOf(
                    StagedUpload(
                        source = "content://media/external/images/1",
                        isPathSource = false,
                        displayName = "IMG_1.jpg",
                        sizeBytes = 1,
                        isVideo = false,
                    ),
                ),
            )
        }
        val (viewModel, _, _, repo) = newViewModel(staging = staging)
        driveIdle()
        assertTrue(viewModel.uiState.value.missingSources.isEmpty())
        assertTrue(repo.existsCalls.isEmpty())
    }

    // ---- 2026-09-25：内置相册式选择器选中项合并（acceptPickedItems） ----

    @Test
    fun `acceptPickedItems元数据直用不查describe并按uri去重`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 2048L)))
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

    /** 装配：批次作者已选（可带来源）→ 加入一个暂存项；库为入队前置，一并选中 */
    private fun seededWithBatch(
        sources: List<String> = emptyList(),
    ): Quad<UploadViewModel, FakeUploadRepository, FakeAuthorRepository, FakeStagingRepository> {
        val (viewModel, repository, authorRepository, staging) = newViewModel(
            authorRepository = FakeAuthorRepository().apply { vocabularyResult = listOf("kemono") },
        )
        selectDefaultLibrary(viewModel)
        viewModel.pickBatchAuthor(authorA)
        sources.forEach { viewModel.toggleBatchSource(it) }
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        return Quad(viewModel, repository, authorRepository, staging)
    }

    @Test
    fun `新进项继承批次默认作者与来源`() {
        val (viewModel, _, _, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        assertEquals("author-a", item.attachAuthorId)
        assertEquals("作者A", item.attachAuthorName)
        assertEquals(listOf("kemono"), item.attachSources)
    }

    @Test
    fun `未设批次默认时新进项不带挂靠`() {
        val (viewModel, _, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        assertNull(item.attachAuthorId)
        assertNull(item.attachSources)
    }

    @Test
    fun `应用到全部覆盖既有暂存项`() {
        val (viewModel, _, _, _) = newViewModel()
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
        val (viewModel, _, _, _) = newViewModel()
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
    fun `逐项覆盖作者按source定点更新`() {
        val (viewModel, _, _, _) = seededWithBatch()
        viewModel.toggleItemExpanded(viewModel.uiState.value.pendingItems.first())
        viewModel.pickItemAuthor(viewModel.uiState.value.pendingItems.first(), authorB)
        driveIdle()
        assertEquals("author-b", viewModel.uiState.value.pendingItems.single().attachAuthorId)
        assertEquals("作者B", viewModel.uiState.value.pendingItems.single().attachAuthorName)
    }

    @Test
    fun `清空某项作者连带清来源为不带挂靠`() {
        val (viewModel, _, _, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.clearItemAuthor(item)
        driveIdle()
        val cleared = viewModel.uiState.value.pendingItems.single()
        assertNull(cleared.attachAuthorId)
        assertNull(cleared.attachAuthorName)
        assertNull(cleared.attachSources)
    }

    @Test
    fun `作品名基名编辑拼接锁定扩展名并入队载荷携带`() {
        val (viewModel, repository, _, _) = seededWithBatch(sources = listOf("kemono"))
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.onItemNameChanged(item, "我的作品名")
        driveIdle()
        val edited = viewModel.uiState.value.pendingItems.single()
        assertEquals("我的作品名.jpg", edited.effectiveUploadName)
        viewModel.enqueue()
        driveIdle()
        val enqueued = repository.enqueueCalls.single().items.single()
        assertEquals("我的作品名.jpg", enqueued.effectiveUploadName)
        assertEquals("author-a", enqueued.attachAuthorId)
        assertEquals(listOf("kemono"), enqueued.attachSources)
    }

    @Test
    fun `作品名清空回退展示名`() {
        val (viewModel, repository, _, _) = seededWithBatch()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.onItemNameChanged(item, "   ")
        driveIdle()
        assertEquals("IMG_1.jpg", viewModel.uiState.value.pendingItems.single().effectiveUploadName)
        viewModel.enqueue()
        driveIdle()
        assertEquals("IMG_1.jpg", repository.enqueueCalls.single().items.single().effectiveUploadName)
    }

    // ---- 作品名联想（暂存区重做）：防抖 + 建议回填 + 扩展名锁定 ----

    @Test
    fun `作品名输入防抖拉联想并回填基名`() {
        val (viewModel, repository, _, _) = seededWithBatch()
        repository.suggestNamesResult = { q -> if (q == "守望先锋dva") listOf("守望先锋 DVA 13") else emptyList() }
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.toggleItemExpanded(item)
        viewModel.onItemNameChanged(item, "守望先锋dva")
        driveIdle()
        assertEquals(listOf("守望先锋 DVA 13"), viewModel.uiState.value.itemNameSuggestions)
        viewModel.pickNameSuggestion(item, "守望先锋 DVA 13")
        driveIdle()
        val edited = viewModel.uiState.value.pendingItems.single()
        assertEquals("守望先锋 DVA 13", edited.uploadBaseName)
        // 扩展名锁定拼接：展示与入队都是基名 + 原扩展名
        assertEquals("守望先锋 DVA 13.jpg", edited.effectiveUploadName)
    }

    @Test
    fun `作品名联想未选库时不发请求`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.acceptPickedItems(listOf(picked("content://media/img/1", "IMG_1.jpg", 100L)))
        driveIdle()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.toggleItemExpanded(item)
        viewModel.onItemNameChanged(item, "某作品")
        driveIdle()
        assertTrue(repository.suggestNameCalls.isEmpty())
        assertTrue(viewModel.uiState.value.itemNameSuggestions.isEmpty())
    }

    @Test
    fun `作品名联想空输入不发请求`() {
        val (viewModel, repository, _, _) = seededWithBatch()
        val item = viewModel.uiState.value.pendingItems.single()
        viewModel.toggleItemExpanded(item)
        viewModel.onItemNameChanged(item, "  ")
        driveIdle()
        assertTrue(repository.suggestNameCalls.isEmpty())
    }

    // ---- 批次作者联想（口径保持：防抖 + 回车精确命中 + 未命中提示） ----

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

    @Test
    fun `逐项来源toggle需该项已有作者`() {
        val (viewModel, _, _, _) = newViewModel()
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
    fun `入队后清空暂存并复位编辑态`() {
        val (viewModel, repository, _, _) = seededWithBatch()
        viewModel.toggleItemExpanded(viewModel.uiState.value.pendingItems.first())
        viewModel.enqueue()
        driveIdle()
        assertTrue(viewModel.uiState.value.pendingItems.isEmpty())
        assertNull(viewModel.uiState.value.editingSource)
        assertEquals(1, repository.enqueueCalls.size)
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
}
