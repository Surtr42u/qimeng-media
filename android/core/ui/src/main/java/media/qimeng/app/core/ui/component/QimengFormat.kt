package media.qimeng.app.core.ui.component

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import media.qimeng.app.core.model.MediaKind

/** 字节换算基数（1024 进制；KB 档留作下界保护，展示主用 MB/GB 两级） */
private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

/** 「万」压缩阈值与除数（Web formatCount 口径：web/src/lib/format.ts:55-63） */
private const val WAN_THRESHOLD = 10_000
private const val WAN_DIVISOR = 10_000.0

/** 万位数值不再保留小数的阈值（≥100万 → 整数万；Web 同款 w>=100 取整） */
private const val WAN_INTEGER_THRESHOLD = 100.0

/** 「万」后缀（中文计数习惯，Web 端同字面量） */
private const val WAN_SUFFIX = "万"

/**
 * 字节容量人读格式（统计页「库容量」/设置页「已用缓存」共用；1024 进制两级单位）。
 * Locale 显式 US：小数点分组符不随设备语言漂移（验收对照 curl 数字时必须稳定）。
 */
fun formatBytesHumanReadable(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> "—"
    bytes >= GB -> String.format(Locale.US, "%.2fGB", bytes.toDouble() / GB)
    bytes >= MB -> String.format(Locale.US, "%.1fMB", bytes.toDouble() / MB)
    else -> "${bytes}B"
}

/**
 * 展示计数（详情 meta 行浏览/播放、点赞数共用）。
 * 口径逐条对照 Web formatCount（web/src/lib/format.ts:55-63）：null → "0"；
 * ≥10000 → x.x万（截尾 ".0"；≥100万 整数万）；其余千分位分组（分组符与 Web toLocaleString
 * 中文环境一致为逗号，Locale.US 保证稳定）。
 */
fun formatCount(n: Int?): String {
    if (n == null) return "0"
    if (n >= WAN_THRESHOLD) {
        val w = n / WAN_DIVISOR
        val s = if (w >= WAN_INTEGER_THRESHOLD) {
            // BigDecimal 半升取整替代 JS Math.round（银行家舍入差异在此量级无感知，行为对齐取整意图）
            BigDecimal(w).setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            String.format(Locale.US, "%.1f", w).removeSuffix(".0")
        }
        return "$s$WAN_SUFFIX"
    }
    return String.format(Locale.US, "%,d", n)
}

/**
 * 短日期「M-D」（月份/日不补零；详情 meta 行文件修改时间用）。
 * 口径对照 Web formatShortDate（web/src/lib/format.ts:18-23）：空值/非法 → 空串；
 * Web 入参 ISO 串、Android 入参 mapper 已转的 epoch 毫秒，时区同取设备本地。
 */
fun formatShortDate(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return ""
    return try {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        "${date.monthValue}-${date.dayOfMonth}"
    } catch (_: Exception) {
        ""
    }
}

/**
 * 详情 meta 行专用，口径 = web format.ts formatBytes：
 * ≥1GiB → x.x GB、≥1MiB → x.x MB、≥1KiB → 整数 KB、否则 n B；null/≤0 → 0 B
 * （Web 详情 sizeBytes ?? 0 直接进 formatBytes，空值语义是「0 B」而非占位符；
 * GB 档一位小数、单位与数字间带空格，均与 [formatBytesHumanReadable]（统计/设置页冻结口径）不同）。
 * Locale.US：小数点不随设备语言漂移；舍入 HALF_UP 对齐 JS toFixed。
 */
fun formatBytesForDetail(bytes: Long?): String {
    val n = if (bytes != null && bytes > 0) bytes else 0L
    return when {
        n >= GB -> String.format(Locale.US, "%.1f GB", n.toDouble() / GB)
        n >= MB -> String.format(Locale.US, "%.1f MB", n.toDouble() / MB)
        n >= KB -> String.format(Locale.US, "%.0f KB", n.toDouble() / KB)
        else -> "$n B"
    }
}

// ---------------- 详情信息 Sheet 专用（任务X X3 旧版移植扩行，纯 JVM 无资源依赖） ----------------

/**
 * 文件扩展名截取（详细信息 Sheet「类型」行用）：取最后一个「.」之后段并归一小写
 * （大小写不敏感口径：IMG.JPG → jpg）；无「.」/ 以「.」结尾 → 空串。
 * 隐藏文件（.bashrc）按无扩展名处理（首字符前的「.」非扩展名分隔——substringAfterLast
 * 对 ".bashrc" 取得 "bashrc"，故先剥前导点再截取）。
 */
fun fileExtension(fileName: String): String {
    val name = fileName.removePrefix(".")
    return name.substringAfterLast('.', "").lowercase()
}

/**
 * 媒体类型中文文案（MediaKind 三档全枚举；DOMAIN_RULES §3「音频」不存在）。
 * 中文用字面量而非 string 资源：本函数族是纯 JVM 可测的格式化层，不依赖 Android 资源
 * （同 [formatCount] 的「万」字面量口径）。
 */
fun mediaTypeLabel(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "图片"
    MediaKind.ANIMATED_IMAGE -> "动图"
    MediaKind.VIDEO -> "视频"
}

/**
 * 详细信息 Sheet「类型」行整行文案（任务X X3 拍板口径）：「视频 · mp4」式 =
 * 中文媒体类型 + 小写扩展名（分隔「 · 」）；无扩展名仅出类型名（旧版「unknown」兜底
 * 不移植——类型行媒体类型恒有值，扩展名缺失不造词）。
 */
fun detailTypeLabel(kind: MediaKind, fileName: String): String {
    val label = mediaTypeLabel(kind)
    val ext = fileExtension(fileName)
    return if (ext.isEmpty()) label else "$label · $ext"
}

/**
 * 详细信息 Sheet「目录」行：null/空串 = 库根 → 显示「/」（协议 AssetDetail.directory
 * 口径与领域模型 KDoc「null/空串 = 库根」单源）；非空原样透传。
 */
fun detailDirectoryLabel(directory: String?): String =
    directory?.takeIf { it.isNotEmpty() } ?: "/"
