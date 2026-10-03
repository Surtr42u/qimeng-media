package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.backup.BackupDirAccess
import media.qimeng.app.core.data.backup.BackupFileStatus
import media.qimeng.app.core.data.backup.BackupValidator
import media.qimeng.app.core.data.backup.ValidatedBackupPayload
import media.qimeng.app.core.data.events.EventClock
import media.qimeng.app.core.data.events.PendingViewEventDao
import media.qimeng.app.core.data.events.PendingViewEventEntity
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.events.ViewEventSender
import media.qimeng.app.core.data.events.ViewEventSendResult
import media.qimeng.app.core.data.repository.BackupAutoPrefs
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.model.LegacyImportSummary
import media.qimeng.app.core.testing.MainDispatcherRule
import kotlin.math.roundToInt
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
 * 备份导入/导出状态机锁定（U10-6b）：导出直写备份目录与 KB 反馈/未设目录不出网/导入直读
 * 备份目录与无文件提示/导入取消静默/确认调用/前置校验拒绝/错误态。fake 只在测试源集内
 * （LibraryManageViewModelTest 同款模式）。
 * 注：「用户取消 SAF 选位」类屏幕层语义已随任务S 两卡收敛退役（导入导出不再弹选择器，
 * 屏幕层仅剩目录授权）——VM 层对应的静默语义 = 取消确认（dismissImport）不触发出网，在此锁定。
 * 2026-09-16 用户反馈：自动备份 prefs 回流入锁；「导出未上传」取数端随按钮退役，不再有对应用例。
 * 2026-09-19 任务S 两卡收敛：跨端暂存机制退役（stageForSync/importStaged 及其用例删除）；
 * 导出/导入改直读直写备份目录，新增「未设目录导出提示」「目录无文件导入提示」用例。
 */
class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    /**
     * 等 Default 池真实线程跳板收口（reviewer P1 返工后：导出序列化已离 Main）：advanceUntilIdle
     * 只推进调度器虚拟时间、不等真实线程池——按条件轮询到状态落位，并防续体在 resetMain 后
     * 恢复污染下一用例（导出/自动备份用例同款纪律）。
     */
    private fun kotlinx.coroutines.test.TestScope.awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(10)
        }
        advanceUntilIdle()
    }

    /** 合法信封字节（经 BackupValidator 真·前置校验，mock 不掺进校验链） */
    private val validBytes = """
        {"format": "qimeng_backup", "schemaVersion": 1, "appIdentifier": "com.qimeng.media",
         "data": {"authors": [{"authorId": "A1", "displayName": "作者一"}]}}
    """.trimIndent().toByteArray()

    private fun newViewModel(
        repository: FakeBackupRepository = FakeBackupRepository(),
        queue: ViewEventQueue = ViewEventQueue(FakeEventDao(), FakeEventSender(fail = false), clock = FixedEventClock),
        prefs: BackupAutoPrefsRepository = FakeBackupAutoPrefs(),
        dirAccess: FakeBackupDirAccess = FakeBackupDirAccess(),
    ) = Pair(
        BackupViewModel(
            backupRepository = repository,
            viewEventQueue = queue,
            autoBackupPrefs = prefs,
            autoBackupRunner = newAutoBackupRunner(repository, prefs, dirAccess),
        ).also { driveIdle() },
        repository,
    )

    /**
     * AutoBackupRunner 测试实例（Unsafe 绕过构造器）：其构造器要求注入 Context 依赖链
     * （Hilt @ApplicationContext），JVM 单测无真实对象可给（android.jar stub 的 Context 是
     * 抽象类，本仓库无 mock 依赖）——故绕过构造注入 backupRepository/prefs/dirAccess 三个
     * 纯 Kotlin 依赖，SAF 细节由 fake BackupDirAccess 承载（任务S 起读/写路径均可测）。
     */
    private fun newAutoBackupRunner(
        repository: BackupRepository,
        prefs: BackupAutoPrefsRepository,
        dirAccess: BackupDirAccess,
    ): AutoBackupRunner {
        val injected = mapOf(
            "backupRepository" to repository,
            "prefs" to prefs,
            "dirAccess" to dirAccess,
        )
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val runner = allocate.invoke(unsafe, AutoBackupRunner::class.java) as AutoBackupRunner
        AutoBackupRunner::class.java.declaredFields
            .filter { it.name in injected }
            .forEach { field ->
                field.isAccessible = true
                field.set(runner, injected[field.name])
            }
        return runner
    }

    @Test
    fun `导出成功直写备份目录并刷新上次备份与状态行`() = runTest(mainDispatcherRule.testDispatcher) {
        val dirAccess = FakeBackupDirAccess()
        val (viewModel, repository) = newViewModel(dirAccess = dirAccess)
        viewModel.onAutoBackupDirPicked("content://tree/primary%3Abackup")
        advanceUntilIdle()
        viewModel.exportToBackupDir()
        // writeToDir 内 withContext(Dispatchers.Default) 是真实线程跳板（序列化段；advanceUntilIdle
        // 只推进调度器虚拟时间、不等真实线程池）：轮询等结果落位。必须在本用例内等协程收敛——
        // 否则续体在 resetMain 之后才恢复，会以 UncaughtExceptions 污染下一用例
        awaitUntil { viewModel.uiState.value.noticeMessage != null }
        advanceUntilIdle()
        // 内容来自 GET /export/qimeng-backup 且已序列化为旧版信封 JSON 直写备份目录
        assertEquals(1, repository.exportCalls.size)
        val written = dirAccess.writtenJsons.single()
        assertTrue(written.contains("\"format\":\"qimeng_backup\""))
        // Web (sizeBytes/1024).toFixed(0) KB 逐字（预期按实写字节数现算，不硬编码）
        val expectedKb = (written.toByteArray(Charsets.UTF_8).size / 1024.0).roundToInt()
        assertEquals("已导出 qimeng_backup.json（$expectedKb KB）", viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.exporting)
        assertNull(viewModel.uiState.value.errorMessage)
        // 手动导出刷新「上次备份时间」（任务S 冻结语义）+ 状态行随 prefs 回流翻新
        assertTrue(viewModel.uiState.value.autoBackupLastRunMillis > 0)
        val status = viewModel.uiState.value.backupFileStatus
        assertNotNull(status)
        assertEquals(written.toByteArray(Charsets.UTF_8).size.toLong(), status!!.sizeBytes)
    }

    @Test
    fun `未设备份目录导出提示且不出网`() {
        val (viewModel, repository) = newViewModel()
        viewModel.exportToBackupDir()
        // 目录未设 → 冻结文案横幅；导出对象在备份目录里，此时连 GET /export 都不发
        assertEquals("请先在自动备份中设置备份目录", viewModel.uiState.value.errorMessage)
        assertEquals(0, repository.exportCalls.size)
        assertFalse(viewModel.uiState.value.exporting)
    }

    @Test
    fun `目录无文件导入提示且不出网`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, repository) = newViewModel()
        viewModel.onAutoBackupDirPicked("content://tree/primary%3Abackup")
        advanceUntilIdle()
        viewModel.importFromBackupDir()
        advanceUntilIdle()
        // 目录里没有备份文件 → 冻结文案横幅（提示先在源端导出）；零出网零弹窗
        assertEquals("备份目录还没有备份文件，请先在源端导出", viewModel.uiState.value.errorMessage)
        assertTrue(repository.importCalls.isEmpty())
        assertNull(viewModel.uiState.value.pendingImport)
    }

    @Test
    fun `目录文件读取失败置读取横幅不出网`() = runTest(mainDispatcherRule.testDispatcher) {
        val dirAccess = FakeBackupDirAccess().apply {
            file = validBytes
            readError = true
        }
        val (viewModel, repository) = newViewModel(dirAccess = dirAccess)
        viewModel.onAutoBackupDirPicked("content://tree/primary%3Abackup")
        advanceUntilIdle()
        viewModel.importFromBackupDir()
        advanceUntilIdle()
        // 状态查得到但字节读不出（授权被回收/IO 异常）→ 与「无文件」区分的可读文案
        assertEquals("读取文件失败，请重试", viewModel.uiState.value.errorMessage)
        assertTrue(repository.importCalls.isEmpty())
    }

    @Test
    fun `目录有文件导入走校验确认链路`() = runTest(mainDispatcherRule.testDispatcher) {
        val dirAccess = FakeBackupDirAccess().apply { file = validBytes }
        val (viewModel, repository) = newViewModel(dirAccess = dirAccess)
        viewModel.onAutoBackupDirPicked("content://tree/primary%3Abackup")
        advanceUntilIdle()
        viewModel.importFromBackupDir()
        // 校验段已下 Default 线程（2026-09-20 审查：与导出序列化段同款真实线程跳板，
        // advanceUntilIdle 不等真实线程池）——按导出用例同款纪律轮询到弹窗载荷落位
        awaitUntil { viewModel.uiState.value.pendingImport != null }
        // 确认前零出网（Web 同构：pending 非 null 只开弹窗，确认弹窗保留）
        assertTrue(repository.importCalls.isEmpty())
        val pending = viewModel.uiState.value.pendingImport
        assertNotNull(pending)
        assertEquals("qimeng_backup.json", pending!!.summary.fileName)
        assertEquals(1, pending.summary.authors)
        // 确认后走同一幂等导入
        viewModel.confirmImport()
        advanceUntilIdle()
        assertEquals(1, repository.importCalls.size)
        assertNull(viewModel.uiState.value.pendingImport)
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
        // Web confirmImport 逐字语义：确认后清 pending → POST 载荷（载荷为不透明句柄，
        // 2026-10-03 撤 :sdk 依赖批起 format 字段收口 core:data，此处只锁调用事实）
        assertEquals(1, repository.importCalls.size)
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

    // ---------- 自动备份（2026-09-16 用户反馈：prefs 回流；任务S：手动触发并入卡1「导出」） ----------

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

    /** 预置一条未上传事件（幂等键合规，避免触发懒回填路径干扰断言） */
    private suspend fun FakeEventDao.seed(rowId: String) = insert(
        PendingViewEventEntity(
            assetId = "00000000-0000-0000-0000-000000000001", kind = "OPEN",
            startedAt = 1L, durationMs = 0L, sessionId = "s", createdAt = 1L, clientEventId = rowId,
        ),
    )

}

/**
 * [BackupRepository] 测试替身：exportJson 恒回旧版信封 JSON、importBackup 回可编程
 * 计数的迁移结果，各端点可编程抛错，调用记录供断言（2026-10-03 撤 :sdk 依赖批随
 * 接口签名域类型化重写；语义与原 Legacy* 生成物构造版一致）。
 */
private class FakeBackupRepository : BackupRepository {

    var exportError: Exception? = null
    var importError: Exception? = null

    /** POST /import/qimeng-backup 的回执计数与 warnings（测试可编程） */
    var resultWarnings: List<String> = emptyList()

    val exportCalls = mutableListOf<Unit>()
    val importCalls = mutableListOf<ValidatedBackupPayload>()

    /** GET /export 的信封（moshi 紧凑序列化同款无空格格式，写盘断言子串口径不变） */
    private val exportEnvelopeJson =
        """{"format":"qimeng_backup","schemaVersion":1,"appIdentifier":"com.qimeng.media","data":{}}"""

    override suspend fun exportJson(): String {
        exportCalls.add(Unit)
        exportError?.let { throw it }
        return exportEnvelopeJson
    }

    override suspend fun importBackup(payload: ValidatedBackupPayload): LegacyImportSummary {
        // 调用记录先于抛错：失败路径也要能断言「端点确实被调用过」
        importCalls.add(payload)
        importError?.let { throw it }
        return LegacyImportSummary(
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

/** 备份目录访问替身（任务S 两卡收敛）：内存单文件（null=无文件），读写记录与可编程读失败 */
private class FakeBackupDirAccess : BackupDirAccess {
    var file: ByteArray? = null
    var readError: Boolean = false
    val writtenJsons = mutableListOf<String>()

    override suspend fun fileStatus(dirUri: String): BackupFileStatus? =
        file?.let { BackupFileStatus(sizeBytes = it.size.toLong(), lastModifiedMillis = 1_000L) }

    override suspend fun readBytes(dirUri: String): ByteArray? {
        if (readError) return null
        return file
    }

    override suspend fun writeBytes(dirUri: String, json: String): Long? {
        writtenJsons.add(json)
        val bytes = json.toByteArray(Charsets.UTF_8)
        file = bytes
        return bytes.size.toLong()
    }
}
