package media.qimeng.app.core.model

/**
 * 媒体库管理领域模型（U10-6「数据管理」合并入口）。信息架构基准 = Web 文件管理页
 * （web/src/pages/LibraryManagePage.tsx 库表：名称/类型/路径/文件/状态/启用/操作）。
 * 本模块零 Android 依赖（纯 Kotlin，:core:model 模块口径）。
 */

/** 库类型（协议 kind: normal|cos；Library schema 与 POST /libraries body 共用两档） */
enum class LibraryKind {
    /** 常规媒体库 */
    NORMAL,

    /** COS 作者库（按 作者/作品/文件 目录结构扫描建 cos_ 作者，DOMAIN_RULES §6） */
    COS,
}

/** 扫描状态（协议 scanState: idle|scanning|error） */
enum class LibraryScanState { IDLE, SCANNING, ERROR }

/**
 * 媒体库摘要（GET /libraries 的 Library schema 展示子集）。
 * id 协议必填；SDK 映射层对缺 id 条目直接丢弃（对齐 SdkUploadRepository 口径）。
 */
data class LibrarySummary(
    val id: String,
    val name: String,
    /** 服务端本地路径（仅管理端展示，协议注释原文） */
    val rootPath: String,
    /** 库内文件总数（Web「文件」列口径 = fileCount，非 image+video 组合） */
    val fileCount: Int,
    val videoCount: Int,
    val imageCount: Int,
    val scanState: LibraryScanState,
    /**
     * 启用开关。停用仅从浏览/搜索/推荐列表隐藏，资产记录/事件流/统计/磁盘文件
     * 全部保留（migration 0007，ADR-0012）。
     */
    val enabled: Boolean,
    val kind: LibraryKind,
)

/**
 * 文案映射单源（U10-6）：scanState/kind → 展示文案，逐字对齐 Web
 * LibraryManagePage.tsx（scanStateText 行：空闲/扫描中/异常；kind 三元：COS/常规）。
 * 协议两端双写：Web 端改这两组文案时必须同步本文件（各端各改一份属事故）。
 */

/** 库类型展示文案（Web LibraryManagePage.tsx 行内三元：cos=COS、其余=常规） */
val LibraryKind.displayLabel: String
    get() = when (this) {
        LibraryKind.NORMAL -> KIND_LABEL_NORMAL
        LibraryKind.COS -> KIND_LABEL_COS
    }

/** 扫描状态展示文案（Web LibraryManagePage.tsx scanStateText 口径） */
val LibraryScanState.displayLabel: String
    get() = when (this) {
        LibraryScanState.IDLE -> SCAN_STATE_LABEL_IDLE
        LibraryScanState.SCANNING -> SCAN_STATE_LABEL_SCANNING
        LibraryScanState.ERROR -> SCAN_STATE_LABEL_ERROR
    }

private const val KIND_LABEL_NORMAL = "常规"
private const val KIND_LABEL_COS = "COS"

private const val SCAN_STATE_LABEL_IDLE = "空闲"
private const val SCAN_STATE_LABEL_SCANNING = "扫描中"
private const val SCAN_STATE_LABEL_ERROR = "异常"
