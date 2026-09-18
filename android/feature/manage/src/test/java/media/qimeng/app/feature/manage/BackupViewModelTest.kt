package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.events.EventClock
import media.qimeng.app.core.data.events.PendingViewEventDao
import media.qimeng.app.core.data.events.PendingViewEventEntity
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.events.ViewEventSender
import media.qimeng.app.core.data.events.ViewEventSendResult
import media.qimeng.app.core.data.repository.BackupAutoPrefs
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.data.repository.StagedBackup
import media.qimeng.app.core.data.repository.StagedBackupMeta
import media.qimeng.app.core.data.repository.SyncStagingRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.sdk.models.LegacyBackupData
import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import media.qimeng.sdk.models.LegacyImportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * 备份导入/导出状态机锁定（U10-6b）：导出成功与 KB 反馈/导出防重/导入取消静默/确认调用/
 * 前置校验拒绝/错误态。fake 只在测试源集内（LibraryManageViewModelTest 同款模式）。
 * 注：导出 SAF 选位「用户取消（uri=null）」发生在屏幕层 launcher 回调（SettingsScreen
 * 先例），VM 层对应的静默语义 = 取消确认（dismissImport）不触发出网，在此锁定。
 * 2026-09-16 用户反馈：自动备份 prefs 回流/立即备份反馈入锁；「导出未上传」取数端
 * （exportPending/onExported）随按钮退役，不再有对应用例。
 */
class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val exportFile = LegacyBackupFile(
        format = "qimeng_backup",
        schemaVersion = 1,
        appIdentifier = "com.qimeng.media",
        data = LegacyBackupData(),
    )

    /** 合法信封字节（经 BackupValidator 真·前置校验，mock 不掺进校验链） */
    private val validBytes = """
        {"format": "qimeng_backup", "schemaVersion": 1, "appIdentifier": "com.qimeng.media",
         "data": {"authors": [{"authorId": "A1", "displayName": "作者一"}]}}
    """.trimIndent().toByteArray()

    private fun newViewModel(
        repository: FakeBackupRepository = FakeBackupRepository(),
        queue: ViewEventQueue = ViewEventQueue(FakeEventDao(), FakeEventSender(fail = false), clock = FixedEventClock),
        prefs: BackupAutoPrefsRepository = FakeBackupAutoPrefs(),
        staging: FakeSyncStagingRepository = FakeSyncStagingRepository(),
    ) = Pair(
        BackupViewModel(
            backupRepository = repository,
            viewEventQueue = queue,
            autoBackupPrefs = prefs,
            autoBackupRunner = newAutoBackupRunner(repository, prefs),
            syncStaging = staging,
        ).also { driveIdle() },
        repository,
    )

    /**
     * AutoBackupRunner 测试实例（Unsafe 绕过构造器）：其构造器要求非空 Context，JVM 单测
     * 无真实对象可给（android.jar stub 的 Context 是抽象类，本仓库无 mock 依赖），而本测试组
     * 只覆盖「未选目录 → writeNow 短路返回 false」路径，context 永不被触达——故绕过构造
     * 仅注入 backupRepository/prefs 两个真实依赖，context 字段留 null。
     */
    private fun newAutoBackupRunner(
        repository: BackupRepository,
        prefs: BackupAutoPrefsRepository,
    ): AutoBackupRunner {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val runner = allocate.invoke(unsafe, AutoBackupRunner::class.java) as AutoBackupRunner
        AutoBackupRunner::class.java.declaredFields
            .filter { it.name == "backupRepository" || it.name == "prefs" }
            .forEach { field ->
                field.isAccessible = true
                when (field.name) {
                    "backupRepository" -> field.set(runner, repository)
                    "prefs" -> field.set(runner, prefs)
                }
            }
        return runner
    }

    @Test
    fun `导出成功出序列化内容且写入回执置KB反馈`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, repository) = newViewModel()
        val export = viewModel.prepareExport()
        driveIdle()
        // 内容来自 GET /export/qimeng-backup 且已序列化为旧版信封 JSON
        assertEquals(1, repository.exportCalls.size)
        assertNotNull(export)
        assertTrue(export!!.json.contains("\"format\":\"qimeng_backup\""))
        assertEquals(export.json.toByteArray(Charsets.UTF_8).size, export.sizeBytes)
        assertTrue(viewModel.uiState.value.pendingExport != null)
        // 屏幕层落盘回执：2048 字节 → Web (sizeBytes/1024).toFixed(0) KB 逐字
        viewModel.onExportWritten(2048)
        val state = viewModel.uiState.value
        assertEquals("已导出 qimeng_backup.json（2 KB）", state.noticeMessage)
        assertNull(state.pendingExport)
        assertNull(state.errorMessage)
    }

    @Test
    fun `导出防重进行中与待写未消费时不出第二次`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, repository) = newViewModel()
        viewModel.prepareExport()
        driveIdle()
        // pendingExport 未被屏幕层消费前，再次 prepareExport 是 no-op（按钮已禁用，双保险）
        assertNull(viewModel.prepareExport())
        driveIdle()
        assertEquals(1, repository.exportCalls.size)
    }

    @Test
    fun `导出请求失败置错误态且无待写内容`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = newViewModel(
            FakeBackupRepository().apply { exportError = RuntimeException("boom") },
        )
        val export = viewModel.prepareExport()
        driveIdle()
        assertNull(export)
        assertEquals("导出失败，请重试", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.pendingExport)
    }

    @Test
    fun `写入失败置错误横幅并清待写`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = newViewModel()
        viewModel.prepareExport()
        driveIdle()
        viewModel.onExportWritten(null)
        val state = viewModel.uiState.value
        assertEquals("写入备份文件失败，请重试", state.errorMessage)
        assertNull(state.pendingExport)
    }

    @Test
    fun `导入文件经前置校验后弹确认且未出网`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("qimeng_backup.json", validBytes)
        val pending = viewModel.uiState.value.pendingImport
        // 确认前零出网（Web 同构：pending 非 null 只开弹窗）
        assertTrue(repository.importCalls.isEmpty())
        assertNotNull(pending)
        assertEquals("qimeng_backup.json", pending!!.summary.fileName)
        assertEquals(1, pending.summary.authors)
    }

    @Test
    fun `取消确认静默不出网`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("qimeng_backup.json", validBytes)
        viewModel.dismissImport()
        val state = viewModel.uiState.value
        assertTrue(repository.importCalls.isEmpty())
        assertNull(state.pendingImport)
        assertNull(state.errorMessage)
        assertNull(state.noticeMessage)
    }

    @Test
    fun `确认导入调用端点并置成功反馈`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("qimeng_backup.json", validBytes)
        viewModel.confirmImport()
        driveIdle()
        // Web confirmImport 逐字语义：确认后清 pending → POST 载荷
        assertEquals(1, repository.importCalls.size)
        assertEquals("qimeng_backup", repository.importCalls.single().format)
        val state = viewModel.uiState.value
        // Web L233-234 逐字：导入完成：匹配文件 M/N，作者 A，标签 T，事件回放 E 条
        assertEquals("「qimeng_backup.json」导入完成：匹配文件 4/9，作者 3，标签 2，事件回放 5 条", state.noticeMessage)
        assertTrue(state.warnings.isEmpty())
        assertTrue(!state.importing)
        assertNull(state.pendingImport)
    }

    @Test
    fun `导入warnings进状态由屏幕逐条展示`() {
        val (viewModel, _) = newViewModel(
            FakeBackupRepository().apply {
                resultWarnings = listOf("albumRules 已忽略", "scanSources 不迁移")
            },
        )
        viewModel.onFilePicked("qimeng_backup.json", validBytes)
        viewModel.confirmImport()
        driveIdle()
        assertEquals(listOf("albumRules 已忽略", "scanSources 不迁移"), viewModel.uiState.value.warnings)
    }

    @Test
    fun `前置校验拒绝置错误横幅不出网`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("坏.json", "不是json".toByteArray())
        val state = viewModel.uiState.value
        assertEquals(BackupValidator.MESSAGE_BAD_JSON, state.errorMessage)
        assertNull(state.pendingImport)
        assertTrue(repository.importCalls.isEmpty())
    }

    @Test
    fun `导入请求失败置错误态`() {
        val (viewModel, _) = newViewModel(
            FakeBackupRepository().apply { importError = RuntimeException("boom") },
        )
        viewModel.onFilePicked("qimeng_backup.json", validBytes)
        viewModel.confirmImport()
        driveIdle()
        assertEquals("导入失败，请重试", viewModel.uiState.value.errorMessage)
        assertTrue(!viewModel.uiState.value.importing)
        assertNull(viewModel.uiState.value.noticeMessage)
    }
    // ---------- 浏览数据同步（2026-09-15 批自我的页迁入；SettingsViewModelTest 原款用例随迁） ----------

    @Test
    fun `浏览数据同步 - 成功后提示同步完成且待上传数清零`() = runTest(mainDispatcherRule.testDispatcher) {
        val dao = FakeEventDao()
        dao.seed("00000000-0000-0000-0000-0000000000a1")
        dao.seed("00000000-0000-0000-0000-0000000000a2")
        val queue = ViewEventQueue(dao, FakeEventSender(fail = false), clock = FixedEventClock)
        val (vm, _) = newViewModel(queue = queue)
        advanceUntilIdle() // init loadPendingEvents
        assertEquals(2, vm.uiState.value.pendingEvents)

        vm.syncEventsNow()
        advanceUntilIdle()

        assertEquals("同步完成", vm.uiState.value.eventSyncNote)
        assertEquals(0, vm.uiState.value.pendingEvents)
        assertFalse(vm.uiState.value.eventSyncing)
    }

    @Test
    fun `浏览数据同步 - 网络不通提示保留重试且行不离队`() = runTest(mainDispatcherRule.testDispatcher) {
        val dao = FakeEventDao()
        dao.seed("00000000-0000-0000-0000-0000000000b1")
        val queue = ViewEventQueue(dao, FakeEventSender(fail = true), clock = FixedEventClock)
        val (vm, _) = newViewModel(queue = queue)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingEvents)

        vm.syncEventsNow()
        advanceUntilIdle()

        assertEquals("网络不通，1 条稍后自动重试", vm.uiState.value.eventSyncNote)
        assertEquals(1, vm.uiState.value.pendingEvents)
        assertEquals(1, dao.rows.size) // 本地优先：失败不丢行
    }

    // ---------- 跨端同步暂存（2026-09-18 用户拍板：App 内暂存中转，免来回导文件） ----------

    @Test
    fun `跨端同步 - 暂存当前库落暂存仓并置KB反馈`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = FakeSyncStagingRepository()
        val (vm, repository) = newViewModel(staging = staging)
        vm.stageForSync()
        // buildExportJson 内 withContext(Dispatchers.Default) 是真实线程跳板（advanceUntilIdle
        // 只推进调度器虚拟时间、不等真实线程池）：轮询等暂存收口（busy 复位=结果已进状态），
        // 防续体在 resetMain 后恢复污染下一用例（自动备份用例同款范式）
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value.stagingBusy && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertEquals(1, staging.stageCalls.size)
        assertEquals(1, repository.exportCalls.size) // 与导出备份共用同一段序列化
        val staged = vm.uiState.value.staged
        assertNotNull(staged)
        assertTrue(staged!!.sourceUrl.isNotEmpty()) // 来源端记档（实现方自取，防导错方向）
        assertTrue(vm.uiState.value.noticeMessage!!.startsWith("已暂存当前库（"))
        assertFalse(vm.uiState.value.stagingBusy)
    }

    @Test
    fun `跨端同步 - 导入暂存走与文件导入同一条确认链路`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = FakeSyncStagingRepository().apply { stagedJson = validBytes.decodeToString() }
        val (vm, repository) = newViewModel(staging = staging)
        vm.importStaged()
        driveIdle()
        // 确认前零出网：与 onFilePicked 同构，只开弹窗
        assertTrue(repository.importCalls.isEmpty())
        val pending = vm.uiState.value.pendingImport
        assertNotNull(pending)
        assertEquals("qimeng_backup.json", pending!!.summary.fileName)
        // 确认后走同一幂等导入
        vm.confirmImport()
        driveIdle()
        assertEquals(1, repository.importCalls.size)
        assertNull(vm.uiState.value.pendingImport)
    }

    @Test
    fun `跨端同步 - 无暂存时导入置错误横幅不出网`() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, repository) = newViewModel()
        vm.importStaged()
        driveIdle()
        assertEquals("暂存数据不存在或已损坏，请重新暂存", vm.uiState.value.errorMessage)
        assertTrue(repository.importCalls.isEmpty())
    }

    // ---------- 自动备份（2026-09-16 用户反馈：prefs 回流 + 立即备份反馈可见） ----------

    @Test
    fun `自动备份 - prefs状态回流进UI状态且开关目录写回`() = runTest(mainDispatcherRule.testDispatcher) {
        val prefs = FakeBackupAutoPrefs()
        val (vm, _) = newViewModel(prefs = prefs)
        advanceUntilIdle() // init observeAutoBackupPrefs
        assertFalse(vm.uiState.value.autoBackupEnabled)
        assertNull(vm.uiState.value.autoBackupDirUri)
        assertEquals(0L, vm.uiState.value.autoBackupLastRunMillis)

        // 写回走同一 DataStore 流（UI 跟随流原值，VM 不另持副本）
        vm.setAutoBackupEnabled(true)
        vm.onAutoBackupDirPicked("content://tree/primary%3Abackup")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.autoBackupEnabled)
        assertEquals("content://tree/primary%3Abackup", vm.uiState.value.autoBackupDirUri)
    }

    @Test
    fun `自动备份 - 未选目录立即备份失败有反馈且busy复位`() = runTest(mainDispatcherRule.testDispatcher) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.writeAutoBackupNow()
        // writeNow 内部 withContext(Dispatchers.IO) 是真实线程跳板（advanceUntilIdle 只推进
        // 调度器虚拟时间、不等真实 IO）：轮询等写回执落位（busy 复位=结果已进状态）。必须在
        // 本用例内等协程收敛——否则续体在 resetMain 之后才恢复，会以 UncaughtExceptions
        // 污染下一用例（与其他直接调 suspend 方法的用例不同，这里无法零等待收口）
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value.autoBackupBusy && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(10)
        }
        advanceUntilIdle()
        // 无目录可写 → runner false → 错误横幅可见（结果反馈不静默）、防重位复位
        assertEquals("自动备份写入失败，请重试（请先选择备份目录）", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.autoBackupBusy)
        assertNull(vm.uiState.value.noticeMessage)
    }

    /** 预置一条未上传事件（幂等键合规，避免触发懒回填路径干扰断言） */
    private suspend fun FakeEventDao.seed(rowId: String) = insert(
        PendingViewEventEntity(
            assetId = "00000000-0000-0000-0000-000000000001", kind = "OPEN",
            startedAt = 1L, durationMs = 0L, sessionId = "s", createdAt = 1L, clientEventId = rowId,
        ),
    )

}

/**
 * [BackupRepository] 测试替身：export 恒回旧版信封、import 回可编程计数的迁移结果，
 * 各端点可编程抛错，调用记录供断言。
 */
private class FakeBackupRepository : BackupRepository {

    var exportError: Exception? = null
    var importError: Exception? = null

    /** POST /import/qimeng-backup 的回执计数与 warnings（测试可编程） */
    var resultWarnings: List<String> = emptyList()

    val exportCalls = mutableListOf<Unit>()
    val importCalls = mutableListOf<LegacyBackupImport>()

    override suspend fun export(): LegacyBackupFile {
        exportCalls.add(Unit)
        exportError?.let { throw it }
        return LegacyBackupFile(
            format = "qimeng_backup",
            schemaVersion = 1,
            appIdentifier = "com.qimeng.media",
            data = LegacyBackupData(),
        )
    }

    override suspend fun import(payload: LegacyBackupImport): LegacyImportResult {
        // 调用记录先于抛错：失败路径也要能断言「端点确实被调用过」
        importCalls.add(payload)
        importError?.let { throw it }
        return LegacyImportResult(
            mediaFilesTotal = 9,
            assetsMatched = 4,
            authorsImported = 3,
            tagsImported = 2,
            eventsReplayed = 5,
            warnings = resultWarnings,
        )
    }
}

/** 内存事件队列 DAO（任务L L5：立即同步/导出用例；语义按接口契约复刻） */
private class FakeEventDao : PendingViewEventDao {
    val rows = mutableListOf<PendingViewEventEntity>()
    private var nextId = 1L

    override suspend fun insert(entity: PendingViewEventEntity): Long {
        val id = nextId++
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun evictBeyondLimit(limit: Int) {
        if (rows.size <= limit) return
        val keep = rows.sortedByDescending { it.id }.take(limit).map { it.id }.toSet()
        rows.removeAll { it.id !in keep }
    }

    override suspend fun selectDue(now: Long, limit: Int): List<PendingViewEventEntity> =
        rows.filter { !it.terminal && it.nextAttemptAt <= now }.sortedBy { it.id }.take(limit)

    override suspend fun deleteByIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun reschedule(id: Long, nextAttemptAt: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(nextAttemptAt = nextAttemptAt)
    }

    override suspend fun markTerminal(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(terminal = true)
    }

    override suspend fun updateClientEventId(id: Long, clientEventId: String) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(clientEventId = clientEventId)
    }

    override suspend fun listAll(): List<PendingViewEventEntity> = rows.sortedBy { it.id }

    override suspend fun countPending(): Int = rows.count { !it.terminal }

    override suspend fun count(): Int = rows.size
}

/** 固定时钟（退避使行不可取件——本组用例只断言提示与计数，0 足够） */
private object FixedEventClock : EventClock {
    override fun now(): Long = 0L
}

/** 可编程发送器：恒 202 或恒 IO 失败（立即同步摘要语义断言用） */
private class FakeEventSender(private val fail: Boolean) : ViewEventSender {
    override suspend fun send(event: PendingViewEventEntity): ViewEventSendResult =
        if (fail) ViewEventSendResult.IoError else ViewEventSendResult.Http(202)
}

/** 自动备份持久化替身（2026-09-16 用户反馈）：内存态 DataStore，写回即发射（UI 跟随流语义） */
private class FakeBackupAutoPrefs : BackupAutoPrefsRepository {
    private val _state = MutableStateFlow(BackupAutoPrefs(enabled = false, dirUri = null, lastRunMillis = 0L))
    override val state: Flow<BackupAutoPrefs> = _state

    override suspend fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }

    override suspend fun setDirUri(uri: String?) {
        _state.value = _state.value.copy(dirUri = uri)
    }

    override suspend fun setLastRunMillis(millis: Long) {
        _state.value = _state.value.copy(lastRunMillis = millis)
    }
}

/** 跨端暂存替身：内存单份暂存（覆盖写语义与实现一致），可预置内容与清空 */
private class FakeSyncStagingRepository : SyncStagingRepository {
    var stagedJson: String? = null
    var stagedSource: String? = null
    val stageCalls = mutableListOf<String>()

    override suspend fun stage(json: String): StagedBackupMeta {
        stageCalls.add(json)
        stagedJson = json
        stagedSource = "http://192.0.2.8:8420"
        return StagedBackupMeta(
            stagedAtMillis = 1_000L,
            sourceUrl = stagedSource!!,
            sizeBytes = json.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    override suspend fun loadStaged(): StagedBackup? = stagedJson?.let {
        StagedBackup(
            meta = StagedBackupMeta(1_000L, stagedSource ?: "", it.length.toLong()),
            json = it,
        )
    }
}
