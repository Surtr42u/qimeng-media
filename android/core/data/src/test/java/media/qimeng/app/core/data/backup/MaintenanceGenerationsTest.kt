package media.qimeng.app.core.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 两代维护目录轮换语义锁定（2026-11-07 R7 批；JVM 临时目录，不依赖 Android 设备）：
 * 首次生成、内容变化轮换、相同数据不轮换、摘要不符/外来文件拒写、原子写中断恢复、
 * 旧三代文件共存不影响两代写入。Android 授权/镜像/偏好部分在云端 CI 编译验证。
 */
class MaintenanceGenerationsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun authorsDir(): Pair<File, File> {
        val root = tmp.newFolder()
        return File(root, "当前/作者资料") to File(root, "上次/作者资料")
    }

    private fun authors(
        master: String,
        report: String = "作者资料A",
        index: String = """["作者总表.txt"]""",
    ): List<MaintenanceGenerationFile> = listOf(
        MaintenanceGenerationFile(AUTHOR_MASTER_FILE, master),
        MaintenanceGenerationFile(AUTHOR_REPORT_FILE, report),
        MaintenanceGenerationFile(AUTHOR_INDEX_FILE, index),
    )

    private fun assertChecksum(directory: File, name: String, content: String) {
        val file = File(directory, name)
        assertTrue("$name 应存在", file.isFile)
        assertEquals(content, file.readText(Charsets.UTF_8))
        assertEquals(
            maintenanceSha256(content.toByteArray(Charsets.UTF_8)),
            File(directory, "$name.sha256").readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `首次生成当前代且每份文件独立摘要`() {
        val (current, previous) = authorsDir()
        val rotated = writeGenerationFiles(current, previous, authors("主表A"), AUTHOR_MASTER_FILE)
        assertTrue(rotated)
        authors("主表A").forEach { assertChecksum(current, it.name, it.content) }
        // 首次没有旧代，不生成上次目录内容。
        assertFalse(File(previous, AUTHOR_MASTER_FILE).exists())
    }

    @Test
    fun `内容变化先把当前整代提交为上次再写新当前`() {
        val (current, previous) = authorsDir()
        writeGenerationFiles(current, previous, authors("主表A", "报告A"), AUTHOR_MASTER_FILE)
        val rotated = writeGenerationFiles(current, previous, authors("主表B", "报告B"), AUTHOR_MASTER_FILE)
        assertTrue(rotated)
        // 上次 = 旧当前整代（主表与伴随资料都在，各自带摘要）。
        assertChecksum(previous, AUTHOR_MASTER_FILE, "主表A")
        assertChecksum(previous, AUTHOR_REPORT_FILE, "报告A")
        assertChecksum(previous, AUTHOR_INDEX_FILE, """["作者总表.txt"]""")
        // 当前 = 新整代。
        assertChecksum(current, AUTHOR_MASTER_FILE, "主表B")
        assertChecksum(current, AUTHOR_REPORT_FILE, "报告B")
    }

    @Test
    fun `相同数据重导出只刷新当前不挤掉上次`() {
        val (current, previous) = authorsDir()
        writeGenerationFiles(current, previous, authors("主表A", "报告1"), AUTHOR_MASTER_FILE)
        writeGenerationFiles(current, previous, authors("主表B", "报告1"), AUTHOR_MASTER_FILE)
        // B 换成与 B 相同主表、仅伴随资料变化的重复导出。
        val rotated = writeGenerationFiles(current, previous, authors("主表B", "报告2"), AUTHOR_MASTER_FILE)
        assertFalse(rotated)
        // 上次仍是 A 代，没有被 B 的重复导出挤掉。
        assertChecksum(previous, AUTHOR_MASTER_FILE, "主表A")
        assertChecksum(previous, AUTHOR_REPORT_FILE, "报告1")
        // 当前刷新为新伴随资料。
        assertChecksum(current, AUTHOR_REPORT_FILE, "报告2")
        assertChecksum(current, AUTHOR_MASTER_FILE, "主表B")
    }

    @Test
    fun `备份相同数据忽略信封导出时间不轮换`() {
        val root = tmp.newFolder()
        val current = File(root, "当前/备份")
        val previous = File(root, "上次/备份")
        val name = BackupFileKind.CURRENT.fileName
        val sameData: (String, String) -> Boolean = { old, new ->
            val oldData = maintenanceBackupDataSignature(old)
            oldData != null && oldData == maintenanceBackupDataSignature(new)
        }
        val first = """{"format":"qimeng_backup","schemaVersion":2,"exportedAtMillis":1,"data":{"authors":[],"viewEvents":[]}}"""
        val second = """{"format":"qimeng_backup","schemaVersion":2,"exportedAtMillis":2,"data":{"authors":[],"viewEvents":[]}}"""
        writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, first)), name, sameData)
        val rotated = writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, second)), name, sameData)
        assertFalse(rotated)
        assertChecksum(current, name, second)
        assertFalse(File(previous, name).exists())
        // 数据段真正变化才轮换。
        val changed = """{"format":"qimeng_backup","schemaVersion":2,"exportedAtMillis":3,"data":{"authors":[{"authorId":"A1"}],"viewEvents":[]}}"""
        assertTrue(writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, changed)), name, sameData))
        assertChecksum(previous, name, second)
        assertChecksum(current, name, changed)
    }

    @Test
    fun `解析失败的旧备份按数据变化处理并保留为上次`() {
        val root = tmp.newFolder()
        val current = File(root, "当前/备份")
        val previous = File(root, "上次/备份")
        val name = BackupFileKind.CURRENT.fileName
        val sameData: (String, String) -> Boolean = { old, new ->
            val oldData = maintenanceBackupDataSignature(old)
            oldData != null && oldData == maintenanceBackupDataSignature(new)
        }
        writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, "损坏的旧文件")), name, sameData)
        val valid = """{"format":"qimeng_backup","schemaVersion":2,"data":{"viewEvents":[]}}"""
        assertTrue(writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, valid)), name, sameData))
        assertEquals("损坏的旧文件", File(previous, name).readText(Charsets.UTF_8))
        assertChecksum(current, name, valid)
    }

    @Test
    fun `自动备份摘要不符拒绝覆盖并保留现场`() {
        val root = tmp.newFolder()
        val current = File(root, "当前/备份")
        val previous = File(root, "上次/备份")
        val name = BackupFileKind.CURRENT.fileName
        val first = """{"format":"qimeng_backup","schemaVersion":2,"data":{"v":1}}"""
        writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, first)), name, protectExternal = true)
        // 模拟外部替换：正文改了但摘要还是旧的。
        File(current, name).writeText("被外部替换", Charsets.UTF_8)
        val next = """{"format":"qimeng_backup","schemaVersion":2,"data":{"v":2}}"""
        val failure = assertThrows(IllegalStateException::class.java) {
            writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, next)), name, protectExternal = true)
        }
        assertTrue(failure.message!!.contains("外部"))
        // 现场保留：正文、摘要、上次目录都未被改写。
        assertEquals("被外部替换", File(current, name).readText(Charsets.UTF_8))
        assertFalse(File(previous, name).exists())
    }

    @Test
    fun `上次目标摘要不符拒绝覆盖并保留现场`() {
        val (current, previous) = authorsDir()
        writeGenerationFiles(current, previous, authors("主表A"), AUTHOR_MASTER_FILE)
        // 模拟上次目录被外部写入且摘要对不上。
        previous.mkdirs()
        File(previous, AUTHOR_MASTER_FILE).writeText("来历不明", Charsets.UTF_8)
        File(previous, "$AUTHOR_MASTER_FILE.sha256").writeText("deadbeef", Charsets.UTF_8)
        val failure = assertThrows(IllegalStateException::class.java) {
            writeGenerationFiles(current, previous, authors("主表B"), AUTHOR_MASTER_FILE)
        }
        assertTrue(failure.message!!.contains("上次目录"))
        assertEquals("来历不明", File(previous, AUTHOR_MASTER_FILE).readText(Charsets.UTF_8))
        // 当前代保持旧内容，没有被半途覆盖。
        assertChecksum(current, AUTHOR_MASTER_FILE, "主表A")
    }

    @Test
    fun `残留临时文件被清理且不阻塞写入`() {
        val (current, previous) = authorsDir()
        current.mkdirs()
        val stale = File(current, ".qm-maintenance-crashed.tmp").apply { writeText("半截") }
        assertTrue(writeGenerationFiles(current, previous, authors("主表A"), AUTHOR_MASTER_FILE))
        assertFalse(stale.exists())
        assertChecksum(current, AUTHOR_MASTER_FILE, "主表A")
    }

    @Test
    fun `上次半代副本缺摘要时重试修复不重复轮换`() {
        val (current, previous) = authorsDir()
        writeGenerationFiles(current, previous, authors("主表A"), AUTHOR_MASTER_FILE)
        // 模拟上次复制到一半：正文已复制、摘要未写。
        previous.mkdirs()
        File(previous, AUTHOR_MASTER_FILE).writeText("主表A", Charsets.UTF_8)
        assertTrue(writeGenerationFiles(current, previous, authors("主表B"), AUTHOR_MASTER_FILE))
        // 修复后上次是完整 A 代，摘要与正文一致。
        assertChecksum(previous, AUTHOR_MASTER_FILE, "主表A")
        // 再提交同一代：当前已是 B，不重复轮换，上次保持 A。
        assertFalse(writeGenerationFiles(current, previous, authors("主表B"), AUTHOR_MASTER_FILE))
        assertChecksum(previous, AUTHOR_MASTER_FILE, "主表A")
    }

    @Test
    fun `提交完成后重试不重复轮换`() {
        val (current, previous) = authorsDir()
        writeGenerationFiles(current, previous, authors("主表A"), AUTHOR_MASTER_FILE)
        // 模拟「已提交上次、写新当前前进程被杀」：当前仍是 A，上次已是 A。
        commitGenerationToPrevious(current, previous, AUTHOR_GENERATION_FILES)
        assertTrue(writeGenerationFiles(current, previous, authors("主表B"), AUTHOR_MASTER_FILE))
        assertChecksum(previous, AUTHOR_MASTER_FILE, "主表A")
        assertChecksum(current, AUTHOR_MASTER_FILE, "主表B")
    }

    @Test
    fun `当前正文已写新代但摘要未更新时重试自愈`() {
        val root = tmp.newFolder()
        val current = File(root, "当前/备份")
        val previous = File(root, "上次/备份")
        val name = BackupFileKind.CURRENT.fileName
        val first = """{"format":"qimeng_backup","schemaVersion":2,"data":{"v":1}}"""
        writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, first)), name, protectExternal = true)
        // 模拟写新当前时中断：正文已是新数据，摘要还是旧的；下一次自动备份不能误判为外部替换。
        val second = """{"format":"qimeng_backup","schemaVersion":2,"data":{"v":2}}"""
        File(current, name).writeText(second, Charsets.UTF_8)
        assertFalse(writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile(name, second)), name, protectExternal = true))
        assertChecksum(current, name, second)
        assertFalse(File(previous, name).exists())
    }

    @Test
    fun `文件名穿越和摘要名被拒绝`() {
        val (current, previous) = authorsDir()
        assertThrows(IllegalArgumentException::class.java) {
            writeGenerationFiles(current, previous, listOf(MaintenanceGenerationFile("../逃逸.txt", "x")), "../逃逸.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeGenerationFiles(
                current, previous,
                listOf(MaintenanceGenerationFile(AUTHOR_MASTER_FILE, "主表A"), MaintenanceGenerationFile("$AUTHOR_MASTER_FILE.sha256", "x")),
                AUTHOR_MASTER_FILE,
            )
        }
        // 拒绝后不落任何文件。
        assertFalse(File(previous, AUTHOR_MASTER_FILE).exists())
    }
}
