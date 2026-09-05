package media.qimeng.app.core.ui.component

import java.util.Locale

/** 字节换算基数（1024 进制；KB 档留作下界保护，展示主用 MB/GB 两级） */
private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

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
