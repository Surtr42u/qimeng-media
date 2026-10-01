package media.qimeng.app.core.data.upload

import java.io.File
import java.util.Locale
import media.qimeng.app.core.data.repository.InboxFileStore
import media.qimeng.app.core.model.LibraryChoice

/**
 * 归档文件夹一键重传的扫描产物（2026-10-01 归档一键上传）：归档根里已按库名分好类的
 * 存量文件，按文件夹名反向匹配服务器库后作为待上传条目清单（目标库随条目自带，
 * 不依赖上传页批次默认库）。
 */

/** 归档文件夹内一个待重传条目（folder -> 条目；目标库已解析） */
data class ArchiveBatchItem(
    /** 源文件（绝对路径；入队后作为路径类 uri 直传，worker 侧 UploadRules.isAbsoluteFilePath 命中） */
    val file: File,
    /** 目标库 id（按文件夹名匹配到的库；入队分组键） */
    val libraryId: String,
    /** 目标库展示名（随载荷入队，供服务端与归档分派使用） */
    val libraryName: String,
    /**
     * 匹配文件夹内的相对子目录（'/' 分隔、不含首尾斜杠；文件直接在文件夹根 = 空串）。
     * 入队后经 UploadItem.relativeDir 语义拼成任务自己的 dir（沿用「库内子目录」口径）。
     */
    val relDir: String,
)

/** 归档一键重传扫描结果（全量快照；扫描在 IO 协程一次完成，结果不可变） */
data class ArchiveBatchScanResult(
    /** 待上传条目（已按「文件夹名 + 相对路径」稳定排序） */
    val items: List<ArchiveBatchItem>,
    /** 未匹配文件夹清单（文件夹名, 原因文案）；原因常量见 [REASON_NO_LIBRARY]/[REASON_AMBIGUOUS] */
    val unmatchedFolders: List<Pair<String, String>>,
    /** 待上传条目总字节数（File.length() 求和） */
    val totalBytes: Long,
    /** 跳过计数：非媒体扩展名文件 + 点前缀文件/目录（跳过原因不逐条展示，只报总数） */
    val skippedCount: Int,
)

/** 未匹配原因：文件夹名对不上任何库（精确与 sanitize 双口径都未命中） */
const val ARCHIVE_BATCH_REASON_NO_LIBRARY = "未找到同名库"

/** 未匹配原因：多个库经 sanitize 后与文件夹同名，无法确定唯一目标库 */
const val ARCHIVE_BATCH_REASON_AMBIGUOUS = "多个库匹配，无法确定"

/**
 * 扫描归档根，产出「一键重传」条目清单（2026-10-01 归档一键上传）。
 * 纯函数式扫描（只读 File 系统，不改任何文件），JVM 临时目录直测（ArchiveBatchScanTest）。
 *
 * 规则（与服务端 ADR-0030 同一目录规范的反向映射）：
 * - 归档根不存在/非目录 → 空结果（items 与 unmatchedFolders 皆空）；
 * - 只看一级子文件夹，点前缀目录整目录跳过（隐藏目录不是库归档区，也不算未匹配）；
 * - 文件夹名匹配库：先精确等于 library.name，再拿每个库名过 [InboxFileStore.sanitizeLibraryDirName]
 *   比对（归档写入侧正是按 sanitize(库名) 建目录，见 InboxFileStore.archiveToLibraryRoot——
 *   读侧用同一函数反向匹配，单一事实源，禁止复制 sanitize 规则）；去重后 0 命中记
 *   [ARCHIVE_BATCH_REASON_NO_LIBRARY]、多命中记 [ARCHIVE_BATCH_REASON_AMBIGUOUS]；
 * - 命中文件夹内递归收集文件：点前缀文件/目录跳过计入 skippedCount（目录不递归）；
 *   扩展名不在媒体白名单（[MEDIA_EXTENSIONS]，与 server/internal/filing/upload.go
 *   allowedExtensions 双写，服务端清单改动须同步此处，反之亦然）计入 skippedCount；
 *   其余进 items（relDir = 文件夹内相对子目录，'/' 统一分隔，文件夹根为空串）；
 * - 归档根下散落的普通文件（不属任何库文件夹）不属本功能语义，静默忽略。
 */
fun scanArchiveForUpload(root: File, libraries: List<LibraryChoice>): ArchiveBatchScanResult {
    if (!root.isDirectory) return ArchiveBatchScanResult(emptyList(), emptyList(), 0L, 0)
    // InboxFileStore 无状态（全部方法无副作用、构造零依赖），此处直接实例化以复用
    // sanitize 单一实现——扫描是一次性动作，不经 DI 注入（保持本函数纯函数签名）
    val sanitizer = InboxFileStore()
    val items = mutableListOf<Pair<String, ArchiveBatchItem>>() // (文件夹名, 条目)——排序键需要文件夹名
    val unmatchedFolders = mutableListOf<Pair<String, String>>()
    var skippedCount = 0
    val subDirs = root.listFiles(File::isDirectory).orEmpty()
        .filter { !it.name.startsWith(DOT_PREFIX) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    for (dir in subDirs) {
        // 匹配候选：精确命中 ∪ sanitize 命中，按库 id 去重（同一库两种口径同时命中只算一次）
        val candidates = (libraries.filter { it.name == dir.name } +
            libraries.filter { sanitizer.sanitizeLibraryDirName(it.name) == dir.name })
            .distinctBy { it.id }
        when {
            candidates.isEmpty() -> unmatchedFolders += dir.name to ARCHIVE_BATCH_REASON_NO_LIBRARY
            candidates.size > 1 -> unmatchedFolders += dir.name to ARCHIVE_BATCH_REASON_AMBIGUOUS
            else -> {
                val library = candidates.single()
                skippedCount += collectMediaFiles(dir, relDir = "") { file, relDir ->
                    items += dir.name to ArchiveBatchItem(file, library.id, library.name, relDir)
                }
            }
        }
    }
    // 稳定排序：先按文件夹名、再按文件夹内相对路径（relDir + 文件名）
    val sorted = items.sortedWith(
        compareBy({ it.first }, { it.second.relDir }, { it.second.file.name }),
    ).map { it.second }
    return ArchiveBatchScanResult(
        items = sorted,
        unmatchedFolders = unmatchedFolders.sortedBy { it.first },
        totalBytes = sorted.sumOf { it.file.length() },
        skippedCount = skippedCount,
    )
}

/**
 * 递归收集 [dir] 内的白名单媒体文件到 [sink]，返回跳过计数。
 * 点前缀文件/目录（不递归）与非媒体扩展名文件计入跳过；目录递归时把目录名拼进
 * relDir（'/' 分隔、无首尾斜杠；文件夹根为空串）。
 */
private fun collectMediaFiles(dir: File, relDir: String, sink: (File, String) -> Unit): Int {
    var skipped = 0
    val entries = dir.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }
    for (entry in entries) {
        // 点前缀 = 隐藏文件/隐藏目录：系统相册扫不到的同类口径（见 InboxFileStore 类注释），
        // 一律跳过且不递归隐藏目录
        if (entry.name.startsWith(DOT_PREFIX)) {
            skipped++
            continue
        }
        if (entry.isDirectory) {
            val childRelDir = if (relDir.isEmpty()) entry.name else "$relDir/${entry.name}"
            skipped += collectMediaFiles(entry, childRelDir, sink)
            continue
        }
        if (!isAllowedMediaExtension(entry.name)) {
            skipped++
            continue
        }
        sink(entry, relDir)
    }
    return skipped
}

/** 文件名是否落在媒体白名单（大小写不敏感；点前缀隐藏名与无扩展名文件不入白名单） */
private fun isAllowedMediaExtension(fileName: String): Boolean {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return false
    return fileName.substring(dot + 1).lowercase(Locale.ROOT) in MEDIA_EXTENSIONS
}

/**
 * 媒体扩展名白名单（小写、不含点）。
 * 协议联动双写：与 server/internal/filing/upload.go allowedExtensions 逐项一致
 * （图片 jpg/jpeg/png/gif/webp/avif + 视频 mp4/m4v/mkv/webm/mov/avi；m4v 与 mp4 同为
 * ISO BMFF 容器取服务端超集口径）。服务端清单改动须同步此处，反之亦然。
 */
private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "avif",
    "mp4", "m4v", "mkv", "webm", "mov", "avi",
)

/** 点前缀隐藏名判定前缀（与归档链路「隐藏目录不进相册」口径一致） */
private const val DOT_PREFIX = "."
