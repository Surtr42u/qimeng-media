package media.qimeng.app.core.model

/**
 * 本地媒体库条目（MediaStore Images+Video 查询映射；内置相册式选择器的数据面模型，
 * 2026-09-25 拍板：上传选取弃系统 SAF 选择器改 App 内置相册式选择器）。
 * uri 用 String 承载：本模块零 Android 依赖，content:// 形态由 :core:data 与 Android 层互转。
 */
data class LocalMediaItem(
    /** MediaStore 记录的 content:// URI 字符串（读权限随媒体运行时权限授予，跨进程存活） */
    val uri: String,
    /** 展示名（MediaStore DISPLAY_NAME，同时作为上传 filename 口径） */
    val displayName: String,
    /** 字节数；-1 = 未知（此时超限本地拦截跳过、由服务端 413 兜底，UploadItem 同口径） */
    val sizeBytes: Long,
    /** true = 视频来源（MediaStore.Video），false = 图片来源（MediaStore.Images） */
    val isVideo: Boolean,
    /** 视频时长毫秒（仅视频有值；图片 null） */
    val durationMs: Long? = null,
    /** 所属相册（bucket）显示名；null = 无归属信息 */
    val bucketName: String? = null,
)

/** 本地相册（bucket）聚合条目（选择器过滤 chips 数据源；itemCount 供角标副文案） */
data class LocalMediaBucket(
    val name: String,
    /** 该相册在已查询范围内的条目数（同一查询窗口内计数，非全库精确值） */
    val itemCount: Int,
)
