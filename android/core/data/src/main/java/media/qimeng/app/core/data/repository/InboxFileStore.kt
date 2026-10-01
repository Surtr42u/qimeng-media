package media.qimeng.app.core.data.repository

import android.os.Build
import android.os.Environment
import java.io.File
import java.io.InputStream
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
     * 归档：源文件 renameTo 到同目录 uploaded/ 子目录（失败不抛，返回 false）。
     * 消费方为 worker 的收件箱来源回退归档分支（UploadWorker.archiveNote，红线链路）。
     */
    fun archiveToUploaded(sourcePath: String): Boolean {
        val file = File(sourcePath)
        if (!file.isFile) return false
        val parent = file.parentFile ?: return false
        val uploadedDir = File(parent, StagingRepository.UPLOADED_DIR_NAME)
        if (!uploadedDir.isDirectory && !uploadedDir.mkdirs()) return false
        return try {
            file.renameTo(File(uploadedDir, file.name))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 归档到上传归档文件夹（2026-09-28 上传归档文件夹功能）：源文件移入
     * <archiveRoot>/<sanitize(库名)>/<目标文件名>，供用户手动复制同步到电脑。
     * - 库名 sanitize：Windows/Linux 目录名非法字符统一替换为 _（归档根会被用户复制到
     *   电脑、常为 Windows，按更严的 Windows 保留字符集处理保证跨平台可复制）；
     *   空/空白库名返回 false（无目录名可建）；
     * - 目标目录不存在时连归档根一并创建（mkdirs）；
     * - 同名冲突：内容不同则源文件名加序号 (1)(2)… 找首个空位（绝不覆盖——归档区文件
     *   可能已被用户整理过，静默覆盖等于丢文件）；内容相同（重复上传同一文件）则删旧
     *   放新（结果与源一致，任何一侧不静默丢）；
     * - 优先 renameTo（同卷快），失败 fallback「copy 到 .part 临时名 + 改名到位 +
     *   删源」（跨挂载点兜底；[copyFallback]）。
     * 失败不抛、返回 false（调用方只记提示不阻断上传完成，与 [archiveToUploaded] 同口径）。
     */
    fun archiveToLibraryRoot(archiveRoot: String, libraryName: String, sourceFile: File): Boolean {
        if (!sourceFile.isFile) return false
        val dirName = sanitizeLibraryDirName(libraryName) ?: return false
        val targetDir = File(archiveRoot, dirName)
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
        var target = File(targetDir, sourceFile.name)
        if (target.exists() && !sameContent(sourceFile, target)) {
            target = resolveConflictName(targetDir, sourceFile.name) ?: return false
        }
        return try {
            if (target.exists() && !target.delete()) return false
            // 删旧放新（同名同内容）与 renameTo 的覆盖语义在 Windows JVM 上均不可依赖，
            // 上面显式 delete 后这里 renameTo/copy 落到的是空位路径
            sourceFile.renameTo(target) || copyFallback(sourceFile, target)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * copy 兜底（renameTo 跨挂载点失败时）：先 copyTo 到同目录 .part 临时名，成功后
     * renameTo 改名到位。为什么不能直接 copyTo 目标名：copyTo 半途失败（磁盘满/进程
     * 被杀）会在归档目录留下半截同名目标文件，下次同名归档因内容不等走序号位、垃圾
     * 永留；.part 临时名则把残留收敛到可识别可清理的形态（finally 兜底删除）。
     * .part 改名失败再试「删目标位 + 二次改名」（防御并发占位），仍失败返回 false；
     * 删源失败同样按失败上报（false 提醒用户手动处理源文件，有意语义，非疏漏）。
     */
    private fun copyFallback(sourceFile: File, target: File): Boolean {
        val part = File(target.parentFile, sourceFile.name + ARCHIVE_PART_SUFFIX)
        try {
            sourceFile.copyTo(part, overwrite = true)
            if (!part.renameTo(target)) {
                if (target.exists() && !target.delete()) return false
                if (!part.renameTo(target)) return false
            }
            return sourceFile.delete()
        } finally {
            // 成功路径 part 已改名移走、delete 是 no-op；异常/失败路径清 .part 残留
            part.delete()
        }
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
        return sanitized.takeIf { it.isNotEmpty() }
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

    /** 字节级内容比对：长度先短路；流式比对不整载内存（归档对象可能是大视频）。
     *  读取异常按「不同内容」处理（走加序号路径，宁可多留一份也不误判相同去覆盖）。 */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return try {
            a.inputStream().use { left ->
                b.inputStream().use { right ->
                    val bufA = ByteArray(FILE_COMPARE_BUFFER_BYTES)
                    val bufB = ByteArray(FILE_COMPARE_BUFFER_BYTES)
                    while (true) {
                        val nA = readFully(left, bufA)
                        val nB = readFully(right, bufB)
                        if (nA != nB) return@use false
                        // 末段短读块也要比对（只比有效前缀）；满块整块比对
                        val chunkEqual = if (nA == bufA.size) {
                            bufA.contentEquals(bufB)
                        } else {
                            bufA.copyOf(nA).contentEquals(bufB.copyOf(nB))
                        }
                        if (!chunkEqual) return@use false
                        if (nA < bufA.size) break // 双流同步到 EOF（起手长度相等，短读即共同结尾）
                    }
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 读满整个缓冲（不足继续读，EOF 停止）；返回实读字节数。InputStream.read 单次
     *  可能短读，比对必须按「读满」口径对齐两侧进度，否则随机短读会误判不同内容 */
    private fun readFully(stream: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = stream.read(buf, offset, buf.size)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    private companion object {
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
