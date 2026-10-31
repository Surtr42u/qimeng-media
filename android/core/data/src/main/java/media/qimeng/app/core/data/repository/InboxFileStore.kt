package media.qimeng.app.core.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传归档 File 侧操作收口（2026-09-25 暂存区重做；2026-09-29 直传化收窄为「归档所需部分」：
 * 收件箱扫描/存在性探测/目录文件列举随暂存区与浏览文件入口退役删除，目录浏览保留——
 * 归档文件夹设置页还用），全部 java.io.File 纯 JDK API（App 已持 MANAGE_EXTERNAL_STORAGE，
 * ADR-0015；不用 MediaStore/SAF——点前缀隐藏目录系统相册扫不到、SAF 也不便选）。
 * 消费方：StagingRepository（授权/目录浏览端口）与 UploadWorker 的 201 后归档分派
 * （uploaded/ 回退归档 + 归档文件夹归档，红线链路）。方法均无状态，JVM 单测用
 * 临时目录直测（InboxFileStoreTest）。
 */
@Singleton
class InboxFileStore @Inject constructor() {

    /** 「所有文件访问」授权判定（口径与 ServerSettingsScreen 既有判定一致，注释见接口） */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** 主存储根目录（目录浏览器起点） */
    fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** 目录浏览：一级子目录（含隐藏目录，名称升序；不可读/非目录返回空表） */
    fun listDirectories(path: String): List<InboxDirEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles(File::isDirectory).orEmpty()
            .map { InboxDirEntry(name = it.name, path = it.absolutePath) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 归档：源文件无覆盖地移动到同目录 uploaded/ 子目录（失败不抛，返回 false）。
     * 同名目标已存在时保留源与目标，避免覆盖用户之前归档的文件。
     * 消费方为 worker 的收件箱来源回退归档分支（UploadWorker.archiveNote，红线链路）。
     */
    fun archiveToUploaded(sourcePath: String, uploadTimestamp: Long? = null): Boolean {
        val file = File(sourcePath)
        if (!file.isFile) return false
        val parent = file.parentFile ?: return false
        val uploadedDir = File(parent, StagingRepository.UPLOADED_DIR_NAME)
        if (!uploadedDir.isDirectory && !uploadedDir.mkdirs()) return false
        if (!isDirectChildDirectory(parent, uploadedDir)) return false
        if (Files.isSymbolicLink(file.toPath())) return false
        val target = File(uploadedDir, file.name)
        return moveFileNoReplace(file, target) && applyUploadTimestamp(target, uploadTimestamp)
    }

    /**
     * 外部服务器确认入库后释放手机归档副本。只删归档根下库目录内、与入队 size/mtime
     * 一致的普通文件；拒绝所有子路径链接、根下散文件和旧载荷缺失时间。不存在视为已清理。
     * 元数据核对不能识别同 size/mtime 的内容替换，也不提供相对外部写入者的原子 compare-delete。
     */
    fun deleteUploadedArchive(archiveRoot: String, source: File, sizeBytes: Long, sourceMtime: Long?): Boolean =
        runCatching {
            if (!source.isAbsolute || sourceMtime == null || sourceMtime <= 0 || sizeBytes < 0) return@runCatching false
            val configuredRoot = File(archiveRoot).absoluteFile.toPath().normalize()
            val sourcePath = source.toPath().toAbsolutePath()
            if (sourcePath != sourcePath.normalize() || !sourcePath.startsWith(configuredRoot)) return@runCatching false
            val relative = configuredRoot.relativize(sourcePath)
            if (relative.nameCount < MIN_ARCHIVE_FILE_SEGMENTS) return@runCatching false
            // /sdcard 等归档根自身可为 Android 提供的别名；根下的任何链接仍一律拒绝。
            val root = File(archiveRoot).canonicalFile.toPath()
            if (!Files.isDirectory(root)) return@runCatching false
            val path = root.resolve(relative)
            var cursor = root
            for (segment in relative) {
                cursor = cursor.resolve(segment)
                if (Files.isSymbolicLink(cursor)) return@runCatching false
                if (cursor != path && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) return@runCatching false
            }
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() != sizeBytes ||
                attributes.lastModifiedTime().toMillis() != sourceMtime
            ) return@runCatching false
            Files.delete(path)
            true
        }.getOrDefault(false)

    /**
     * 归档到上传归档文件夹（2026-09-28 上传归档文件夹功能）：源文件移入
     * <archiveRoot>/<sanitize(库名)>/<目标文件名>，供用户手动复制同步到电脑。
     * - 库名 sanitize：Windows/Linux 目录名非法字符统一替换为 _（归档根会被用户复制到
     *   电脑、常为 Windows，按更严的 Windows 保留字符集处理保证跨平台可复制）；
     *   空/空白库名返回 false（无目录名可建）；
     * - 目标目录不存在时连归档根一并创建（mkdirs）；
     * - 同名冲突：内容不同则源文件名加序号 (1)(2)… 找首个空位（绝不覆盖——归档区文件
     *   可能已被用户整理过，静默覆盖等于丢文件）；内容相同则保留已有归档目标、删除重复源；
     *   源路径本来就是目标路径时幂等成功，不做删除/重命名；
     * - 同卷优先无覆盖移动；不同挂载点或移动失败时 fallback「复制到唯一 .part 临时名 +
     *   无覆盖发布 + 删源」（跨挂载点兜底；[copyFallback]）。
     * 失败不抛、返回 false（调用方只记提示不阻断上传完成，与 [archiveToUploaded] 同口径）。
     */
    fun archiveToLibraryRoot(archiveRoot: String, libraryName: String, sourceFile: File, uploadTimestamp: Long? = null): Boolean {
        if (!sourceFile.isFile) return false
        if (Files.isSymbolicLink(sourceFile.toPath())) return false
        val dirName = sanitizeLibraryDirName(libraryName) ?: return false
        val root = File(archiveRoot)
        val targetDir = File(root, dirName)
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
        if (!isDirectChildDirectory(root, targetDir)) return false
        var target = File(targetDir, sourceFile.name)
        return try {
            if (target.exists()) {
                // 上传源本来就在目标归档位置时是幂等成功；绝不能先删目标再把同一文件当源搬一次。
                if (sourceFile.canonicalFile == target.canonicalFile) return applyUploadTimestamp(target, uploadTimestamp)
                if (sameContent(sourceFile, target)) {
                    // 同内容已有归档就是完整副本：保留用户可能整理过的目标文件，仅移除重复源。
                    return applyUploadTimestamp(target, uploadTimestamp) && sourceFile.delete()
                }
                target = resolveConflictName(targetDir, sourceFile.name) ?: return false
            }
            moveOrCopyNoReplace(sourceFile, target, uploadTimestamp)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把 content:// URI 流归档到上传归档文件夹（供相册与系统文件来源在设置归档文件夹后落位）：
     * 将文件流写入 <archiveRoot>/<sanitize(库名)>/<目标文件名>。
     * - 优先比对目标存在性与内容：同内容保留已有目标并安全返回（不删除源 URI，因为无法证明它与目标不是同一物理文件）；不同内容加序号；
     * - 写入流经 .part 临时文件改名到位；
     * - 新目标写入成功后尽力调用 DocumentsContract.deleteDocument 删除源文档（若 SAF provider
     *   支持删除则达成移动语义；若不支持删除/无删除权限则安全留存原位，达成安全备份归档语义）；
     * - 绝不抛异常，失败返回 false。
     */
    fun archiveUriToLibraryRoot(
        context: Context,
        archiveRoot: String,
        libraryName: String,
        uri: Uri,
        displayName: String,
        uploadTimestamp: Long? = null,
    ): Boolean {
        // displayName 来自外部 ContentProvider，必须先拒绝路径分隔符/控制字符，
        // 避免目标路径逸出库归档目录。文件名保持原样，不做静默改名。
        if (!isSafeArchiveFileName(displayName)) return false
        val dirName = sanitizeLibraryDirName(libraryName) ?: return false
        val root = File(archiveRoot)
        val targetDir = File(root, dirName)
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
        if (!isDirectChildDirectory(root, targetDir)) return false
        var target = File(targetDir, displayName)
        if (target.exists()) {
            if (sameContent(context, uri, target)) {
                // 已存在同内容归档：不重写/不删除目标文件，也不删除源 URI。
                // 通用 SAF URI 无法证明与 target 是不同物理文件；删源可能把归档本体一起删掉。
                // 此分支按安全备份语义成功，宁保留一份重复源，也不冒误删唯一归档的风险。
                return applyUploadTimestamp(target, uploadTimestamp)
            }
            target = resolveConflictName(targetDir, displayName) ?: return false
        }
        // 固定的 "<目标名>.part" 会和用户已有同名文件或另一归档任务冲突。
        // 创建唯一临时文件，并在发布时使用不带 REPLACE_EXISTING 的 Files.move。
        val part = try {
            Files.createTempFile(targetDir.toPath(), "qimeng-archive-", ARCHIVE_PART_SUFFIX).toFile()
        } catch (e: Exception) {
            return false
        }
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { inStream ->
                part.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (!applyUploadTimestamp(part, uploadTimestamp)) return false
            // 不能为了发布成功而删掉首检后被其他进程创建的目标；检测到冲突时保留源与目标。
            // 注意：标准 Files.move 的存在性检查与底层移动不是跨进程原子操作，仍有窄 TOCTOU 窗口。
            if (!moveFileNoReplace(part, target)) return false
            // 尽力而为尝试删除源文档（若 SAF 提供方支持删除）
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            part.delete()
        }
    }

    /**
     * 重命名归档目录内的物理文件（2026-10-10 资产重命名联动归档）：在资产详情编辑页改名成功后，
     * 自动将本地归档目录中的物理文件同步重命名（例如 11.mp4 -> 守望先锋  天使 13.mp4）。
     * 规则：
     * - 归档根不存在或非目录时返回 false；
     * - 优先在 <archiveRoot>/<libraryName>/ 或 <archiveRoot>/<sanitize(libraryName)>/ 查找 oldFileName；
     * - 若未指定 libraryName 或对应目录不存在，回落遍历 archiveRoot 的各一级子目录查找；
     * - 找到目标文件后使用无覆盖移动重命名；目标同名已存在（包括检查后并发创建）则不覆盖返回 false；
     * - 文件成功重命名返回 true，未找到或重命名失败返回 false。
     */
    fun renameArchivedFile(
        archiveRoot: String,
        libraryName: String?,
        oldFileName: String,
        newFileName: String,
    ): Boolean {
        if (!isSafeArchiveFileName(oldFileName) || !isSafeArchiveFileName(newFileName) || oldFileName == newFileName) {
            return false
        }
        val root = File(archiveRoot)
        if (!root.isDirectory) return false

        val candidateDirs = mutableListOf<File>()
        if (!libraryName.isNullOrBlank()) {
            // 原始库名只有在单个安全路径段时才可直接拼接；否则仅尝试 sanitize 后的目录名。
            // 不能先 File(root, "../...") 再依赖后续过滤——候选目录本身可能指向归档根之外。
            if (isSafeArchiveFileName(libraryName)) {
                candidateDirs += File(root, libraryName)
            }
            val sanitized = sanitizeLibraryDirName(libraryName)
            if (sanitized != null && sanitized != libraryName) {
                candidateDirs += File(root, sanitized)
            }
        }
        root.listFiles(File::isDirectory)?.let { candidateDirs.addAll(it) }

        for (dir in candidateDirs.distinct()) {
            // 除了拦截 ..，也拒绝指向根外或指向另一归档库的目录符号链接。
            if (!isDirectChildDirectory(root, dir)) continue
            val source = File(dir, oldFileName)
            if (source.isFile) {
                val target = File(dir, newFileName)
                if (!target.exists()) {
                    return moveFileNoReplace(source, target)
                }
            }
        }
        return false
    }

    /**
     * copy 兜底：先复制到目标目录下唯一 .part 临时文件，再以无覆盖方式发布到目标位，
     * 最后删除源。不能直接 copyTo 目标名：复制中途失败会留下半截同名文件。
     * 发布时若目标已存在且被标准库检测到，操作失败并保留目标与源文件；finally 只清理本次
     * 创建的临时文件。注意标准 Files.move 的检查与移动不是跨进程原子操作，仍有窄 TOCTOU
     * 窗口。删源失败时目标是完整副本、源仍在，返回 false 提醒用户人工核对。
     */
    private fun copyFallback(sourceFile: File, target: File, uploadTimestamp: Long?): Boolean {
        var part: File? = null
        try {
            val parent = target.parentFile ?: return false
            // 唯一临时名避免覆盖同目录中由其他操作创建的 .part 文件。
            part = Files.createTempFile(parent.toPath(), "qimeng-archive-", ARCHIVE_PART_SUFFIX).toFile()
            sourceFile.copyTo(part, overwrite = true)
            if (!applyUploadTimestamp(part, uploadTimestamp)) return false
            // Files.move 不指定 REPLACE_EXISTING：目标竞争时失败，而不是覆盖竞争者文件。
            if (!moveFileNoReplace(part, target)) return false
            return sourceFile.delete()
        } finally {
            // 成功路径 part 已改名移走、delete 是 no-op；异常/失败路径只清理本次创建的临时文件。
            part?.delete()
        }
    }

    /**
     * 同卷优先无覆盖移动：先试平台改名（同卷瞬时；跨卷以无害失败退出、绝不复制），
     * 失败且目标仍是空位时再走受控复制（[copyFallback]）。
     * 为什么不用 Files.getFileStore 判定卷：Android 上它恒抛 SecurityException（AOSP
     * UnixFileSystemProvider/LinuxFileSystemProvider 的 Android-changed 覆写，SELinux 策略），
     * runCatching 吞掉后会让每次归档都静默退化成本地整文件复制（2026-10-15 审查发现）。
     * 为什么不直接交给 Files.move：libcore UnixCopyFile.move 在跨卷时会自行 copy+删源，
     * 绕开本模块「唯一临时文件 + 无覆盖发布 + 失败即清理」的受控路径。
     * 已知边界：存在性检查与改名之间不保证跨进程原子（与 [moveFileNoReplace] 同一窗口）；
     * 检查后被并发创建的目标不被替换，靠的是改名前的 exists() 前置检查必须保留——
     * POSIX rename 自身会替换既有目标。
     */
    private fun moveOrCopyNoReplace(source: File, target: File, uploadTimestamp: Long?): Boolean {
        if (!target.exists() && source.renameTo(target)) return applyUploadTimestamp(target, uploadTimestamp)
        // 目标已存在（含首检后并发创建）时不再复制/尝试替换它。
        if (target.exists()) return false
        return copyFallback(source, target, uploadTimestamp)
    }

    /** 只更新实际归档文件；失败向上传完成提示传播，拒绝通过链接触碰其他文件。 */
    private fun applyUploadTimestamp(file: File, timestamp: Long?): Boolean =
        runCatching {
            timestamp == null || (!Files.isSymbolicLink(file.toPath()) && file.setLastModified(timestamp))
        }.getOrDefault(false)

    /**
     * 发布文件时不指定 REPLACE_EXISTING；目标在 Files.move 检查时已存在会安全失败。
     * Android minSdk=26，可用 java.nio.file.Files。
     *
     * 已知边界：标准 Files.move 的目标存在性检查与底层移动不保证相对其他进程原子；
     * 不可把此 helper 当作跨进程 no-replace 原语。外部写入者仍可能在检查与 move 之间竞争，
     * 后续如需消除该窗口，需按目标文件系统提供原子 no-replace 原语或引入所有写入方共用的协调。
     */
    internal fun moveFileNoReplace(source: File, target: File): Boolean = try {
        Files.move(source.toPath(), target.toPath())
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 库名 -> 归档子目录名：非法字符替换为 [LIBRARY_DIR_SANITIZE_CHAR]。
     * 空/空白（trim 后）返回 null——调用方按归档失败处理（回退 uploaded/ 归档）。
     * internal（2026-10-01 归档一键上传）：归档读取侧（ArchiveBatchScan.kt）反向匹配
     * 文件夹名复用同一实现，保证「建目录名」与「匹配目录名」单一事实源；规则本体不变。
     */
    internal fun sanitizeLibraryDirName(libraryName: String): String? {
        val sanitized = buildString {
            for (c in libraryName.trim()) {
                append(if (c in LIBRARY_DIR_ILLEGAL_CHARS) LIBRARY_DIR_SANITIZE_CHAR else c)
            }
        }
        // 路径语义段与 Windows 不允许的尾随点统一拒绝，避免跨平台复制后路径被归一成别的目录。
        return sanitized.takeIf {
            it.isNotEmpty() && it != "." && it != ".." && !it.endsWith(".")
        }
    }

    /**
     * 同名冲突解名：基名 +「 (N)」+ 扩展名逐级试探首个不存在的名字（N 从 1 起）。
     * 序号耗尽（极端：同名前缀文件已堆满上限）返回 null 按失败处理，绝不覆盖既有文件。
     */
    private fun resolveConflictName(targetDir: File, fileName: String): File? {
        val dot = fileName.lastIndexOf('.')
        // dot<=0：无扩展名或点前缀隐藏文件——整个文件名当基名，序号加在尾部
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (seq in 1..MAX_CONFLICT_SEQ) {
            val candidate = File(targetDir, base + String.format(Locale.ROOT, CONFLICT_SEQ_SUFFIX, seq) + ext)
            if (!candidate.exists()) return candidate
        }
        return null
    }

    /**
     * 确保 child 是 root 下真实的直接子目录，而不是指向归档根外/另一库的符号链接。
     * 归档和重命名均涉及搬动/删除源文件；仅校验名称还不足以约束文件系统的链接别名。
     */
    private fun isDirectChildDirectory(root: File, child: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalFile
        val canonicalChild = child.canonicalFile
        canonicalChild.parentFile == canonicalRoot && canonicalChild.name == child.name
    }.getOrDefault(false)

    /** 纯文件名校验：ContentProvider 提供的展示名不可包含路径/控制字符。 */
    internal fun isSafeArchiveFileName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.endsWith(" ") || name.endsWith(".")) return false
        return name.none {
            it in LIBRARY_DIR_ILLEGAL_CHARS || it.code < 0x20 || it.code == 0x7f
        }
    }

    /** 字节级内容比对：长度先短路；流式比对不整载内存（归档对象可能是大视频）。 */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return try {
            a.inputStream().use { left ->
                b.inputStream().use { right -> streamsEqual(left, right) }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sameContent(context: Context, uri: Uri, b: File): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { left ->
                b.inputStream().use { right -> streamsEqual(left, right) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 流式逐字节比较，正确处理 InputStream 短读，不为大视频分配整文件内存。 */
    internal fun streamsEqual(left: InputStream, right: InputStream): Boolean {
        val bufA = ByteArray(FILE_COMPARE_BUFFER_BYTES)
        val bufB = ByteArray(FILE_COMPARE_BUFFER_BYTES)
        while (true) {
            val nA = readFully(left, bufA)
            val nB = readFully(right, bufB)
            if (nA != nB) return false
            for (i in 0 until nA) {
                if (bufA[i] != bufB[i]) return false
            }
            if (nA < bufA.size) return true // 双流均到 EOF
        }
    }

    /** 读满缓冲区或直到 EOF；read 的 length 必须是剩余空间，且处理罕见的零长度短读。 */
    private fun readFully(stream: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = stream.read(buf, offset, buf.size - offset)
            when {
                n < 0 -> break
                n == 0 -> {
                    // 防御非标准 InputStream 的零长度返回，避免死循环。
                    val one = stream.read()
                    if (one < 0) break
                    buf[offset++] = one.toByte()
                }
                else -> offset += n
            }
        }
        return offset
    }

    private companion object {
        /** 归档源至少含「库目录/文件名」，禁止清理根下散文件。 */
        const val MIN_ARCHIVE_FILE_SEGMENTS = 2
        /** 库名→目录名的非法字符集（Windows 保留字符全集 \ / : * ? " < > |，覆盖 Linux 的 / 与 \） */
        const val LIBRARY_DIR_ILLEGAL_CHARS = "\\/:*?\"<>|"

        /** 非法字符的统一替换字符 */
        const val LIBRARY_DIR_SANITIZE_CHAR = '_'

        /** 同名冲突序号后缀格式（Locale.ROOT 防区域数字异形；「基名 (1).扩展名」形态） */
        const val CONFLICT_SEQ_SUFFIX = " (%d)"

        /** 同名冲突序号上限（防病态堆叠下的死循环；耗尽按失败处理不覆盖） */
        const val MAX_CONFLICT_SEQ = 10_000

        /** copy 兜底的临时文件名后缀：copyTo 先落 <源名>.part 再改名到位，半截残留
         *  可识别可清理，绝不出现在目标名上（口径见 [copyFallback]） */
        const val ARCHIVE_PART_SUFFIX = ".part"

        /** 内容比对缓冲（64KB：流式比对的读放大与调用次数折中档） */
        const val FILE_COMPARE_BUFFER_BYTES = 64 * 1024
    }
}
