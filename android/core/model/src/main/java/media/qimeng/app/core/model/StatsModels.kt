package media.qimeng.app.core.model

/**
 * GET /stats/overview 响应（C2 拍板：数字卡静态不随时段联动——overview 本身无 range 参数）。
 * 字段与 openapi StatsOverview schema 一一对应（协议侧改动须同步此处）。
 */
data class StatsOverviewValues(
    val totalFiles: Int,
    val imageCount: Int,
    val videoCount: Int,
    val totalSizeBytes: Long,
    val todayViews: Int,
    val totalViews: Long,
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
