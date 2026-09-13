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

private const val MINUTE_SECONDS = 60L
private const val HOUR_SECONDS = 3600L
private const val DAY_SECONDS = 86400L

/** 紧凑计数「k」档阈值（旧版数字卡口径：千位以上缩写） */
private const val COMPACT_KILO = 1_000L

/** 紧凑计数「M」档阈值（与 k 档同式进位） */
private const val COMPACT_MILLION = 1_000_000L

private const val COMPACT_KILO_DIVISOR = 1000.0
private const val COMPACT_MILLION_DIVISOR = 1_000_000.0
