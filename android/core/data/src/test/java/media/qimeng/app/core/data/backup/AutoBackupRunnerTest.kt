package media.qimeng.app.core.data.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.BackupAutoPrefs
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.model.LegacyImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史备份读取接线单测（2026-11-07 task-13）：维持久化目录先由维护仓库解析两代/旧 flat3
 * 真实文件（上次代在独立目录），非托管 SAF 目录才回退固定名直读；状态查询同样优先解析。
 * 纯 JVM：BackupDirAccess/维护仓库均为替身，不触 Android 存储。
 */
class AutoBackupRunnerTest {

    private class FakePrefs(initial: BackupAutoPrefs = BackupAutoPrefs(enabled = true, dirUri = null, lastRunMillis = 0L)) :
        BackupAutoPrefsRepository {
        private val stateFlow = MutableStateFlow(initial)
        override val state: Flow<BackupAutoPrefs> = stateFlow
        override suspend fun setEnabled(enabled: Boolean) { stateFlow.value = stateFlow.value.copy(enabled = enabled) }
        override suspend fun setDirUri(uri: String?) { stateFlow.value = stateFlow.value.copy(dirUri = uri) }
        override suspend fun setLastRunMillis(millis: Long) { stateFlow.value = stateFlow.value.copy(lastRunMillis = millis) }
    }

    private class FakeDirAccess : BackupDirAccess {
        val files = mutableMapOf<BackupFileKind, ByteArray>()
        val statuses = mutableMapOf<BackupFileKind, BackupFileStatus>()
        var reads = 0

        override suspend fun writeBytes(dirUri: String, json: String, kind: BackupFileKind): Long? = null
        override suspend fun readBytes(dirUri: String, kind: BackupFileKind): ByteArray? {
            reads += 1
            return files[kind]
        }
        override suspend fun fileStatus(dirUri: String, kind: BackupFileKind): BackupFileStatus? = statuses[kind]
    }

    private class FakeMaintenance(
        private val managedFiles: Map<BackupFileKind, ManagedBackupFile> = emptyMap(),
        private val managedStatuses: Map<BackupFileKind, BackupFileStatus> = emptyMap(),
    ) : MaintenanceBackupWriter {
        override suspend fun writeIfManaged(dirUri: String, kind: BackupFileKind): Long? = null
        override suspend fun readManagedBackup(dirUri: String, kind: BackupFileKind): ManagedBackupFile? =
            managedFiles[kind]
        override suspend fun managedBackupStatus(dirUri: String, kind: BackupFileKind): BackupFileStatus? =
            managedStatuses[kind]
    }

    private class FakeBackupRepository : BackupRepository {
        override suspend fun exportJson(): String = "{}"
        override suspend fun importBackup(payload: ValidatedBackupPayload): LegacyImportSummary =
            LegacyImportSummary(mediaFilesTotal = 0, assetsMatched = 0, authorsImported = 0, tagsImported = 0, eventsReplayed = 0)
    }

    private fun runner(
        prefs: FakePrefs,
        dirAccess: FakeDirAccess,
        maintenance: FakeMaintenance,
    ): AutoBackupRunner = AutoBackupRunner(FakeBackupRepository(), prefs, dirAccess, maintenance)

    @Test
    fun `历史槽优先走维护目录解析并返回真实文件名`() = runTest {
        val prefs = FakePrefs().apply { setDirUri("file:///synthetic/当前/备份") }
        val dirAccess = FakeDirAccess()
        val managedBytes = """{"format":"qimeng_backup","schemaVersion":2,"data":{"viewEvents":[]}}""".toByteArray()
        // 两代布局上次代真实文件是 上次/备份/影库备份.json，不是「影库备份（上次）.json」。
        val maintenance = FakeMaintenance(
            managedFiles = mapOf(
                BackupFileKind.PREVIOUS to ManagedBackupFile(BackupFileKind.CURRENT.fileName, managedBytes),
            ),
        )
        val backup = runner(prefs, dirAccess, maintenance).readBackup(BackupFileKind.PREVIOUS)
        assertEquals(BackupFileKind.CURRENT.fileName, backup!!.fileName)
        assertTrue(managedBytes.contentEquals(backup.bytes))
        // 维护目录命中后不再回退目录直读（旧路径拼接修复的锁定点）。
        assertEquals(0, dirAccess.reads)
    }

    @Test
    fun `非托管目录回退固定名直读并保留槽别名`() = runTest {
        val prefs = FakePrefs().apply { setDirUri("content://tree/primary%3Abackup") }
        val bytes = "legacy".toByteArray()
        val dirAccess = FakeDirAccess().apply { files[BackupFileKind.MANUAL] = bytes }
        val runner = runner(prefs, dirAccess, FakeMaintenance())
        val backup = runner.readBackup(BackupFileKind.MANUAL)
        assertEquals(BackupFileKind.MANUAL.fileName, backup!!.fileName)
        assertTrue(bytes.contentEquals(backup.bytes))
        assertEquals(1, dirAccess.reads)
        // 兼容入口语义不变。
        assertTrue(bytes.contentEquals(runner.readBackupBytes(BackupFileKind.MANUAL)!!))
    }

    @Test
    fun `状态查询优先维护目录，缺失时回退目录固定名`() = runTest {
        val prefs = FakePrefs().apply { setDirUri("file:///synthetic/当前/备份") }
        val managedStatus = BackupFileStatus(sizeBytes = 123, lastModifiedMillis = 456)
        val dirAccess = FakeDirAccess().apply {
            statuses[BackupFileKind.OLDER] = BackupFileStatus(sizeBytes = 9, lastModifiedMillis = 9)
        }
        val runner = runner(
            prefs, dirAccess,
            FakeMaintenance(managedStatuses = mapOf(BackupFileKind.PREVIOUS to managedStatus)),
        )
        assertEquals(managedStatus, runner.readFileStatus(BackupFileKind.PREVIOUS))
        // 维护目录没有该槽时回退直读（旧 SAF 目录/旧 flat3 同目录旧名的兜底由 dirAccess 承担）。
        assertEquals(9L, runner.readFileStatus(BackupFileKind.OLDER)!!.sizeBytes)
    }

    @Test
    fun `未设置备份目录时读取返回 null`() = runTest {
        val prefs = FakePrefs()
        val runner = runner(prefs, FakeDirAccess(), FakeMaintenance())
        assertNull(runner.readBackup(BackupFileKind.MANUAL))
        assertNull(runner.readFileStatus(BackupFileKind.PREVIOUS))
    }
}
