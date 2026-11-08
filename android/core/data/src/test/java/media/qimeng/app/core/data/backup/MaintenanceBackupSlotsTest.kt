package media.qimeng.app.core.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份槽解析/读取单测（2026-11-07 task-13）：锁定两代布局「上次」跨目录、旧 flat3
 * 旧名文件只读回退、摘要核对与大小上限；不依赖 Android 设备/真实存储。
 */
class MaintenanceBackupSlotsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeSlot(directory: File, name: String, content: String, checksum: Boolean = true) {
        directory.mkdirs()
        File(directory, name).writeText(content, Charsets.UTF_8)
        if (checksum) {
            File(directory, "$name.sha256").writeText(
                maintenanceSha256(content.toByteArray(Charsets.UTF_8)),
                Charsets.UTF_8,
            )
        }
    }

    private fun currentBackup(root: File) = File(File(File(root, CURRENT_GENERATION_DIR), BACKUP_DIR), BackupFileKind.CURRENT.fileName)
    private fun previousBackup(root: File) = File(File(File(root, PREVIOUS_GENERATION_DIR), BACKUP_DIR), BackupFileKind.CURRENT.fileName)
    private fun legacyBackup(root: File, kind: BackupFileKind) = File(File(root, BACKUP_DIR), kind.fileName)

    @Test
    fun `当前槽两代优先且缺失时回退旧布局最新份`() {
        val root = tmp.newFolder()
        writeSlot(legacyBackup(root, BackupFileKind.CURRENT).parentFile, BackupFileKind.CURRENT.fileName, "legacy-current")
        // 两代当前文件尚未生成（升级后首次导出前）：回退旧布局最新一份，避免读空。
        assertEquals(legacyBackup(root, BackupFileKind.CURRENT), resolveMaintenanceBackupSlot(root, BackupFileKind.CURRENT))
        for (kind in listOf(BackupFileKind.MANUAL, BackupFileKind.AUTOMATIC)) {
            assertEquals(legacyBackup(root, BackupFileKind.CURRENT), resolveMaintenanceBackupSlot(root, kind))
        }
        // 两代当前存在后优先读当前，旧文件保留但不再作为当前来源。
        writeSlot(currentBackup(root).parentFile, BackupFileKind.CURRENT.fileName, "current")
        assertEquals(currentBackup(root), resolveMaintenanceBackupSlot(root, BackupFileKind.CURRENT))
        assertEquals(currentBackup(root), resolveMaintenanceBackupSlot(root, BackupFileKind.MANUAL))
    }

    @Test
    fun `上次槽读独立上次目录，缺失时回退旧 flat3 的（上次）文件`() {
        val root = tmp.newFolder()
        writeSlot(currentBackup(root).parentFile, BackupFileKind.CURRENT.fileName, "current")
        assertNull(resolveMaintenanceBackupSlot(root, BackupFileKind.PREVIOUS))
        // 旧三代布局：历史份是同目录旧名文件，升级后保留、仍可只读恢复。
        writeSlot(legacyBackup(root, BackupFileKind.PREVIOUS).parentFile, BackupFileKind.PREVIOUS.fileName, "legacy-previous")
        assertEquals(
            legacyBackup(root, BackupFileKind.PREVIOUS),
            resolveMaintenanceBackupSlot(root, BackupFileKind.PREVIOUS),
        )
        // 两代上次目录存在时优先；两处文件名不同是修复点（上次/备份/影库备份.json）。
        writeSlot(previousBackup(root).parentFile, BackupFileKind.CURRENT.fileName, "previous")
        assertEquals(previousBackup(root), resolveMaintenanceBackupSlot(root, BackupFileKind.PREVIOUS))
    }

    @Test
    fun `上上次只认旧 flat3 旧名，两代布局没有第三代`() {
        val root = tmp.newFolder()
        writeSlot(currentBackup(root).parentFile, BackupFileKind.CURRENT.fileName, "current")
        writeSlot(previousBackup(root).parentFile, BackupFileKind.CURRENT.fileName, "previous")
        assertNull(resolveMaintenanceBackupSlot(root, BackupFileKind.OLDER))
        writeSlot(legacyBackup(root, BackupFileKind.OLDER).parentFile, BackupFileKind.OLDER.fileName, "legacy-older")
        assertEquals(legacyBackup(root, BackupFileKind.OLDER), resolveMaintenanceBackupSlot(root, BackupFileKind.OLDER))
    }

    @Test
    fun `导入前保护槽不是恢复入口`() {
        assertNull(resolveMaintenanceBackupSlot(tmp.newFolder(), BackupFileKind.PRE_IMPORT))
    }

    @Test
    fun `摘要一致才读取，缺摘要兼容旧文件，摘要不符拒绝`() {
        val root = tmp.newFolder()
        val directory = currentBackup(root).parentFile
        writeSlot(directory, BackupFileKind.CURRENT.fileName, "backup-body")
        val file = currentBackup(root)
        assertTrue("backup-body".toByteArray(Charsets.UTF_8).contentEquals(readMaintenanceBackupSlot(file)!!))
        // 旧文件可能没有摘要（兼容放行，真校验在 v2 结构闸）。
        File(directory, "${file.name}.sha256").delete()
        assertTrue(readMaintenanceBackupSlot(file) != null)
        // 摘要与正文不符（外部替换/写坏）：拒绝读取，不把可疑文件交给导入链。
        File(directory, "${file.name}.sha256").writeText("deadbeef", Charsets.UTF_8)
        assertNull(readMaintenanceBackupSlot(file))
    }

    @Test
    fun `超过上限不读取`() {
        val root = tmp.newFolder()
        val directory = currentBackup(root).parentFile
        writeSlot(directory, BackupFileKind.CURRENT.fileName, "0123456789", checksum = false)
        val file = currentBackup(root)
        assertNull(readMaintenanceBackupSlot(file, maxBytes = 4))
        assertTrue(readMaintenanceBackupSlot(file, maxBytes = 10) != null)
    }
}
