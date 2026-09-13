package media.qimeng.app.feature.stats

import java.util.Locale
import kotlin.math.roundToLong

/**
 * 统计页数值展示辅助（纯函数，JVM 单测锁定；Locale 显式 US 保证分组符稳定不随设备语言漂移，
 * 与旧 toDisplayText 同口径）。
 */

/** 整数千分位（排行数值/常看行数值，旧版千分位口径不动） */
internal fun Int.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/** 长整数千分位（窗口聚合值可能超 Int 域：浏览时长秒数累加） */
internal fun Long.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/**
 * 数字卡紧凑计数（2026-09-13 用户终裁对齐旧版数字卡：总文件数 6339 →「6.3k」）。
 * 阈值/后缀对照旧版截图口径：≥[COMPACT_MILLION] → x.yM、≥[COMPACT_KILO] → x.yk
 * （1 位小数去尾零，同 [trimTrailingZero] 档）；<1000 裸数。常看行小数值不受影响。
 */
internal fun formatCountCompact(value: Long): String = when {
    value >= COMPACT_MILLION -> trimTrailingZero(value / COMPACT_MILLION_DIVISOR) + "M"
    value >= COMPACT_KILO -> trimTrailingZero(value / COMPACT_KILO_DIVISOR) + "k"
    else -> value.toString()
}

/** Int 转发（总文件数为 Int 域；与 [toDisplayText] 同款双型口径） */
internal fun formatCountCompact(value: Int): String = formatCountCompact(value.toLong())

/**
 * 浏览时长秒数 → 人读时长（数字卡「总浏览时长」格；GUIDE_UI 未锁定格式，
 * 取「大致相似」口径：秒/分/小时/天四档，>1 小时保留 1 位小数。
 * 分档后缀 2026-09-13 对齐旧版：「38分」非「38分钟」）。
 */
internal fun formatDurationSeconds(totalSeconds: Long): String = when {
    totalSeconds < MINUTE_SECONDS -> "${totalSeconds}秒"
    totalSeconds < HOUR_SECONDS -> "${totalSeconds / MINUTE_SECONDS}分"
    totalSeconds < DAY_SECONDS -> {
        val hours = totalSeconds.toDouble() / HOUR_SECONDS
        trimTrailingZero(hours) + "小时"
    }
    else -> {
        val days = totalSeconds.toDouble() / DAY_SECONDS
        trimTrailingZero(days) + "天"
    }
}

/** 1 位小数但整数值去掉 ".0"（12.0→"12"，12.5→"12.5"）；internal 供平均浏览次数格式化复用 */
internal fun trimTrailingZero(value: Double): String {
    val rounded = (value * 10).roundToLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}

// ==================== 统计详情页专用（2026-09-13 视觉复刻批，对齐旧仓库
// StatsDetailFragment/StatsFormatHelper 运行时口径；与主页已锁定函数口径不同，禁止互改） ====================

/**
 * 详情页紧凑计数（旧版 StatsFormatHelper.formatNumber 同款 万/k 双档）：
 * ≥[DETAIL_COMPACT_WAN] → x.y万、≥[DETAIL_COMPACT_KILO] → x.yk、否则裸数。
 * 与 [formatCountCompact]（数字卡 k/M 口径）分档不同——旧版详情页就是 万/k，勿合并。
 * 注意不去尾零（旧版 String.format("%.1f") 原样输出，1000 →「1.0k」）。
 */
internal fun formatCountDetail(value: Int): String = when {
    value >= DETAIL_COMPACT_WAN -> String.format(Locale.US, "%.1f万", value / DETAIL_WAN_DIVISOR)
    value >= DETAIL_COMPACT_KILO -> String.format(Locale.US, "%.1fk", value / DETAIL_KILO_DIVISOR)
    else -> value.toString()
}

/** 详情页大小四档（旧版 StatsFormatHelper.formatSize 同款：GB/MB/KB/B，1 位小数不去尾零） */
internal fun formatSizeDetail(bytes: Long): String = when {
    bytes >= DETAIL_GIGA_BYTES -> String.format(Locale.US, "%.1fGB", bytes / DETAIL_GIGA_BYTES.toDouble())
    bytes >= DETAIL_MEGA_BYTES -> String.format(Locale.US, "%.1fMB", bytes / DETAIL_MEGA_BYTES.toDouble())
    bytes >= DETAIL_KILO_BYTES -> String.format(Locale.US, "%.1fKB", bytes / DETAIL_KILO_BYTES.toDouble())
    else -> "${bytes}B"
}

/**
 * 详情页停留时长三档（旧版 StatsDetailFragment.formatDuration 同款逐字：
 * X小时X分 / X分X秒 / X秒，≤0 →「0秒」）。与 [formatDurationSeconds]（数字卡
 * 四档「38分」口径）是两个函数——主页格式已锁定，禁止互改。
 */
internal fun formatDurationDetail(totalSeconds: Long): String {
    if (totalSeconds <= 0) return DETAIL_ZERO_DURATION_TEXT
    val hours = totalSeconds / HOUR_SECONDS
    val minutes = (totalSeconds % HOUR_SECONDS) / MINUTE_SECONDS
    val secs = totalSeconds % MINUTE_SECONDS
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${secs}秒"
        else -> "${secs}秒"
    }
}

/** 详情页平均浏览次数（旧版 String.format("%.1f") 原样一位小数，不去尾零：3.0 →「3.0」） */
internal fun formatAvgViewsDetail(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * 排行/分布进度条百分比（旧版 RankListAdapter/createMetricItem 同式）：
 * (value/max*100) 截断取整后 coerceIn(1,100)；value≤0 或 max≤0 → 0
 * （陷阱#11：0 值禁止被 coerceIn 抬成 1%，同时防除零）。
 */
internal fun rankProgressPercent(value: Long, maxValue: Long): Int =
    if (maxValue > 0 && value > 0) (value * PERCENT_SCALE / maxValue).toInt().coerceIn(1, PERCENT_SCALE) else 0

/** Int 转发（浏览次数/库存数量等 Int 域值） */
internal fun rankProgressPercent(value: Int, maxValue: Int): Int =
    rankProgressPercent(value.toLong(), maxValue.toLong())

private const val MINUTE_SECONDS = 60L
private const val HOUR_SECONDS = 3600L
private const val DAY_SECONDS = 86400L

/** 详情页紧凑计数「万」档阈值（旧版 formatNumber 口径：万位以上） */
private const val DETAIL_COMPACT_WAN = 10_000

/** 详情页紧凑计数「k」档阈值（旧版 formatNumber 口径：千位以上） */
private const val DETAIL_COMPACT_KILO = 1_000

private const val DETAIL_WAN_DIVISOR = 10_000.0
private const val DETAIL_KILO_DIVISOR = 1_000.0

/** 大小档位进率（旧版 formatSize 同款 1024 进制） */
private const val DETAIL_KILO_BYTES = 1_024L
private const val DETAIL_MEGA_BYTES = 1_024L * 1_024
private const val DETAIL_GIGA_BYTES = 1_024L * 1_024 * 1_024

/** 零时长占位（旧版 formatDuration(0) →「0秒」） */
private const val DETAIL_ZERO_DURATION_TEXT = "0秒"

/** 百分比刻度（进度条 max=100，旧版 ProgressBar max=100 同款） */
private const val PERCENT_SCALE = 100

/** 紧凑计数「k」档阈值（旧版数字卡口径：千位以上缩写） */
private const val COMPACT_KILO = 1_000L

/** 紧凑计数「M」档阈值（与 k 档同式进位） */
private const val COMPACT_MILLION = 1_000_000L

private const val COMPACT_KILO_DIVISOR = 1000.0
private const val COMPACT_MILLION_DIVISOR = 1_000_000.0
