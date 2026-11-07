package media.qimeng.app.core.data.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import media.qimeng.sdk.infrastructure.Serializer

/**
 * 两代分代维护目录（2026-11-07 R7 批）：维护根下「当前 / 上次」各是一份独立文件夹，
 * 与服务端作者镜像、Web 维护页的目录命名约定一致（docs/AI_MAINTENANCE_P0.md §4.4、
 * docs/AI_PLATFORM_TASKBOOK.md §9.4）。
 *
 * 为什么抽成纯 JVM 实现：轮换、摘要与原子写是数据安全核心，必须能用临时目录在 JVM 单测
 * 锁定（本模块单测不启动 Android 设备）；「所有文件访问权限」、共享存储路径规范化、
 * 服务端镜像刷新等 Android 专属逻辑留在 [MaintenanceFolderRepository]。
 *
 * 轮换契约（与服务端两代布局同语义）：
 * - 内容变化时先把「当前」整代提交为「上次」，再原子写新「当前」；
 * - 相同数据重导出只刷新当前、不挤掉上次（备份比较 data 段，忽略信封导出时间）；
 * - 每代每个文件独立 .sha256；上次目标摘要不符或不可读时拒绝覆盖并保留现场；
 * - 中断重试幂等：半截副本按「内容已等于要提交的这一代」识别并补摘要修复，不重复轮换。
 */
internal const val CURRENT_GENERATION_DIR = "当前"
internal const val PREVIOUS_GENERATION_DIR = "上次"
internal const val AUTHORS_DIR = "作者资料"
internal const val BACKUP_DIR = "备份"
internal const val ARCHIVE_DIR = "上传归档"
internal const val AUTHOR_MASTER_FILE = "作者总表.txt"
internal const val AUTHOR_REPORT_FILE = "作者资料.md"
internal const val AUTHOR_INDEX_FILE = "作者片段索引.json"
internal const val MAINTENANCE_README_FILE = "目录说明.md"

/** 作者资料代由程序管理的固定文件；轮换只提交这些文件，不搬运用户自行放入的其他文件。 */
internal val AUTHOR_GENERATION_FILES = listOf(AUTHOR_MASTER_FILE, AUTHOR_REPORT_FILE, AUTHOR_INDEX_FILE)

/** 原子写临时文件前缀：点开头与用户文件区分；只清理本前缀残留，绝不删除用户文件。 */
private const val GENERATION_TEMP_PREFIX = ".qm-maintenance-"

/** 单文件上限与完整备份上限同值（BackupValidator.BACKUP_MAX_BYTES），防误读超大文件。 */
internal const val GENERATION_MAX_FILE_BYTES = 64L * 1024 * 1024

/** 摘要文件只允许短文本，拒绝把大文件误当摘要读取。 */
private const val CHECKSUM_MAX_BYTES = 1024L

/** 两代布局里的单个文件（UTF-8 文本：备份 JSON、作者总表 TXT 与 Markdown 都是文本载荷）。 */
internal data class MaintenanceGenerationFile(val name: String, val content: String) {
    val bytes: ByteArray get() = content.toByteArray(Charsets.UTF_8)
}

/**
 * 写一代文件并处理两代轮换：返回本次是否发生轮换。
 *
 * [files] 首项按主文件优先落盘（[primaryName]），保证「中断后重试」能靠读主文件判断是否已写新代；
 * [sameData] 决定「内容是否变化」——默认字节相等，备份传 [maintenanceBackupDataSignature]
 * 比较（导出时间等信封元数据变化不算数据变化）。
 * [protectExternal] 为 true 时（自动备份）若当前主文件内容将被覆盖但其摘要与内容不符，
 * 判定为外部替换并拒绝写入（保留现场）；内容未变化时只补写摘要修复中断，不触发保护。
 */
internal fun writeGenerationFiles(
    currentDir: File,
    previousDir: File,
    files: List<MaintenanceGenerationFile>,
    primaryName: String,
    sameData: (previous: String, next: String) -> Boolean = { previous, next -> previous == next },
    protectExternal: Boolean = false,
): Boolean {
    require(files.isNotEmpty()) { "维护代至少需要一个文件" }
    val names = files.map { it.name }
    require(names.toSet().size == names.size) { "维护代文件名重复" }
    files.forEach { requireGenerationFileName(it.name) }
    val primary = files.firstOrNull { it.name == primaryName } ?: error("缺少维护主文件：$primaryName")
    val current = prepareGenerationDirectory(currentDir)
    val previous = prepareGenerationDirectory(previousDir)
    val ordered = listOf(primary) + files.filter { it.name != primaryName }

    val previousContent = readGenerationText(current, primaryName)
    val changed = previousContent == null || !sameData(previousContent, primary.content)
    if (changed && previousContent != null) {
        if (protectExternal) {
            val expected = readGenerationChecksum(current, primaryName)
            check(expected != null && expected == maintenanceSha256(previousContent.toByteArray(Charsets.UTF_8))) {
                "备份已被外部替换，请先导入该文件，再更新备份"
            }
        }
        commitGenerationToPrevious(current, previous, names)
    }
    writeGenerationFileSet(current, ordered)
    return changed
}

/**
 * 只刷新一代内容、不轮换（相同数据的重导出，或服务端镜像已负责主表换代的作者伴随资料）。
 * 写盘顺序仍是「正文后摘要」，中断后下一次重写会整体覆盖修复。
 */
internal fun writeGenerationFileSet(directory: File, files: List<MaintenanceGenerationFile>) {
    require(files.isNotEmpty()) { "维护代至少需要一个文件" }
    val names = files.map { it.name }
    require(names.toSet().size == names.size) { "维护代文件名重复" }
    files.forEach { requireGenerationFileName(it.name) }
    val dir = prepareGenerationDirectory(directory)
    files.forEach { writeAtomicGenerationContent(dir, it.name, it.bytes) }
}

/**
 * 把当前整代提交为上次：只按 [names] 复制程序管理的同名文件并重算摘要，
 * 绝不删除上次目录中的任何文件，也不覆盖来历不明的同名文件。
 *
 * 先整体校验目标再写盘：目标自身摘要自洽（是一份完整旧代）才允许覆盖；
 * 目标摘要缺失/不符但内容已等于本次提交内容时，视为上次中断留下的半代副本并修复摘要；
 * 其余情况（外部编辑、同名目录等）抛错并保留两个目录的现场。
 */
internal fun commitGenerationToPrevious(currentDir: File, previousDir: File, names: Collection<String>) {
    val current = prepareGenerationDirectory(currentDir)
    val previous = prepareGenerationDirectory(previousDir)
    val sourceFiles = names.mapNotNull { name ->
        requireGenerationFileName(name)
        val source = File(current, name)
        require(source.canonicalFile.parentFile == current)
        source.takeIf { it.isFile }
    }
    if (sourceFiles.isEmpty()) return
    val hashes = sourceFiles.associateWith { file ->
        require(file.length() <= GENERATION_MAX_FILE_BYTES) { "${file.name} 超过维护文件上限，请先移走保留" }
        maintenanceSha256OfFile(file)
    }
    hashes.forEach { (file, hash) -> validatePreviousTarget(previous, file.name, hash) }
    hashes.forEach { (file, hash) -> copyFileToPrevious(file, previous, hash) }
}

/**
 * 把一份「刷新前快照」整代提交为上次（本机内嵌服务端负责写主表时的换代表补偿）。
 * 服务端镜像只写主表与摘要，作者资料.md / 作者片段索引.json 仍由客户端维护；
 * 提交前先校验上次目标，规则与 [commitGenerationToPrevious] 相同。
 */
internal fun commitGenerationSnapshot(previousDir: File, snapshot: Map<String, ByteArray>) {
    val entries = snapshot.entries
        .filter { (name, _) -> !isGenerationChecksum(name) && !isGenerationTemporaryName(name) }
    if (entries.isEmpty()) return
    entries.forEach { (name, content) -> requireGenerationFileName(name) }
    val previous = prepareGenerationDirectory(previousDir)
    val hashes = entries.associate { (name, content) -> name to maintenanceSha256(content) }
    hashes.forEach { (name, hash) -> validatePreviousTarget(previous, name, hash) }
    entries.forEach { (name, content) -> writeAtomicGenerationContent(previous, name, content) }
}

/** 读当前代内程序管理的文件快照；目录不存在返回空（首次导出/升级后尚未生成时）。 */
internal fun readGenerationSnapshot(directory: File, names: Collection<String>): Map<String, ByteArray> {
    val root = directory.canonicalFile
    if (!root.isDirectory) return emptyMap()
    val snapshot = linkedMapOf<String, ByteArray>()
    names.forEach { name ->
        requireGenerationFileName(name)
        val file = File(root, name)
        require(file.canonicalFile.parentFile == root)
        if (!file.isFile) return@forEach
        require(file.length() <= GENERATION_MAX_FILE_BYTES) { "${file.name} 超过维护文件上限" }
        snapshot[name] = file.readBytes()
    }
    return snapshot
}

/** 读一代内 [name] 的 UTF-8 正文；不存在返回 null。 */
internal fun readGenerationText(directory: File, name: String): String? {
    val file = generationFile(directory, name)
    if (!file.isFile) return null
    require(file.length() <= GENERATION_MAX_FILE_BYTES) { "${file.name} 超过维护文件上限，请先移走保留" }
    return file.readText(Charsets.UTF_8)
}

/** 读一代内 [name].sha256；不存在或内容异常返回 null（异常摘要按「无法确认」处理）。 */
internal fun readGenerationChecksum(directory: File, name: String): String? {
    val file = generationFile(directory, "$name.sha256")
    if (!file.isFile) return null
    require(file.length() <= CHECKSUM_MAX_BYTES) { "${file.name} 摘要文件异常，请人工核对" }
    return file.readText(Charsets.UTF_8).trim().lowercase()
}

/**
 * 备份轮换的「相同数据」签名：只比较信封 data 段，忽略 exportedAtMillis 等元数据；
 * 与旧三代实现、Web 口径一致。解析失败返回 null，调用方按「数据已变化」处理
 * （宁可多保留一代，也不把无法确认的旧文件当成相同数据）。
 */
internal fun maintenanceBackupDataSignature(json: String): String? {
    val parsed = try {
        Serializer.moshi.adapter(Any::class.java).fromJson(json)
    } catch (failure: Exception) {
        return null
    }
    val data = (parsed as? Map<*, *>)?.get("data") ?: return null
    return try {
        Serializer.moshi.adapter(Any::class.java).toJson(data)
    } catch (failure: Exception) {
        null
    }
}

/** 流式计算文件 SHA-256（大备份不整份读入内存）。 */
internal fun maintenanceSha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** 维护文件名安全校验：拒绝路径分隔符/控制字符/摘要名/临时前缀名。 */
internal fun requireGenerationFileName(name: String) {
    require(safeMaintenanceName(name)) { "维护文件名不安全：$name" }
    require(!isGenerationChecksum(name)) { "摘要文件由布局自动生成：$name" }
    require(!isGenerationTemporaryName(name)) { "维护文件名不能使用内部临时前缀：$name" }
}

/** 摘要伴随文件名（程序生成，不参与轮换提交列表）。 */
internal fun isGenerationChecksum(name: String): Boolean = name.endsWith(".sha256", ignoreCase = true)

internal fun isGenerationTemporaryName(name: String): Boolean = name.startsWith(GENERATION_TEMP_PREFIX)

/**
 * 创建/校验一代目录并清理本实现遗留的临时文件（掉电/进程被杀后的残留）。
 * 只删除本前缀的普通文件，不触碰目录内的任何用户文件。
 */
private fun prepareGenerationDirectory(directory: File): File {
    val canonical = directory.canonicalFile
    require(canonical.parentFile != null) { "维护代目录不能是文件系统根" }
    require(canonical.isDirectory || canonical.mkdirs()) { "无法创建维护代目录：${directory.name}" }
    (canonical.listFiles() ?: emptyArray())
        .filter { it.isFile && isGenerationTemporaryName(it.name) }
        .forEach { it.delete() }
    return canonical
}

private fun generationFile(directory: File, name: String): File {
    require(safeMaintenanceName(name)) { "维护文件名不安全：$name" }
    val root = directory.canonicalFile
    val file = File(root, name)
    require(file.canonicalFile.parentFile == root) { "文件超出维护代目录" }
    return file
}

/** 目标已有同名文件时先判定来历：完整旧代可覆盖；半截副本修复；其余拒绝并保留现场。 */
private fun validatePreviousTarget(previous: File, name: String, sourceSha: String) {
    val target = generationFile(previous, name)
    if (!target.exists()) return
    check(target.isFile) { "上次目录存在同名非普通文件：$name，已停止轮换并保留现场" }
    check(target.length() <= GENERATION_MAX_FILE_BYTES) { "上次文件超过大小上限，请先移走保留：$name" }
    val targetSha = maintenanceSha256OfFile(target)
    val stored = readGenerationChecksum(previous, name)
    when {
        // 目标正文与自身摘要一致：是完整的一代，按轮换覆盖。
        stored != null && stored == targetSha -> Unit
        // 目标正文已等于本次要提交的内容：上次中断留下的半代副本，补写摘要即可。
        targetSha == sourceSha -> Unit
        else -> error("上次目录中的 $name 摘要不符或无法确认来历，已停止轮换并保留现场，请人工核对")
    }
}

/** 流式复制当前代文件到上次并重算摘要（大备份不整份读入内存）。 */
private fun copyFileToPrevious(source: File, previous: File, expectedSha: String) {
    val target = generationFile(previous, source.name)
    val part = try {
        Files.createTempFile(previous.toPath(), GENERATION_TEMP_PREFIX, ".tmp").toFile()
    } catch (failure: Exception) {
        throw IllegalStateException("无法在上次目录创建临时文件：${failure.message}", failure)
    }
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
        check(actual == expectedSha) { "${source.name} 在提交过程中被修改，已停止轮换" }
        publishTemporary(part, target)
        published = true
        writeAtomicGenerationBytes(previous, "${source.name}.sha256", actual.toByteArray(Charsets.UTF_8))
    } finally {
        if (!published) part.delete()
    }
}

/** 写正文后写同名摘要（顺序固定：中断重试时按正文识别半代副本并补摘要）。 */
private fun writeAtomicGenerationContent(directory: File, name: String, content: ByteArray) {
    writeAtomicGenerationBytes(directory, name, content)
    writeAtomicGenerationBytes(directory, "$name.sha256", maintenanceSha256(content).toByteArray(Charsets.UTF_8))
}

/** 临时文件 + rename 原子替换：Android/minSdk26 与 JVM 测试都用 java.nio.file。 */
private fun writeAtomicGenerationBytes(directory: File, name: String, bytes: ByteArray) {
    val root = directory.canonicalFile
    val target = generationFile(root, name)
    val part = try {
        Files.createTempFile(root.toPath(), GENERATION_TEMP_PREFIX, ".tmp").toFile()
    } catch (failure: Exception) {
        throw IllegalStateException("无法创建维护临时文件：${failure.message}", failure)
    }
    var published = false
    try {
        FileOutputStream(part).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        publishTemporary(part, target)
        published = true
    } finally {
        if (!published) part.delete()
    }
}

/** 同目录内换名发布；优先原子移动，文件系统不支持时退回「替换式移动」。 */
private fun publishTemporary(part: File, target: File) {
    try {
        Files.move(
            part.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (unsupported: UnsupportedOperationException) {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
