package media.qimeng.app.core.model

/**
 * Coil 磁盘缓存 LRU 档位（C5 拍板：512MB/1GB/2GB/5GB，默认 1GB）。
 * bytes 是 Coil `DiskCache.Builder().maxSizeBytes()` 的入参；label 是设置页行文案。
 */
enum class DiskCacheQuota(
    val label: String,
    val bytes: Long,
) {
    MB512("512MB", 512L * 1024L * 1024L),
    GB1("1GB", 1024L * 1024L * 1024L),
    GB2("2GB", 2L * 1024L * 1024L * 1024L),
    GB5("5GB", 5L * 1024L * 1024L * 1024L),
    ;

    companion object {
        /** 默认档 1GB（C5 拍板；未持久化过或持久化值非法时回落） */
        val DEFAULT: DiskCacheQuota = GB1

        /** 持久化只存 MB 整数（DataStore int 键），读回时映射；未知值回落默认档 */
        fun fromMb(megabytes: Int): DiskCacheQuota =
            entries.firstOrNull { it.bytes / BYTES_PER_MB == megabytes.toLong() } ?: DEFAULT

        /** 字节 → MB（持久化写入用） */
        val BYTES_PER_MB: Long = 1024L * 1024L
    }
}
