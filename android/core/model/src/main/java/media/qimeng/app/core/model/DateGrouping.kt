package media.qimeng.app.core.model

/**
 * 日期分组（DOMAIN_RULES §8 逐字口径；Web lib/format.ts dateLabel 同源复刻）：
 * 今天 / 昨天 / 距今 2~6 天→周X（周一~周日）/ 更早→yyyy-MM-dd / 无时间→未知日期。
 *
 * @param epochMs 时间戳（毫秒）；null/负值视为「未知日期」
 * @param nowMs 当前时间（由调用方注入，纯函数可测——不偷读系统时钟）
 */
fun dateLabel(epochMs: Long?, nowMs: Long): String {
    if (epochMs == null || epochMs < 0) return UNKNOWN_DATE_LABEL
    val today = startOfDay(nowMs)
    val day = startOfDay(epochMs)
    val diffDays = Math.round((today - day) / MS_PER_DAY.toDouble())
    return when {
        diffDays == 0L -> "今天"
        diffDays == 1L -> "昨天"
        diffDays in 2..6 -> WEEKDAY_LABELS[dayOfWeekIndex(day)]
        else -> formatYmd(day)
    }
}

/** 「未知日期」组标签（无时间的条目归此组；DOMAIN_RULES §8，恒排最后） */
const val UNKNOWN_DATE_LABEL = "未知日期"

/** 网格分组段：组头渲染一次、组内保持列表原序 */
data class GridSection(
    val label: String,
    val items: List<MediaAsset>,
)

/**
 * 按日期标签分组（纯函数）：同标签归并同组（组头只渲染一次），
 * 组间按组首时间降序，「未知日期」组恒排最后（Web 相册页 groups 同款语义，
 * 组键用什么时间由调用方决定：相册/收藏/搜索=modifiedAt，历史页=lastViewedAt）。
 */
fun List<MediaAsset>.groupByDateLabel(nowMs: Long, timestamp: (MediaAsset) -> Long?): List<GridSection> {
    val byLabel = LinkedHashMap<String, MutableList<MediaAsset>>()
    for (asset in this) {
        byLabel.getOrPut(dateLabel(timestamp(asset), nowMs)) { mutableListOf() }.add(asset)
    }
    return byLabel.entries
        .map { (label, assets) -> label to assets }
        .sortedWith(
            compareBy<Pair<String, List<MediaAsset>>> { it.first == UNKNOWN_DATE_LABEL }
                .thenByDescending { pair ->
                    pair.second.maxOfOrNull { timestamp(it) ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
                },
        )
        .map { (label, assets) -> GridSection(label, assets) }
}

/** 一天的起点（本地时区；与 Web new Date(y,m,d) 同义） */
private fun startOfDay(epochMs: Long): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = epochMs
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** Calendar.DAY_OF_WEEK（周日=1）→ WEEKDAY_LABELS 下标（周一开头） */
private fun dayOfWeekIndex(startOfDayMs: Long): Int {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = startOfDayMs
    return (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private const val MS_PER_DAY = 86_400_000L

private fun formatYmd(startOfDayMs: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = startOfDayMs
    val month = (calendar.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "${calendar.get(java.util.Calendar.YEAR)}-$month-$day"
}
