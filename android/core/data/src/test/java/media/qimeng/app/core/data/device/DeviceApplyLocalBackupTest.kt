package media.qimeng.app.core.data.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceApplyLocalBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `维护目录未设置或缺少所有文件访问时阻止应用`() {
        val noRoot = DeviceApplyLocalBackup.gate(null, hasAllFilesAccess = true)
        assertTrue(noRoot is LocalWriteGate.Blocked)
        assertTrue((noRoot as LocalWriteGate.Blocked).message.contains("资料备份与归档"))

        val noPermission = DeviceApplyLocalBackup.gate("/storage/emulated/0/qimeng", hasAllFilesAccess = false)
        assertTrue(noPermission is LocalWriteGate.Blocked)
        assertTrue((noPermission as LocalWriteGate.Blocked).message.contains("所有文件访问"))

        assertEquals(
            LocalWriteGate.Ready,
            DeviceApplyLocalBackup.gate("/storage/emulated/0/qimeng", hasAllFilesAccess = true),
        )
    }

    @Test
    fun `两代固定文件路径覆盖当前与上次`() {
        val root = File(temp.root, "维护").path
        val files = DeviceApplyLocalBackup.generationFiles(root)
        assertEquals(4, files.size)
        assertTrue(files.any { it.endsWith(File("当前/作者资料/作者总表.txt").path) })
        assertTrue(files.any { it.endsWith(File("当前/备份/影库备份.json").path) })
        assertTrue(files.any { it.endsWith(File("上次/作者资料/作者总表.txt").path) })
        assertTrue(files.any { it.endsWith(File("上次/备份/影库备份.json").path) })
    }

    @Test
    fun `导出后缺少任一代目录视为布局写入失败`() {
        val root = temp.newFolder("root")
        assertEquals(
            listOf(DeviceApplyLocalBackup.CURRENT_DIR, DeviceApplyLocalBackup.PREVIOUS_DIR),
            DeviceApplyLocalBackup.missingGenerationDirectories(root),
        )
        File(root, DeviceApplyLocalBackup.CURRENT_DIR).mkdirs()
        assertEquals(
            listOf(DeviceApplyLocalBackup.PREVIOUS_DIR),
            DeviceApplyLocalBackup.missingGenerationDirectories(root),
        )
        File(root, DeviceApplyLocalBackup.PREVIOUS_DIR).mkdirs()
        assertTrue(DeviceApplyLocalBackup.missingGenerationDirectories(root).isEmpty())
    }

    @Test
    fun `当前备份文件位于当前代备份目录`() {
        val root = temp.newFolder("root2")
        assertEquals(
            File(File(File(root, DeviceApplyLocalBackup.CURRENT_DIR), DeviceApplyLocalBackup.BACKUP_DIR), DeviceApplyLocalBackup.BACKUP_FILE),
            DeviceApplyLocalBackup.currentBackupFile(root),
        )
    }
}
