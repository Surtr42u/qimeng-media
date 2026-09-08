package media.qimeng.app.feature.stats

import java.util.Locale
import kotlin.math.roundToLong

/**
 * 统计页数值展示辅助（纯函数，JVM 单测锁定；Locale 显式 US 保证分组符稳定不随设备语言漂移，
 * 与旧 toDisplayText 同口径）。
 */

/** 整数千分位（数字卡/排行数值） */
internal fun Int.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/** 长整数千分位（窗口聚合值可能超 Int 域：浏览时长秒数累加） */
internal fun Long.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/**
 * 浏览时长秒数 → 人读时长（数字卡「总浏览时长」格；GUIDE_UI 未锁定格式，
 * 取「大致相似」口径：秒/分/小时/天四档，>1 小时保留 1 位小数）。
 */
internal fun formatDurationSeconds(totalSeconds: Long): String = when {
    totalSeconds < MINUTE_SECONDS -> "${totalSeconds}秒"
    totalSeconds < HOUR_SECONDS -> "${totalSeconds / MINUTE_SECONDS}分钟"
    totalSeconds < DAY_SECONDS -> {
        val hours = totalSeconds.toDouble() / HOUR_SECONDS
        trimTrailingZero(hours) + "小时"
    }
    else -> {
        val days = totalSeconds.toDouble() / DAY_SECONDS
        trimTrailingZero(days) + "天"
    }
}

/** 1 位小数但整数值去掉 ".0"（12.0→"12"，12.5→"12.5"） */
private fun trimTrailingZero(value: Double): String {
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
