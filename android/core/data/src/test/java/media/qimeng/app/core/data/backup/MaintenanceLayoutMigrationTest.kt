package media.qimeng.app.core.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 旧三代同目录布局 → 两代目录的只读核对与复制迁移（2026-11-07 R7 批；JVM 临时目录）。
 * 契约：只读核对不改文件；迁移只复制不删除、不覆盖已有不同内容、上上次只列可清理。
 */
class MaintenanceLayoutMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeWithChecksum(directory: File, name: String, content: String) {
        File(directory, name).writeText(content, Charsets.UTF_8)
        File(directory, "$name.sha256").writeText(
            maintenanceSha256(content.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8,
        )
    }

    private fun legacyAuthors(root: File): File = File(root, AUTHORS_DIR).apply { mkdirs() }
    private fun legacyBackups(root: File): File = File(root, BACKUP_DIR).apply { mkdirs() }
    private fun currentAuthors(root: File): File = File(File(root, CURRENT_GENERATION_DIR), AUTHORS_DIR)
    private fun previousAuthors(root: File): File = File(File(root, PREVIOUS_GENERATION_DIR), AUTHORS_DIR)
    private fun currentBackups(root: File): File = File(File(root, CURRENT_GENERATION_DIR), BACKUP_DIR)
    private fun previousBackups(root: File): File = File(File(root, PREVIOUS_GENERATION_DIR), BACKUP_DIR)

    private val backupName: String get() = BackupFileKind.CURRENT.fileName

    /** 合成旧三代布局：作者总表三代 + 无摘要的伴随资料，备份两代。 */
    private fun seedLegacy(root: File) {
        val authors = legacyAuthors(root)
        writeWithChecksum(authors, AUTHOR_MASTER_FILE, "主表最新")
        writeWithChecksum(authors, "作者总表（上次）.txt", "主表上次")
        writeWithChecksum(authors, "作者总表（上上次）.txt", "主表上上次")
        // 旧版本对阅读版/索引不写摘要：迁移时应接受并补算。
        File(authors, AUTHOR_REPORT_FILE).writeText("报告最新", Charsets.UTF_8)
        File(authors, AUTHOR_INDEX_FILE).writeText("""["作者总表.txt"]""", Charsets.UTF_8)
        val backups = legacyBackups(root)
        writeWithChecksum(backups, backupName, """{"format":"qimeng_backup","data":{"v":1}}""")
        writeWithChecksum(backups, "影库备份（上次）.json", """{"format":"qimeng_backup","data":{"v":0}}""")
    }

    private fun assertGenerationChecksum(directory: File, name: String, content: String) {
        assertEquals(content, File(directory, name).readText(Charsets.UTF_8))
        assertEquals(
            maintenanceSha256(content.toByteArray(Charsets.UTF_8)),
            File(directory, "$name.sha256").readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `只读核对不创建两代目录也不修改旧文件`() {
        val root = tmp.newFolder()
        seedLegacy(root)
        val report = inspectMaintenanceLayout(root)
        assertTrue(report.needsMigration)
        assertTrue(report.current.isEmpty())
        assertTrue(report.previous.isEmpty())
        assertFalse(File(root, CURRENT_GENERATION_DIR).exists())
        assertFalse(File(root, PREVIOUS_GENERATION_DIR).exists())
        assertEquals(7, report.legacy.size)
        assertTrue(report.migratable.any { it.role == MaintenanceFileRole.LEGACY_LATEST })
        assertTrue(report.migratable.any { it.role == MaintenanceFileRole.LEGACY_PREVIOUS })
        assertTrue(report.cleanable.any { it.role == MaintenanceFileRole.LEGACY_OLDER })
        // 旧文件原样保留。
        assertEquals("主表最新", File(legacyAuthors(root), AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
    }

    @Test
    fun `复制生成当前与上次两代且保留旧文件不复制上上次`() {
        val root = tmp.newFolder()
        seedLegacy(root)
        val result = copyLegacyToGenerations(root)
        assertTrue(result.copied.isNotEmpty())
        // 最新 → 当前，旧（上次）→ 上次；均带重算摘要。
        assertGenerationChecksum(currentAuthors(root), AUTHOR_MASTER_FILE, "主表最新")
        assertGenerationChecksum(currentAuthors(root), AUTHOR_REPORT_FILE, "报告最新")
        assertGenerationChecksum(currentAuthors(root), AUTHOR_INDEX_FILE, """["作者总表.txt"]""")
        assertGenerationChecksum(currentBackups(root), backupName, """{"format":"qimeng_backup","data":{"v":1}}""")
        assertGenerationChecksum(previousAuthors(root), AUTHOR_MASTER_FILE, "主表上次")
        assertGenerationChecksum(previousBackups(root), backupName, """{"format":"qimeng_backup","data":{"v":0}}""")
        // 上上次不复制，仍在旧目录；旧最新文件也保留（只复制不移动）。
        assertFalse(File(currentAuthors(root), "作者总表（上上次）.txt").exists())
        assertTrue(File(legacyAuthors(root), "作者总表（上上次）.txt").isFile)
        assertTrue(File(legacyAuthors(root), AUTHOR_MASTER_FILE).isFile)
        assertTrue(File(legacyBackups(root), backupName).isFile)
        assertTrue(result.skipped.any { it.contains("上上次") })
    }

    @Test
    fun `目标已有不同内容时不覆盖并报告冲突`() {
        val root = tmp.newFolder()
        seedLegacy(root)
        val current = currentAuthors(root).apply { mkdirs() }
        writeWithChecksum(current, AUTHOR_MASTER_FILE, "两代布局已有内容")
        val report = inspectMaintenanceLayout(root)
        val conflict = report.legacy.first {
            it.role == MaintenanceFileRole.LEGACY_LATEST && it.location.endsWith(AUTHOR_MASTER_FILE)
        }
        assertEquals(MaintenanceMigrationAction.CONFLICT, conflict.action)
        assertTrue(report.warnings.any { it.contains("内容不同") })
        val result = copyLegacyToGenerations(root)
        assertEquals("两代布局已有内容", File(current, AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
        assertGenerationChecksum(current, AUTHOR_MASTER_FILE, "两代布局已有内容")
        assertEquals("主表最新", File(legacyAuthors(root), AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
        assertTrue(result.warnings.any { it.contains("内容不同") })
    }

    @Test
    fun `摘要不符的旧文件保留不迁移`() {
        val root = tmp.newFolder()
        seedLegacy(root)
        val authors = legacyAuthors(root)
        // 外部改写了正文但摘要还是旧的。
        File(authors, AUTHOR_MASTER_FILE).writeText("被外部编辑", Charsets.UTF_8)
        val report = inspectMaintenanceLayout(root)
        val tampered = report.legacy.first {
            it.role == MaintenanceFileRole.LEGACY_LATEST && it.location.endsWith(AUTHOR_MASTER_FILE)
        }
        assertEquals(MaintenanceFileState.CHECKSUM_MISMATCH, tampered.state)
        assertEquals(MaintenanceMigrationAction.KEEP, tampered.action)
        copyLegacyToGenerations(root)
        assertFalse(File(currentAuthors(root), AUTHOR_MASTER_FILE).exists())
        assertEquals("被外部编辑", File(authors, AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
    }

    @Test
    fun `重复调用幂等且不改动旧文件`() {
        val root = tmp.newFolder()
        seedLegacy(root)
        val first = copyLegacyToGenerations(root)
        assertTrue(first.copied.isNotEmpty())
        val currentContent = File(currentAuthors(root), AUTHOR_MASTER_FILE).readText(Charsets.UTF_8)
        val second = copyLegacyToGenerations(root)
        assertTrue(second.copied.isEmpty())
        assertEquals(currentContent, File(currentAuthors(root), AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
        assertTrue(second.skipped.isNotEmpty())
    }

    @Test
    fun `没有旧文件时报告为空且不创建目录`() {
        val root = tmp.newFolder()
        val report = inspectMaintenanceLayout(root)
        assertFalse(report.needsMigration)
        assertTrue(report.legacy.isEmpty())
        assertTrue(report.warnings.isEmpty())
        val result = copyLegacyToGenerations(root)
        assertFalse(result.changed)
        assertFalse(File(root, CURRENT_GENERATION_DIR).exists())
    }
}
