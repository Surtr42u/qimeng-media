package media.qimeng.app.core.data.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 旧三代同目录布局（作者总表（上次）.txt 等）与新两代分代布局之间的核对/迁移。
 *
 * 迁移契约（docs/AI_MAINTENANCE_P0.md §4.4）：只读核对旧文件 → 用户确认后才复制生成
 * 「当前」「上次」；旧「上上次」不复制，只列入可清理清单；任何情况下都不删除用户目录文件，
 * 也不覆盖新布局中已存在的不同内容。这里只做纯文件操作，Android 权限/路径规范化在
 * [MaintenanceFolderRepository]。
 */

/** 核对结果里单个文件的摘要/可读状态。 */
enum class MaintenanceFileState {
    /** 正文与同名 .sha256 一致。 */
    OK,

    /** 没有 .sha256：旧版本对作者资料.md / 索引本就不生成摘要，迁移时补算。 */
    MISSING_CHECKSUM,

    /** .sha256 与正文不一致：可能被外部编辑或写入中断，迁移不动它。 */
    CHECKSUM_MISMATCH,

    /** 文件不可读。 */
    UNREADABLE,

    /** 超过单文件上限（64MiB），拒绝读入核对。 */
    OVERSIZE,
}

/** 文件在维护目录里的归属。 */
enum class MaintenanceFileRole {
    CURRENT,
    PREVIOUS,

    /** 旧三代布局的最新文件（作者总表.txt / 作者资料.md / 作者片段索引.json / 影库备份.json）。 */
    LEGACY_LATEST,

    /** 旧三代布局的「（上次）」文件。 */
    LEGACY_PREVIOUS,

    /** 旧三代布局的「（上上次）」文件：按约定不迁移，只列可清理。 */
    LEGACY_OLDER,

    /** 旧目录里无法识别来源的文件：保留原样，不动它。 */
    LEGACY_OTHER,
}

/** 旧布局文件的迁移建议（只报告，[copyLegacyToGenerations] 才真正复制）。 */
enum class MaintenanceMigrationAction {
    /** 目标代目录没有同名文件，可复制生成。 */
    COPY,

    /** 目标代目录已有相同内容：旧文件是冗余副本，核对后可清理（不自动删）。 */
    ALREADY_PRESENT,

    /** 目标代目录已有不同内容：不覆盖，需人工核对。 */
    CONFLICT,

    /** 无法确认来源或正文损坏：保留原样。 */
    KEEP,

    /** 旧「上上次」：按两代约定不复制，核对后可清理（不自动删）。 */
    CLEAN_AFTER_REVIEW,
}

/** 维护目录核对结果中的单个文件。 */
data class MaintenanceFileReport(
    val location: String,
    val sizeBytes: Long,
    val state: MaintenanceFileState,
    val role: MaintenanceFileRole,
    val action: MaintenanceMigrationAction? = null,
)

/**
 * 只读核对报告：当前/上次代文件状态、旧三代布局文件、可迁移与可清理清单、警告。
 * [needsMigration] 仅表示旧布局文件存在；是否执行复制由用户决定。
 */
data class MaintenanceLayoutReport(
    val current: List<MaintenanceFileReport>,
    val previous: List<MaintenanceFileReport>,
    val legacy: List<MaintenanceFileReport>,
    val migratable: List<MaintenanceFileReport>,
    val cleanable: List<MaintenanceFileReport>,
    val warnings: List<String>,
) {
    val needsMigration: Boolean get() = legacy.isNotEmpty()
}

/** 复制迁移结果：只复制，不移动、不删除；[skipped] 含冲突/损坏/无需复制的旧文件。 */
data class MaintenanceMigrationResult(
    val copied: List<String>,
    val skipped: List<String>,
    val warnings: List<String>,
) {
    val changed: Boolean get() = copied.isNotEmpty()
}

private const val LEGACY_PREVIOUS_SUFFIX = "（上次）"
private const val LEGACY_OLDER_SUFFIX = "（上上次）"
private const val CHECKSUM_MAX_BYTES = 1024L
private const val MIGRATION_TEMP_PREFIX = ".qm-maintenance-"

private enum class LegacyFolder { AUTHORS, BACKUPS }

private data class InspectedFile(val state: MaintenanceFileState, val sha256: String?)

private data class LegacyPlanEntry(
    val source: File,
    val target: File?,
    val report: MaintenanceFileReport,
    val sourceSha: String?,
)

/**
 * 只读核对：不创建目录、不清理、不改动任何文件；只读取大小与摘要。
 * 根目录不存在时返回空报告（调用方负责给出「请先选择维护根目录」提示）。
 */
internal fun inspectMaintenanceLayout(root: File): MaintenanceLayoutReport {
    val canonical = root.canonicalFile
    val plan = planLegacyMigration(canonical)
    val current = listOf(
        inspectGeneration(File(File(canonical, CURRENT_GENERATION_DIR), AUTHORS_DIR), canonical, MaintenanceFileRole.CURRENT),
        inspectGeneration(File(File(canonical, CURRENT_GENERATION_DIR), BACKUP_DIR), canonical, MaintenanceFileRole.CURRENT),
    ).flatten()
    val previous = listOf(
        inspectGeneration(File(File(canonical, PREVIOUS_GENERATION_DIR), AUTHORS_DIR), canonical, MaintenanceFileRole.PREVIOUS),
        inspectGeneration(File(File(canonical, PREVIOUS_GENERATION_DIR), BACKUP_DIR), canonical, MaintenanceFileRole.PREVIOUS),
    ).flatten()
    val legacy = plan.map { it.report }
    val migratable = plan.filter { it.report.action == MaintenanceMigrationAction.COPY }.map { it.report }
    val cleanable = plan
        .filter {
            it.report.action == MaintenanceMigrationAction.ALREADY_PRESENT ||
                it.report.action == MaintenanceMigrationAction.CLEAN_AFTER_REVIEW
        }
        .map { it.report }
    val warnings = buildList {
        plan.forEach { entry ->
            when (entry.report.action) {
                MaintenanceMigrationAction.CONFLICT ->
                    add("${entry.report.location} 与 ${location(canonical, entry.target)} 内容不同，未迁移也未覆盖")
                MaintenanceMigrationAction.KEEP ->
                    add("${entry.report.location} 无法确认来源或正文损坏，已保留原样")
                else -> Unit
            }
        }
        (current + previous).forEach { report ->
            if (report.state == MaintenanceFileState.UNREADABLE || report.state == MaintenanceFileState.OVERSIZE) {
                add("${report.location} 无法读取或超过大小上限，请人工核对")
            }
        }
    }
    return MaintenanceLayoutReport(current, previous, legacy, migratable, cleanable, warnings)
}

/**
 * 只读核对后复制生成两代布局：最新文件 → 当前，旧「（上次）」→ 上次；旧「（上上次）」不复制。
 * - 只复制，不删除/不移动旧文件，复制失败保留源文件；
 * - 目标代已有不同内容时不覆盖（[MaintenanceMigrationAction.CONFLICT]），留待人工核对；
 * - 目标代已存在相同内容时跳过（幂等，可重复调用）；
 * - 复制内容重算 SHA-256 写入目标代，旧摘要不直接照搬。
 */
internal fun copyLegacyToGenerations(root: File): MaintenanceMigrationResult {
    val canonical = root.canonicalFile
    val plan = planLegacyMigration(canonical)
    val copied = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    plan.filter { it.report.action == MaintenanceMigrationAction.COPY }.forEach { entry ->
        val target = entry.target ?: return@forEach
        try {
            if (target.exists()) {
                // 计划后并发出现了目标：迁移永远不覆盖已有文件。
                skipped += entry.report.location
                warnings += "${entry.report.location} 的目标在迁移过程中出现，已跳过（未覆盖）"
                return@forEach
            }
            copyLegacyFile(entry.source, target, requireNotNull(entry.sourceSha))
            copied += entry.report.location
        } catch (failure: Exception) {
            skipped += entry.report.location
            warnings += "${entry.report.location} 复制失败，旧文件保持原样：${failure.message}"
        }
    }
    plan.filter { it.report.action == MaintenanceMigrationAction.ALREADY_PRESENT }.forEach { entry ->
        val target = entry.target ?: return@forEach
        val sha = entry.sourceSha ?: return@forEach
        // 目标正文已与旧文件一致：只补写摘要（修复上次复制在写摘要前中断），绝不改正文。
        runCatching { writeAtomicText(requireNotNull(target.parentFile), "${target.name}.sha256", sha) }
            .onFailure { warnings += "${entry.report.location} 摘要补齐失败：${it.message}" }
    }
    plan.filter { it.report.action != MaintenanceMigrationAction.COPY }.forEach { entry ->
        skipped += entry.report.location
        when (entry.report.action) {
            MaintenanceMigrationAction.CONFLICT -> warnings += "${entry.report.location} 与目标代已有内容不同，未覆盖"
            MaintenanceMigrationAction.KEEP -> warnings += "${entry.report.location} 无法确认来源或正文损坏，已保留原样"
            else -> Unit
        }
    }
    return MaintenanceMigrationResult(copied, skipped, warnings)
}

private fun inspectGeneration(
    directory: File,
    root: File,
    role: MaintenanceFileRole,
): List<MaintenanceFileReport> {
    if (!directory.isDirectory) return emptyList()
    return (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && !isGenerationChecksum(it.name) && !isGenerationTemporaryName(it.name) }
        .sortedBy { it.name }
        .map { file -> reportFor(file, root, role, inspectFile(file).state) }
}

private fun reportFor(
    file: File,
    root: File,
    role: MaintenanceFileRole,
    state: MaintenanceFileState,
    action: MaintenanceMigrationAction? = null,
): MaintenanceFileReport = MaintenanceFileReport(
    location = location(root, file),
    sizeBytes = file.length(),
    state = state,
    role = role,
    action = action,
)

private fun planLegacyMigration(root: File): List<LegacyPlanEntry> = buildList {
    addAll(planLegacyFolder(root, File(root, AUTHORS_DIR), LegacyFolder.AUTHORS))
    addAll(planLegacyFolder(root, File(root, BACKUP_DIR), LegacyFolder.BACKUPS))
}

private fun planLegacyFolder(root: File, directory: File, folder: LegacyFolder): List<LegacyPlanEntry> {
    if (!directory.isDirectory) return emptyList()
    val files = (directory.listFiles() ?: return emptyList())
        .filter { it.isFile && !isGenerationChecksum(it.name) && !isGenerationTemporaryName(it.name) }
        .sortedBy { it.name }
    return files.mapNotNull { file ->
        val inspected = inspectFile(file)
        when (legacyRole(file.name)) {
            MaintenanceFileRole.LEGACY_OLDER -> LegacyPlanEntry(
                source = file,
                target = null,
                report = reportFor(
                    file, root, MaintenanceFileRole.LEGACY_OLDER, inspected.state,
                    if (inspected.state == MaintenanceFileState.OK || inspected.state == MaintenanceFileState.MISSING_CHECKSUM) {
                        MaintenanceMigrationAction.CLEAN_AFTER_REVIEW
                    } else {
                        MaintenanceMigrationAction.KEEP
                    },
                ),
                sourceSha = inspected.sha256,
            )
            MaintenanceFileRole.LEGACY_OTHER -> LegacyPlanEntry(
                source = file,
                target = null,
                report = reportFor(file, root, MaintenanceFileRole.LEGACY_OTHER, inspected.state, MaintenanceMigrationAction.KEEP),
                sourceSha = inspected.sha256,
            )
            MaintenanceFileRole.LEGACY_LATEST -> legacyEntry(
                root, file, currentGenerationDirectory(root, folder), MaintenanceFileRole.LEGACY_LATEST, inspected,
            )
            else -> {
                val previousName = previousGenerationName(file.name)
                if (previousName == null) {
                    LegacyPlanEntry(
                        source = file,
                        target = null,
                        report = reportFor(file, root, MaintenanceFileRole.LEGACY_OTHER, inspected.state, MaintenanceMigrationAction.KEEP),
                        sourceSha = inspected.sha256,
                    )
                } else {
                    legacyEntry(
                        root, file,
                        targetDirectory = previousGenerationDirectory(root, folder),
                        role = MaintenanceFileRole.LEGACY_PREVIOUS,
                        inspected = inspected,
                        targetName = previousName,
                    )
                }
            }
        }
    }
}

private fun legacyEntry(
    root: File,
    source: File,
    targetDirectory: File,
    role: MaintenanceFileRole,
    inspected: InspectedFile,
    targetName: String = source.name,
): LegacyPlanEntry {
    val target = File(targetDirectory, targetName)
    // 摘要不符/不可读的旧文件可能已被外部编辑或损坏，不自动迁移，保留原样待人工核对。
    if (inspected.state != MaintenanceFileState.OK && inspected.state != MaintenanceFileState.MISSING_CHECKSUM) {
        return LegacyPlanEntry(source, target, reportFor(source, root, role, inspected.state, MaintenanceMigrationAction.KEEP), inspected.sha256)
    }
    val action = when {
        !target.isFile -> MaintenanceMigrationAction.COPY
        targetSha256(target) == inspected.sha256 -> MaintenanceMigrationAction.ALREADY_PRESENT
        else -> MaintenanceMigrationAction.CONFLICT
    }
    return LegacyPlanEntry(source, target, reportFor(source, root, role, inspected.state, action), inspected.sha256)
}

private fun legacyRole(name: String): MaintenanceFileRole {
    if (name in AUTHOR_GENERATION_FILES || name == BackupFileKind.CURRENT.fileName) {
        return MaintenanceFileRole.LEGACY_LATEST
    }
    val rotatingBases = listOf(AUTHOR_MASTER_FILE, BackupFileKind.CURRENT.fileName)
    for (base in rotatingBases) {
        val stem = base.substringBeforeLast('.')
        val extension = base.substringAfterLast('.')
        if (name == "$stem$LEGACY_OLDER_SUFFIX.$extension") return MaintenanceFileRole.LEGACY_OLDER
        if (name == "$stem$LEGACY_PREVIOUS_SUFFIX.$extension") return MaintenanceFileRole.LEGACY_PREVIOUS
    }
    return MaintenanceFileRole.LEGACY_OTHER
}

private fun previousGenerationName(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return null
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    if (!stem.endsWith(LEGACY_PREVIOUS_SUFFIX)) return null
    return stem.removeSuffix(LEGACY_PREVIOUS_SUFFIX) + extension
}

private fun currentGenerationDirectory(root: File, folder: LegacyFolder): File = File(
    File(root, CURRENT_GENERATION_DIR),
    if (folder == LegacyFolder.AUTHORS) AUTHORS_DIR else BACKUP_DIR,
)

private fun previousGenerationDirectory(root: File, folder: LegacyFolder): File = File(
    File(root, PREVIOUS_GENERATION_DIR),
    if (folder == LegacyFolder.AUTHORS) AUTHORS_DIR else BACKUP_DIR,
)

private fun inspectFile(file: File): InspectedFile {
    val size = runCatching { file.length() }.getOrElse { return InspectedFile(MaintenanceFileState.UNREADABLE, null) }
    if (size > GENERATION_MAX_FILE_BYTES) return InspectedFile(MaintenanceFileState.OVERSIZE, null)
    val sha = runCatching { maintenanceSha256OfFile(file) }.getOrElse {
        return InspectedFile(MaintenanceFileState.UNREADABLE, null)
    }
    val checksum = File(file.parentFile, "${file.name}.sha256")
    if (!checksum.isFile) return InspectedFile(MaintenanceFileState.MISSING_CHECKSUM, sha)
    if (checksum.length() > CHECKSUM_MAX_BYTES) return InspectedFile(MaintenanceFileState.CHECKSUM_MISMATCH, sha)
    val stored = runCatching { checksum.readText(Charsets.UTF_8).trim().lowercase() }.getOrNull()
    return if (stored != null && stored == sha) {
        InspectedFile(MaintenanceFileState.OK, sha)
    } else {
        InspectedFile(MaintenanceFileState.CHECKSUM_MISMATCH, sha)
    }
}

private fun targetSha256(target: File): String? =
    runCatching { if (target.isFile) maintenanceSha256OfFile(target) else null }.getOrNull()

/**
 * 复制旧文件到目标代：先以无覆盖rename发布正文、成功后再写摘要（中断时最多是
 * 「正文完整、摘要缺失」，下次迁移按内容一致补摘要），失败清理本次临时文件。
 * 绝不覆盖检查后并发出现的同名目标。
 */
private fun copyLegacyFile(source: File, target: File, expectedSha: String) {
    val parent = target.parentFile ?: error("迁移目标目录不存在")
    require(parent.isDirectory || parent.mkdirs()) { "无法创建迁移目标目录：${parent.name}" }
    val part = Files.createTempFile(parent.toPath(), MIGRATION_TEMP_PREFIX, ".tmp").toFile()
    var published = false
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(part).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
            output.fd.sync()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedSha) { "${source.name} 在复制过程中被修改，已跳过" }
        // 先发布正文（不带 REPLACE_EXISTING：目标在检查后并发出现时失败、不覆盖），
        // 成功后再写摘要：中断时最多是「正文完整、摘要缺失」，下次迁移按内容一致补摘要，
        // 不会把本次摘要写到别人并发创建的同名文件上。
        Files.move(part.toPath(), target.toPath())
        published = true
        writeAtomicText(parent, "${target.name}.sha256", actual)
    } finally {
        part.delete()
    }
}

private fun writeAtomicText(directory: File, name: String, content: String) {
    val root = directory.canonicalFile
    val target = File(root, name)
    require(target.canonicalFile.parentFile == root) { "摘要文件超出目标目录" }
    val part = Files.createTempFile(root.toPath(), MIGRATION_TEMP_PREFIX, ".tmp").toFile()
    var published = false
    try {
        FileOutputStream(part).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        published = true
    } finally {
        if (!published) part.delete()
    }
}

private fun location(root: File, file: File?): String {
    if (file == null) return "（无目标）"
    return runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name)
}
