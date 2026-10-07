package media.qimeng.app.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
 * - 直传管道（系统分享接收，选完即传）：describe 解元数据 → 未选库门禁 → 逐项判超限
 *   （超限项本地拦截不出网给 blockText 文案，未超限项照常入队）→ enqueue 继承批次默认
 *   快照（批次库 + 已选目录 + 作者/来源 + 库名）；
 * - 选中文件预览/编辑文件名（2026-10-07 加回，SAF 多选走此管道）：onFilesSelected 预览
 *   → 基名编辑（扩展名锁定）→ uploadSelectedFiles 门禁/超限/入队同直传口径，成功清空列表；
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
        // 归档扫描走 IO 调度器（注入测试调度器，driveIdle 确定性驱动）
        val viewModel = UploadViewModel(repository, authorRepository, staging, mainDispatcherRule.testDispatcher)
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
            // site-d 只存在于组合里：拆词提取后同样必须出现在建议中（用户实测反馈漏词）
            vocabularyResult = listOf("site-a", "site-a  site-b", "site-d  site-b", "")
        }
        val (viewModel, _, _, _) = newViewModel(authorRepository = authorRepository)
        // 组合按空白拆词去重、空串过滤；作者联想为百度式（空输入无建议列表）
        assertEquals(listOf("site-a", "site-b", "site-d"), viewModel.uiState.value.sourceOptions)
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
        viewModel.toggleBatchSource("site-a")
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
        assertEquals(listOf("site-a"), call.items.single().attachSources)
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

    // ---- 选中文件预览/编辑文件名（2026-10-07 文件名编辑加回：SAF 走 onFilesSelected
    //      预览 → 基名编辑 → 「开始上传」入队；系统分享仍走 submitUris 选完即传不变） ----

    @Test
    fun `onFilesSelected填充选中列表且预览阶段不入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.onFilesSelected(listOf("content://media/img/1", "content://media/img/2"))
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), state.selectedFiles.map { it.displayName })
        assertFalse(state.describing)
        // 预览阶段只 describe 不入队：等「开始上传」
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals(2, repository.describeCalls)
    }

    @Test
    fun `onFilesSelected空列表忽略`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.onFilesSelected(emptyList())
        driveIdle()
        assertEquals(0, repository.describeCalls)
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())
    }

    @Test
    fun `onFilesSelecteddescribe失败给错误横幅且列表为空`() {
        val (viewModel, _, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                describedItem = { throw IllegalStateException("describe boom") }
            },
        )
        viewModel.onFilesSelected(listOf("content://media/img/1"))
        driveIdle()
        assertEquals(DESCRIBE_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())
        assertFalse(viewModel.uiState.value.describing)
    }

    @Test
    fun `基名编辑经开始上传生效于入队载荷且列表清空`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.selectDir("photos")
        viewModel.onFilesSelected(listOf("content://media/img/1"))
        driveIdle()
        val selected = viewModel.uiState.value.selectedFiles.single()
        viewModel.updateSelectedFileName(selected, "新作品名")
        driveIdle()
        assertEquals("新作品名", viewModel.uiState.value.selectedFiles.single().currentBaseName)
        viewModel.uploadSelectedFiles()
        driveIdle()

        val enqueued = repository.enqueueCalls.single()
        assertEquals("lib-a", enqueued.libraryId)
        assertEquals("photos", enqueued.dir)
        val item = enqueued.items.single()
        assertEquals("新作品名", item.uploadBaseName)
        // 落库名经 effectiveUploadName 单源拼装：基名 + 锁定扩展名
        assertEquals("新作品名.jpg", item.effectiveUploadName)
        assertEquals("IMG_1.jpg", item.displayName)
        // 入队成功后选中列表清空、提交态复位
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `基名编辑清空回退展示名入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.onFilesSelected(listOf("content://media/img/1"))
        driveIdle()
        val selected = viewModel.uiState.value.selectedFiles.single()
        viewModel.updateSelectedFileName(selected, "改名")
        driveIdle()
        viewModel.updateSelectedFileName(viewModel.uiState.value.selectedFiles.single(), "   ")
        driveIdle()
        // 空白编辑 = 未编辑口径：回退展示名
        assertEquals("IMG_1.jpg", viewModel.uiState.value.selectedFiles.single().effectiveUploadName)
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertEquals(
            listOf("IMG_1.jpg"),
            repository.enqueueCalls.single().items.map { it.effectiveUploadName },
        )
    }

    @Test
    fun `移除选中项后开始上传只入队剩余`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.onFilesSelected(listOf("content://media/img/1", "content://media/img/2"))
        driveIdle()
        viewModel.removeSelectedFile(viewModel.uiState.value.selectedFiles.first())
        driveIdle()
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertEquals(
            listOf("IMG_2.jpg"),
            repository.enqueueCalls.single().items.map { it.displayName },
        )
    }

    @Test
    fun `未选库开始上传被拦并提示选库`() {
        val (viewModel, repository, _, _) = newViewModel()
        viewModel.onFilesSelected(listOf("content://media/img/1"))
        driveIdle()
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        assertEquals("先选择目标库", viewModel.uiState.value.blockMessage)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `开始上传全部超限时拦截不出网`() {
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
        viewModel.onFilesSelected(listOf("content://x/big"))
        driveIdle()
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
        val state = viewModel.uiState.value
        assertTrue(state.blockMessage?.contains("超过服务端上限 64 MB") == true)
        // 预览列表保留（未清空）：用户可移除或改后重试
        assertEquals(1, state.selectedFiles.size)
    }

    @Test
    fun `开始上传部分超限时被拦项拦下其余照常入队`() {
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
        viewModel.onFilesSelected(listOf("content://x/big", "content://x/small"))
        driveIdle()
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertEquals(
            listOf("small.jpg"),
            repository.enqueueCalls.single().items.map { it.displayName },
        )
        assertNotNull(viewModel.uiState.value.blockMessage)
    }

    @Test
    fun `开始上传空列表与重复触发不产生入队`() {
        val (viewModel, repository, _, _) = newViewModel()
        selectDefaultLibrary(viewModel)
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
    }

    @Test
    fun `入队失败时选中列表保留可重试`() {
        val (viewModel, repository, _, _) = newViewModel(
            repository = FakeUploadRepository().apply {
                librariesResult = listOf(libraryA)
                enqueueError = IllegalStateException("enqueue boom")
            },
        )
        selectDefaultLibrary(viewModel)
        viewModel.onFilesSelected(listOf("content://media/img/1"))
        driveIdle()
        viewModel.uploadSelectedFiles()
        driveIdle()
        assertEquals(ENQUEUE_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
        assertEquals(1, viewModel.uiState.value.selectedFiles.size)
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
        viewModel.toggleBatchSource("site-a")
        driveIdle()
        viewModel.clearBatchAuthor()
        driveIdle()
        assertNull(viewModel.uiState.value.batchAuthorId)
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
    }

    @Test
    fun `批次来源toggle需先选作者`() {
        val (viewModel, _, _, _) = newViewModel()
        viewModel.toggleBatchSource("site-a")
        driveIdle()
        assertTrue(viewModel.uiState.value.batchSources.isEmpty())
        viewModel.pickBatchAuthor(authorA)
        viewModel.toggleBatchSource("site-a")
        driveIdle()
        assertEquals(listOf("site-a"), viewModel.uiState.value.batchSources)
    }

    // ---- 归档一键上传（2026-10-01：扫描 -> 确认 -> 按匹配库分组入队；入队后防整批重入队
    //      门禁 + 超限本地过滤 + 库加载失败不隐藏归档区） ----

    @get:Rule
    val archiveTmp = TemporaryFolder()

    /** 归档测试装配：播种归档路径与临时目录后手工构造 VM（扫描挂在 init 的库列表就绪后） */
    private fun newArchiveViewModel(
        staging: FakeStagingRepository,
        repository: FakeUploadRepository,
    ): Pair<UploadViewModel, FakeUploadRepository> {
        val viewModel = UploadViewModel(repository, FakeAuthorRepository(), staging, mainDispatcherRule.testDispatcher)
        driveIdle()
        return viewModel to repository
    }

    @Test
    fun `归档根未配置时无扫描结果`() {
        val (viewModel, repository) = newViewModel()
        assertNull(viewModel.uiState.value.archiveBatch)
        assertFalse(viewModel.uiState.value.archiveBatchLoading)
        // 一键路径无从触发：不入队
        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
    }

    @Test
    fun `一键上传按匹配库分组入队且携带alreadyArchived`() {
        val root = archiveTmp.newFolder("归档")
        val libADir = archiveTmp.newFolder("归档/测试库A")
        val libBDir = archiveTmp.newFolder("归档/测试库B/sub")
        File(libADir, "a.jpg").writeText("123")
        File(libBDir, "b.mp4").writeText("4567")

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesResult = listOf(libraryA, libraryB) }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        // 扫描摘要：2 个待传条目、命中 2 库、无未匹配
        val scan = viewModel.uiState.value.archiveBatch
        assertNotNull(scan)
        assertEquals(2, scan!!.items.size)
        assertEquals("待传 2 个文件 · 命中 2 个库 · 未匹配 0 个文件夹", viewModel.uiState.value.archiveBatchSummary)

        viewModel.onArchiveBatchUpload()
        driveIdle()

        // 按目标库分组入队（两组），调用级 dir 恒库根、条目 dir 由 relativeDir 承载
        assertEquals(2, repository.enqueueCalls.size)
        val callA = repository.enqueueCalls.first { it.libraryId == "lib-a" }
        assertEquals("", callA.dir)
        assertEquals("测试库A", callA.libraryName)
        val itemA = callA.items.single()
        assertTrue(itemA.alreadyArchived)
        assertEquals("测试库A", itemA.libraryName)
        assertEquals(File(libADir, "a.jpg").absolutePath, itemA.uri)
        assertEquals("", itemA.relativeDir)
        // 一键路径不继承批次作者/来源（归档区是整理过的存量，整批挂默认作者=错误挂靠）
        assertNull(itemA.attachAuthorId)
        assertNull(itemA.attachSources)

        val callB = repository.enqueueCalls.first { it.libraryId == "lib-b" }
        val itemB = callB.items.single()
        assertTrue(itemB.alreadyArchived)
        assertEquals("测试库B", itemB.libraryName)
        assertEquals("sub", itemB.relativeDir)
        assertEquals(File(libBDir, "b.mp4").absolutePath, itemB.uri)

        // 入队成功给轻提示（既有 noticeMessage 横幅）
        assertTrue(viewModel.uiState.value.noticeMessage?.contains("已加入上传队列") == true)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `一键上传无匹配条目时不入队`() {
        val root = archiveTmp.newFolder("空归档")
        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesResult = listOf(libraryA) }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        assertTrue(viewModel.uiState.value.archiveBatch?.items.isNullOrEmpty())
        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
    }

    @Test
    fun `归档扫描未匹配文件夹进结果且不产生条目`() {
        val root = archiveTmp.newFolder("归档")
        archiveTmp.newFolder("归档/未知库")

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesResult = listOf(libraryA) }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        val scan = viewModel.uiState.value.archiveBatch
        assertNotNull(scan)
        val unmatched = scan!!.unmatchedFolders.single()
        assertEquals("未知库", unmatched.first)
        assertEquals("未找到同名库", unmatched.second)
        assertTrue(scan.items.isEmpty())
        // 只有未匹配文件夹时一键路径不允许触发（VM 兜底 + UI 按钮置灰同口径）
        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertTrue(repository.enqueueCalls.isEmpty())
    }

    @Test
    fun `一键入队成功后置防重入队门禁且再次调用被拦`() {
        val root = archiveTmp.newFolder("归档")
        val libADir = archiveTmp.newFolder("归档/测试库A")
        File(libADir, "a.jpg").writeText("123")

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesResult = listOf(libraryA) }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        viewModel.onArchiveBatchUpload()
        driveIdle()

        assertEquals(1, repository.enqueueCalls.size)
        assertTrue(viewModel.uiState.value.archiveBatchEnqueued)
        // 入队成功触发重扫：源文件上传成功前仍原位，文件数未变 → 门禁保持置位
        assertEquals(1, viewModel.uiState.value.archiveBatch?.items?.size)
        assertTrue(viewModel.uiState.value.archiveBatchEnqueued)

        // 门禁置位期间再次调用被拦：不产生新的入队调用
        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertEquals(1, repository.enqueueCalls.size)
    }

    @Test
    fun `入队后归档区文件数变化重扫复位门禁`() {
        val root = archiveTmp.newFolder("归档")
        val libADir = archiveTmp.newFolder("归档/测试库A")
        File(libADir, "a.jpg").writeText("123")

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesResult = listOf(libraryA) }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertTrue(viewModel.uiState.value.archiveBatchEnqueued)

        // 归档区新增文件（重扫文件数与入队时不同）：门禁复位，可再次一键上传
        File(libADir, "b.jpg").writeText("456")
        viewModel.refreshArchiveBatch()
        driveIdle()
        assertFalse(viewModel.uiState.value.archiveBatchEnqueued)
        assertEquals(2, viewModel.uiState.value.archiveBatch?.items?.size)

        viewModel.onArchiveBatchUpload()
        driveIdle()
        assertEquals(2, repository.enqueueCalls.size)
        assertEquals(2, repository.enqueueCalls.last().items.size)
    }

    @Test
    fun `一键上传超限条目本地过滤不入队`() {
        val root = archiveTmp.newFolder("归档")
        val libADir = archiveTmp.newFolder("归档/测试库A")
        File(libADir, "small.jpg").writeText("123")
        // >1MB 真实文件：条目大小取 file.length()，超限判定与手动直传同一单源口径
        val big = File(libADir, "big.jpg")
        big.outputStream().use { it.write(ByteArray(1024 * 1024 + 1)) }

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply {
            librariesResult = listOf(libraryA)
            limitsResult = UploadLimits(maxBytesMb = 1, autoAccept = true)
        }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        // 确认弹窗摘要口径：1 个超限文件将被跳过
        assertEquals(1, viewModel.uiState.value.archiveBatchOverLimitCount)

        viewModel.onArchiveBatchUpload()
        driveIdle()

        // 超限项不入队：只入 small.jpg；文案沿用既有 blockText 来源（列名 + 上限）
        assertEquals(1, repository.enqueueCalls.size)
        assertEquals(listOf("small.jpg"), repository.enqueueCalls.single().items.map { it.displayName })
        assertTrue(viewModel.uiState.value.blockMessage?.contains("big.jpg") == true)
        assertTrue(viewModel.uiState.value.blockMessage?.contains("超过服务端上限 1 MB") == true)
        // 部分入队成功：防重入队门禁照常置位（比对基准为入队时整轮扫描数 2）
        assertTrue(viewModel.uiState.value.archiveBatchEnqueued)
    }

    @Test
    fun `库列表加载失败时归档扫描仍执行`() {
        val root = archiveTmp.newFolder("归档")
        val libADir = archiveTmp.newFolder("归档/测试库A")
        File(libADir, "a.jpg").writeText("123")

        val staging = FakeStagingRepository().apply { seedArchivePath(root.absolutePath) }
        val repository = FakeUploadRepository().apply { librariesError = IllegalStateException("boom") }
        val (viewModel, _) = newArchiveViewModel(staging, repository)

        // 库加载失败横幅照旧
        assertEquals(LOAD_FAILED_MESSAGE, viewModel.uiState.value.errorMessage)
        // 归档区不被静默隐藏：空库列表匹配不到库，「测试库A」如实落未匹配清单
        val scan = viewModel.uiState.value.archiveBatch
        assertNotNull(scan)
        assertEquals("测试库A" to "未找到同名库", scan!!.unmatchedFolders.single())
        assertTrue(scan.items.isEmpty())
    }
}
