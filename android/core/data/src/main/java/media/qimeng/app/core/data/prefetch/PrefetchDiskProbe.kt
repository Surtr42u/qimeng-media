package media.qimeng.app.core.data.prefetch

import media.qimeng.app.core.data.coil.SignedMediaCacheKeys
import media.qimeng.app.core.data.coil.SplitDiskCache

/**
 * 预取磁盘探测（第四百一十一笔 反复缓存根治）：预取单条之前先按稳定键直查磁盘缓存，
 * 命中即整条跳过（连 Coil execute 都不发）——现状「每轮全库重扫」的开销大头是已缓存
 * 条目的磁盘打开 + 位图解码（Coil 对无 target 请求命中磁盘缓存后仍走完整取图管线），
 * 6371 条跑数分钟且进度条反复从头跑；探测短路后已齐全轮次秒级收口，中断重跑只补缺口。
 *
 * **键与写入侧同源**：磁盘键 = [SignedMediaCacheKeys.stableKey]（SignedMediaDiskKeyInterceptor
 * 覆写 diskCacheKey 的同一推导），预取 URL 与浏览同源（绝对直链 → SdkMappers.absolutize），
 * 探测键与 Coil 实际落盘键逐字一致；池路由由 SplitDiskCache 内部统一（探测与写入
 * 同键同路由，分池来源化后自洽）。stableKey 回 null（非签名家族 URL）交回 false，
 * 调用方走原 execute 路径（行为与改写前逐字一致）。
 *
 * **容错口径**：探测失败（IO 异常等）按「未缓存」处理走原 execute 路径——Coil 自己
 * 会再查一次磁盘缓存，探测只是短路优化，失败即退化为现状行为，不影响正确性。
 *
 * Snapshot 用完必须关闭（DiskLruCache 条目级读锁），此处即取即闭；openSnapshot 属
 * 轻量文件操作（预取 worker 已在 Default 调度器），不另挂 IO 调度器。
 */
internal class PrefetchDiskProbe(private val splitDiskCache: SplitDiskCache) {

    /** 该 URL 的缩略图是否已在磁盘缓存（稳定键推导 + 直查对应池；探测失败按未缓存） */
    fun isDiskCached(url: String): Boolean {
        val key = SignedMediaCacheKeys.stableKey(url) ?: return false
        return runCatching {
            splitDiskCache.openSnapshot(key)?.also { it.close() } != null
        }.getOrDefault(false)
    }
}
