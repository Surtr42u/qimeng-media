package media.qimeng.app.core.model

/**
 * GET /stats/overview 响应（C2 拍板：总文件数/总占用空间静态不随时段联动）。
 * 字段与 openapi StatsOverview schema 一一对应（协议侧改动须同步此处）。
 * N3 协议批新增：sourceNormalCount/sourceCosCount（来源库存构成）、
 * avgViewsPerFile（窗口内平均浏览次数，分母 0 → null——「—」占位语义）。
 * 2026-09-14 协议批新增：分类型/分来源大小两组求和（DOMAIN_RULES §5：
 * image 不含动图、per_type 三键与 per_source 两键各自之和 = totalSizeBytes）。
 */
data class StatsOverviewValues(
    val totalFiles: Int,
    val imageCount: Int,
    val videoCount: Int,
    val totalSizeBytes: Long,
    val todayViews: Int,
    val totalViews: Long,
    /** 常规来源库存（不关联 COS 作者的现存资产数；协议批 #31b） */
    val sourceNormalCount: Int = 0,
    /** COS 来源库存（关联 COS 作者的现存资产数） */
    val sourceCosCount: Int = 0,
    /** 平均浏览次数 = 窗口 open 事件总数 ÷ 窗口内至少一次 open 的不同现存文件数；分母 0 → null */
    val avgViewsPerFile: Double? = null,
    /** 图片资产大小求和（字节；不含动图——物理占用口径，与 imageCount 计数不同） */
    val imageSizeBytes: Long = 0,
    /** 视频资产大小求和（字节） */
    val videoSizeBytes: Long = 0,
    /** 动图资产大小求和（字节） */
    val animatedImageSizeBytes: Long = 0,
    /** 常规来源（不关联 COS 作者）资产大小求和（字节；谓词同 sourceNormalCount） */
    val normalSizeBytes: Long = 0,
    /** COS 来源（关联 COS 作者）资产大小求和（字节；谓词同 sourceCosCount） */
    val cosSizeBytes: Long = 0,
)

/**
 * GET /stats/trends 单桶（openapi TrendBucket 映射；label 服务端已按 DOMAIN_RULES §5
 * 口径生成——周 MM/dd / 月 MM月·yy/MM / 季 yy/Qn，运行时核验与规格逐字一致，客户端零重排）。
 */
data class TrendPoint(
    val label: String,
    val viewCount: Int,
    val playCount: Int,
    val seconds: Int,
)

/**
 * GET /stats/most-viewed 单条（N3 协议批 #31c；N4 消费批接线）。
 * metric=views 时 value=窗口内 open 次数；metric=seconds 时 value=窗口内 dwell 秒数累计。
 */
data class MostViewedEntry(
    val assetId: String,
    val fileName: String,
    /** MediaType 协议值（image/video/animated_image） */
    val mediaType: String,
    /** 签名缩略图相对路径（与 AssetSummary.thumbUrl 同一签名机制；缺省 null） */
    val thumbUrl: String?,
    /** 排行值（次数或秒数，随 metric 而定） */
    val value: Int,
)

/** GET /stats/top-authors 单条（窗口内该作者关联资产 open 次数倒序） */
data class TopAuthorEntry(
    val authorId: String,
    val displayName: String,
    val views: Int,
)

/** GET /stats/top-tags 单条（窗口内带该标签资产 open 次数倒序） */
data class TopTagEntry(
    val tag: String,
    val views: Int,
)

/** 缩略图覆盖进度（GET /thumbnails/progress；缓存进度页数据源，2026-09-15 批） */
data class ThumbnailCacheProgress(
    val totalAssets: Int,
    val thumbsOnDisk: Int,
) {
    /**
     * 0~1 覆盖率（分母为 0 视为已满，避免除零）。2026-09-19 批S4 勘误：分子是缓存目录
     * 落盘**文件数**（多档并存按文件计 + 已删资产遗留孤儿，真库实测可 > 资产数），
     * 原实现不钳制会让进度比例越界（>1）；钳到 1f = 覆盖语义下的「已满」降级。
     */
    val fraction: Float
        get() = if (totalAssets <= 0) 1f else (thumbsOnDisk.toFloat() / totalAssets).coerceIn(0f, 1f)
}
