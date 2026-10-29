package media.qimeng.app.core.model

/** 浏览回顾直接承接服务端结果，客户端不重新选择窗口或计算兴趣份额。 */
data class BrowsingReviewValues(
    val range: String,
    val fromDay: String,
    val toDay: String,
    val previousFromDay: String,
    val previousToDay: String,
    val viewCount: Long,
    val uniqueFiles: Long,
    val firstViews: Long,
    val repeatViews: Long,
    val activeDays: Long,
    val previousViewCount: Long,
    val comparisonAvailable: Boolean,
    val interests: List<ReviewInterestValues>,
    val hours: List<ReviewHourValues>,
    val seconds: Long, val imageSeconds: Long, val videoSeconds: Long, val animatedSeconds: Long,
)

data class ReviewInterestValues(
    val kind: String, val id: String, val name: String,
    val currentViews: Long, val previousViews: Long,
    val currentShare: Double, val previousShare: Double, val changePoints: Double,
)

data class ReviewHourValues(val hour: Int, val views: Long, val seconds: Long = 0, val imageSeconds: Long = 0, val videoSeconds: Long = 0, val animatedSeconds: Long = 0)
