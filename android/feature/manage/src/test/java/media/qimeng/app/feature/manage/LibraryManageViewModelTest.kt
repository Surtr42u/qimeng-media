package media.qimeng.app.feature.manage

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.data.repository.LibraryRepository
import media.qimeng.app.core.model.LibraryKind
import media.qimeng.app.core.model.LibraryScanState
import media.qimeng.app.core.model.LibrarySummary
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 库管理表单状态机锁定（U10-6）：加载/注册并扫描两步链路/表单校验/启停/删除二次确认/错误态。
 * fake 只在测试源集内（不进 core:testing：本任务文件集外，且当前仅本模块消费）。
 */
class LibraryManageViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val libraryA = LibrarySummary(
        id = "lib-a",
        name = "图集库",
        rootPath = "/data/media/a",
        fileCount = 12,
        videoCount = 2,
        imageCount = 10,
        scanState = LibraryScanState.IDLE,
        enabled = true,
        kind = LibraryKind.NORMAL,
    )

    private fun newViewModel(
        repository: FakeLibraryRepository = FakeLibraryRepository(),
        scanChargeController: FakeScanChargeController = FakeScanChargeController(repository),
    ): Triple<LibraryManageViewModel, FakeLibraryRepository, FakeScanChargeController> =
        Triple(
            LibraryManageViewModel(repository, scanChargeController).also { driveIdle() },
            repository,
            scanChargeController,
        )

    @Test
    fun `init加载库列表无错误`() {
        val (viewModel, _) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        val state = viewModel.uiState.value
        assertEquals(listOf(libraryA), state.libraries)
        assertNull(state.errorMessage)
        assertNull(state.noticeMessage)
    }

    @Test
    fun `加载失败置错误态且loading归位`() {
        val (viewModel, _) = newViewModel(
            FakeLibraryRepository().apply { librariesError = RuntimeException("boom") },
        )
        val state = viewModel.uiState.value
        assertTrue(state.libraries.isEmpty())
        assertNotNull(state.errorMessage)
        assertTrue(!state.loading)
    }

    @Test
    fun `注册并扫描两步链路并清空表单`() {
        val (viewModel, repository) = newViewModel()
        viewModel.updateFormName("新库")
        viewModel.updateFormRootPath("/data/media/new")
        viewModel.updateFormKind(LibraryKind.COS)
        viewModel.registerAndScan()
        driveIdle()
        val register = repository.registerCalls.single()
        assertEquals("新库", register.first)
        assertEquals("/data/media/new", register.second)
        assertEquals(LibraryKind.COS, register.third)
        // Web 口径：POST /libraries 成功后立即补一发 scan
        assertEquals(listOf("new-id"), repository.scanCalls)
        val state = viewModel.uiState.value
        assertEquals("", state.formName)
        assertEquals("", state.formRootPath)
        assertEquals(LibraryKind.NORMAL, state.formKind)
        assertNotNull(state.noticeMessage)
        assertNull(state.errorMessage)
        // 注册结果已进刷新后的列表
        assertTrue(state.libraries.any { it.id == "new-id" })
    }

    @Test
    fun `注册响应缺id时跳过续接扫描但仍注册成功`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply { registerResultId = null },
        )
        viewModel.updateFormName("新库")
        viewModel.updateFormRootPath("/data/media/new")
        viewModel.registerAndScan()
        driveIdle()
        assertEquals(1, repository.registerCalls.size)
        assertTrue(repository.scanCalls.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `注册成功但扫描失败仍清表单并报注册成功`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply { scanError = RuntimeException("boom") },
        )
        viewModel.updateFormName("新库")
        viewModel.updateFormRootPath("/data/media/new")
        viewModel.registerAndScan()
        driveIdle()
        // 两个端点都被触达；注册事实不回滚（reviewer P1：防「注册失败」误报诱导重复注册）
        assertEquals(1, repository.registerCalls.size)
        assertEquals(1, repository.scanCalls.size)
        assertEquals("", viewModel.uiState.value.formName)
        assertTrue(viewModel.uiState.value.noticeMessage!!.startsWith("已注册「新库」"))
        assertTrue(viewModel.uiState.value.noticeMessage!!.contains("扫描失败"))
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `注册失败报错误且表单保留不发扫描`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply { registerError = RuntimeException("boom") },
        )
        viewModel.updateFormName("新库")
        viewModel.updateFormRootPath("/data/media/new")
        viewModel.registerAndScan()
        driveIdle()
        // 注册失败：错误横幅、表单不清（用户修正后重试）、扫描无从谈起
        assertEquals("注册失败，请重试", viewModel.uiState.value.errorMessage)
        assertEquals("新库", viewModel.uiState.value.formName)
        assertTrue(repository.scanCalls.isEmpty())
        assertNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `表单必填校验失败不发起任何请求`() {
        val (viewModel, repository) = newViewModel()
        viewModel.updateFormName("只有名字")
        viewModel.registerAndScan()
        driveIdle()
        assertTrue(repository.registerCalls.isEmpty())
        assertTrue(repository.scanCalls.isEmpty())
        assertEquals("名称与路径均必填", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `重扫触发后刷新列表`() {
        val (viewModel, repository, controller) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        controller.decision = media.qimeng.app.core.data.scan.RescanDecision.STARTED
        viewModel.rescan(libraryA)
        driveIdle()
        assertEquals(listOf("lib-a"), repository.scanCalls)
        assertEquals(listOf("lib-a"), controller.requestCalls)
        assertTrue(viewModel.uiState.value.libraries.single().scanState == LibraryScanState.SCANNING)
        assertEquals("「图集库」扫描已触发", viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `重扫被充电门推迟时给待扫反馈且不触发扫描`() {
        val (viewModel, repository, controller) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        controller.decision = media.qimeng.app.core.data.scan.RescanDecision.DEFERRED
        viewModel.rescan(libraryA)
        driveIdle()
        assertEquals(listOf("lib-a"), controller.requestCalls)
        assertTrue(repository.scanCalls.isEmpty())
        assertEquals(
            "「图集库」未接通电源，已记入待扫描，接入电源后自动开始",
            viewModel.uiState.value.noticeMessage,
        )
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `重扫触发失败给错误横幅`() {
        val (viewModel, _, controller) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        controller.decision = media.qimeng.app.core.data.scan.RescanDecision.FAILED
        viewModel.rescan(libraryA)
        driveIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `删除需二次确认且确认后调用删除`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        viewModel.confirmDelete()
        driveIdle()
        // 未先 requestDelete：无目标时确认是 no-op
        assertTrue(repository.deleteCalls.isEmpty())

        viewModel.requestDelete(libraryA)
        assertEquals(libraryA, viewModel.uiState.value.deleteTarget)
        viewModel.confirmDelete()
        driveIdle()
        assertEquals(listOf("lib-a"), repository.deleteCalls)
        assertNull(viewModel.uiState.value.deleteTarget)
        assertNotNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `启停开关调用repository并刷新`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply { seed.add(libraryA) },
        )
        viewModel.setEnabled(libraryA, false)
        driveIdle()
        assertEquals(listOf("lib-a" to false), repository.setEnabledCalls)
        assertTrue(!viewModel.uiState.value.libraries.single().enabled)
    }

    @Test
    fun `启停失败置错误态且列表还原`() {
        val (viewModel, _) = newViewModel(
            FakeLibraryRepository().apply {
                seed.add(libraryA)
                setEnabledError = RuntimeException("boom")
            },
        )
        viewModel.setEnabled(libraryA, false)
        driveIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        // 刷新还原开关原值（失败不乐观更新）
        assertTrue(viewModel.uiState.value.libraries.single().enabled)
    }

    @Test
    fun `删除失败置错误态`() {
        val (viewModel, repository) = newViewModel(
            FakeLibraryRepository().apply {
                seed.add(libraryA)
                deleteError = RuntimeException("boom")
            },
        )
        viewModel.requestDelete(libraryA)
        viewModel.confirmDelete()
        driveIdle()
        assertEquals(1, repository.deleteCalls.size)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.noticeMessage)
    }
}

/**
 * [LibraryRepository] 测试替身：seed 为可变库表（register/scan/setEnabled/delete 直接改它，
 * 模拟服务端持久化——VM 各写操作后的 refresh 才能看到变化）；各端点可编程抛错，调用记录供断言。
 */
private class FakeLibraryRepository : LibraryRepository {

    /** 初始库表（可变；测试用 libraryA 播种） */
    val seed = mutableListOf<LibrarySummary>()

    var librariesError: Exception? = null

    /** POST /libraries 响应给出的 id（null = 模拟响应缺 id，调用方应跳过续接扫描） */
    var registerResultId: String? = "new-id"
    var registerError: Exception? = null
    var scanError: Exception? = null
    var deleteError: Exception? = null
    var setEnabledError: Exception? = null

    val registerCalls = mutableListOf<Triple<String, String, LibraryKind>>()
    val scanCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun libraries(): List<LibrarySummary> {
        librariesError?.let { throw it }
        return seed.toList()
    }

    override suspend fun register(name: String, rootPath: String, kind: LibraryKind): LibrarySummary? {
        registerError?.let { throw it }
        registerCalls.add(Triple(name, rootPath, kind))
        val id = registerResultId ?: return null
        val created = LibrarySummary(
            id = id,
            name = name,
            rootPath = rootPath,
            fileCount = 0,
            videoCount = 0,
            imageCount = 0,
            scanState = LibraryScanState.IDLE,
            enabled = true,
            kind = kind,
        )
        seed.add(created)
        return created
    }

    override suspend fun delete(libraryId: String) {
        // 调用记录先于抛错：失败路径也要能断言「端点确实被调用过」
        deleteCalls.add(libraryId)
        deleteError?.let { throw it }
        seed.removeAll { it.id == libraryId }
    }

    override suspend fun scan(libraryId: String) {
        scanCalls.add(libraryId)
        scanError?.let { throw it }
        replaceSeed(libraryId) { it.copy(scanState = LibraryScanState.SCANNING) }
    }

    override suspend fun setEnabled(libraryId: String, enabled: Boolean) {
        setEnabledCalls.add(libraryId to enabled)
        setEnabledError?.let { throw it }
        replaceSeed(libraryId) { it.copy(enabled = enabled) }
    }

    private fun replaceSeed(libraryId: String, transform: (LibrarySummary) -> LibrarySummary) {
        for (index in seed.indices) {
            if (seed[index].id == libraryId) seed[index] = transform(seed[index])
        }
    }
}

/**
 * [ScanChargeController] 测试替身（批C 任务Q C-3）：决策可编程（STARTED 时模拟 controller
 * 内部直扫路径调 repository.scan，与生产实现同构）；充电判定/持久化不在本层测——
 * ScanGate 纯函数单测 + 模拟器冒烟覆盖。
 */
private class FakeScanChargeController(
    private val repository: FakeLibraryRepository,
) : media.qimeng.app.core.data.scan.ScanChargeController {

    var decision = media.qimeng.app.core.data.scan.RescanDecision.STARTED

    val requestCalls = mutableListOf<String>()

    private val chargeOnly = kotlinx.coroutines.flow.MutableStateFlow(true)

    override val chargeOnlyScanEnabled: Flow<Boolean> = chargeOnly

    override suspend fun setChargeOnlyScanEnabled(enabled: Boolean) {
        chargeOnly.value = enabled
    }

    override suspend fun requestRescan(libraryId: String): media.qimeng.app.core.data.scan.RescanDecision {
        requestCalls.add(libraryId)
        if (decision == media.qimeng.app.core.data.scan.RescanDecision.STARTED) {
            // 模拟生产 controller 的直扫路径（requestRescan 内部调 repository.scan）
            repository.scan(libraryId)
            return media.qimeng.app.core.data.scan.RescanDecision.STARTED
        }
        return decision
    }

    override suspend fun onPowerConnected() = Unit

    override suspend fun resumeDeferredIfCharging() = Unit
}
