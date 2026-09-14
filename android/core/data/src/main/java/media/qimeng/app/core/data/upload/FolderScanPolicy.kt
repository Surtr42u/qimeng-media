package media.qimeng.app.core.data.upload

import media.qimeng.app.core.model.UploadItem

/**
 * 选文件夹上传的纯逻辑（U10-6c）：扫描结果契约 / 扩展名过滤 / 单次上限截断。
 * 本文件零 Android import（JVM 单测锁定）；SAF 枚举本体见 [SafFolderScanner]。
 */

/**
 * 文件夹扫描出网端口。treeUri 用 String 承载（与 UploadRepository.describe 同口径）：
 * Android Uri/DocumentsContract 解析收口在实现层，接口保持纯字符串契约便于 VM 无
 * Android import、JVM 单测注入 fake。
 */
interface FolderScanner {

    /**
     * 递归枚举 [treeUri] 指向的文件夹（即用即弃，不 takePersistableUriPermission——
     * M4-5 OpenMultipleDocuments 先例），产出可上传文件清单与跳过/截断统计。
     */
    suspend fun scan(treeUri: String): FolderScanResult
}

/** 文件夹扫描结果（U10-6c 拍板口径②③的载体） */
data class FolderScanResult(
    /** 可上传文件（relativeDir = 「所选文件夹名/子路径」；大小未知 -1 交服务端 413 兜底） */
    val files: List<UploadItem>,
    /** 枚举到的非媒体扩展名文件数（跳过不照传，口径②：服务端白名单外必 400） */
    val skippedCount: Int,
    /** 触达单次上限发生截断（口径③，配 [totalUploadable] 生成「已选前 N 个，共 M 个」） */
    val truncated: Boolean,
    /** 文件夹内可上传文件总数（含截断未收录部分） */
    val totalUploadable: Int,
) {
    companion object {
        val EMPTY = FolderScanResult(files = emptyList(), skippedCount = 0, truncated = false, totalUploadable = 0)
    }
}

/**
 * 扫描收录器：收集可上传文件 + 计数跳过/总数，超 [maxFiles] 后只计数不再收集
 * （口径③）。纯类（无 IO/Android），JVM 单测锁定截断与计数。
 */
class UploadableFileSink(private val maxFiles: Int = FolderScanPolicy.MAX_FOLDER_FILES) {

    private val collected = mutableListOf<UploadItem>()

    /** 枚举到的非媒体文件数 */
    var skippedCount: Int = 0
        private set

    /** 可上传文件总数（含超出 [maxFiles] 未收录的） */
    var totalUploadable: Int = 0
        private set

    /** 是否截断（总数突破上限即真，哪怕只超 1 个） */
    val truncated: Boolean get() = totalUploadable > maxFiles

    fun addUploadable(item: UploadItem) {
        totalUploadable++
        if (collected.size < maxFiles) collected += item
    }

    fun addSkipped() {
        skippedCount++
    }

    fun toResult(): FolderScanResult =
        FolderScanResult(
            files = collected.toList(),
            skippedCount = skippedCount,
            truncated = truncated,
            totalUploadable = totalUploadable,
        )
}

/** 选文件夹上传的纯规则集合（口径来源见各成员注释） */
object FolderScanPolicy {

    /**
     * 单次文件夹上传的文件数上限。为什么客户端截断：每文件 = 串行链上一条 WorkManager
     * 任务（UNIQUE_WORK_NAME 链过长拖慢调度与观测）+ 全量清单常驻扫描内存。
     */
    const val MAX_FOLDER_FILES = 1000

    /**
     * 可上传扩展名（不含点、小写）。口径 = 服务端白名单的唯一事实源
     * server/internal/filing/upload.go:30-43（allowedExtensions）：
     * jpg/jpeg/png/gif/webp/avif + mp4/m4v/mkv/webm/mov/avi（大小写不敏感）。
     * 为什么客户端先滤：口径②（非媒体文件跳过+计数提示，不照传吃服务端 400）；
     * 只滤扩展名不做内容嗅探——魔数交叉校验仍是服务端第②道权威。
     */
    private val UPLOADABLE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "avif",
        "mp4", "m4v", "mkv", "webm", "mov", "avi",
    )

    /** 扩展名白名单判定（大小写不敏感；无扩展名 = 不可上传）。纯函数，单测锁定。 */
    fun isUploadableName(displayName: String): Boolean {
        val dot = displayName.lastIndexOf('.')
        if (dot < 0) return false
        return displayName.substring(dot + 1).lowercase() in UPLOADABLE_EXTENSIONS
    }
}
