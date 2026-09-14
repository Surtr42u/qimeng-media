package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.sdk.models.LegacyBackupData
import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import media.qimeng.sdk.models.LegacyImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.runTest

/**
 * 备份导入/导出状态机锁定（U10-6b）：导出成功与 KB 反馈/导出防重/导入取消静默/确认调用/
 * 前置校验拒绝/错误态。fake 只在测试源集内（LibraryManageViewModelTest 同款模式）。
 * 注：导出 SAF 选位「用户取消（uri=null）」发生在屏幕层 launcher 回调（SettingsScreen
 * 先例），VM 层对应的静默语义 = 取消确认（dismissImport）不触发出网，在此锁定。
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

    private fun newViewModel(repository: FakeBackupRepository = FakeBackupRepository()) =
        Pair(BackupViewModel(repository).also { driveIdle() }, repository)

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
