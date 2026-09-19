package media.qimeng.app.core.data.coil

import android.util.Log
import java.io.File
import coil3.disk.DiskCache
import media.qimeng.app.core.network.ServerAddress

/**
 * 缩略图磁盘缓存池（批S5 2026-09-19 用户拍板冻结）：缓存 = 手机 App 侧缓存，按连接
 * 来源分两池——[NAS]（连 NAS 服务器时缓存的图）与 [LOCAL]（连本地端内嵌服务端时
 * 缓存的图）。两池内容按来源隔离、**互不共享**（两端库内容不同，共享会串图）。
 */
enum class CachePool {
    /** 服务器池：连接 NAS（远程服务器）时缓存的图 */
    NAS,

    /** 本地池：连接本地端（内嵌服务端，端口口径见 [ServerAddress.LOCAL_MODE_PORT]）时缓存的图 */
    LOCAL,
}

/** 旧混合缓存目录名（批S5 前的唯一磁盘缓存目录，Coil 官方示例同款；仅存量迁移用） */
internal const val LEGACY_DISK_CACHE_DIR = "image_cache"

/** 服务器（NAS）池目录名 */
internal const val NAS_DISK_CACHE_DIR = "image_cache_nas"

/** 本地端池目录名 */
internal const val LOCAL_DISK_CACHE_DIR = "image_cache_local"

/**
 * 缓存键是否为 http(s) URL 形态（磁盘键族判定；协议前缀单源 = core:network ServerAddress，
 * SignedMediaCacheKeys 同款纪律：前缀第 2 处手抄即漂移风险）。
 */
internal fun isHttpCacheKey(key: String): Boolean =
    key.startsWith(ServerAddress.SCHEME_HTTP) || key.startsWith(ServerAddress.SCHEME_HTTPS)

/**
 * 缓存键 → 池路由（纯函数，行为由单元测试锁定）：
 * - http(s) 键交 [isLocalUrl] 判定（实参恒为 [ServerAddress.isLocalModePreset]：
 *   回环 host + 端口 18430 → LOCAL，否则 NAS）；
 * - **非 http 键兜底 NAS 池**：Coil 磁盘键正常只有签名直链/裸 URL 两族（均含
 *   host:port 可判），file/data 等键族外样本不该出现在缩略图缓存，兜底归主池不丢数据。
 *
 * 为什么必须「写入时分」而不能事后分流：磁盘键落盘后只剩 SHA-256 哈希文件名 +
 * `.0` 元数据（NetworkFetcher 只写响应头，无 URL），事后无法从目录内容还原键的来源。
 */
internal fun resolveCachePool(key: String, isLocalUrl: (String) -> Boolean): CachePool = when {
    !isHttpCacheKey(key) -> CachePool.NAS
    isLocalUrl(key) -> CachePool.LOCAL
    else -> CachePool.NAS
}

/**
 * 存量缓存迁移（批S5，幂等）：批S5 前所有缩略图缓存在旧 [LEGACY_DISK_CACHE_DIR] 混合
 * 目录。整体重命名为 NAS 池目录（历史缓存 99% 来自 NAS 连接，整体归 NAS 口径；
 * 本地池从空开始惰性创建）。仅在「旧目录存在且 NAS 池目录不存在」时执行——两目录
 * 并存（理论上不可达）时保持现状不合并，防 renameTo 对非空目标目录失败。
 *
 * @return 是否实际发生了迁移（调用方记档日志）
 */
internal fun migrateLegacyImageCacheDir(cacheRoot: File): Boolean {
    val legacy = File(cacheRoot, LEGACY_DISK_CACHE_DIR)
    val nasDir = File(cacheRoot, NAS_DISK_CACHE_DIR)
    if (!legacy.isDirectory || nasDir.exists()) return false
    return legacy.renameTo(nasDir)
}

/**
 * 按键路由的双池磁盘缓存（批S5）：实现 Coil [DiskCache] 接口，内部持两个独立
 * [RealDiskCache]（NAS 池/本地池，两个**物理目录**）。
 *
 * **为什么用包装器而不是运行时换 ImageLoader**：运行中重建 ImageLoader 官方不支持
 * （SingletonImageLoader.setSafe 已建则 no-op），单 ImageLoader + 路由 DiskCache 是唯一正路。
 * **为什么双目录安全而同目录双实例不安全**：Coil 官方明言「同一目录下多个 DiskCache
 * 实例并发会损坏缓存」——损坏前提是两实例共管同一目录的 journal；双物理目录各自有
 * 独立 journal，互不知晓也无竞争。
 *
 * 路由时机：openSnapshot/openEditor/remove 收到的是**原始键**（接口层字符串，Coil
 * 内部落盘才哈希），读写同键同路由——写入时分即达成两池分离（[resolveCachePool]）。
 *
 * 成员口径：size = 两池之和；maxSize = 两池聚合（只读口径，无消费方，Coil 各子池
 * 驱逐只认各自 maxSize）；clear/shutdown 委托两池；directory 返回 NAS 池目录（接口
 * 必须给值；聚合统计一律走 [pool] 专用口，禁止用 directory 扫描代替——那会漏掉本地池）。
 */
class SplitDiskCache(
    private val nasPool: DiskCache,
    private val localPool: DiskCache,
) : DiskCache {

    /** 取指定池（分池统计/清空与 ImageLoader 装配两侧共用同一实例，口径单源） */
    fun pool(pool: CachePool): DiskCache = when (pool) {
        CachePool.NAS -> nasPool
        CachePool.LOCAL -> localPool
    }

    override val size: Long get() = nasPool.size + localPool.size

    override val maxSize: Long get() = nasPool.maxSize + localPool.maxSize

    override val directory: okio.Path get() = nasPool.directory

    override val fileSystem: okio.FileSystem get() = nasPool.fileSystem

    override fun openSnapshot(key: String): DiskCache.Snapshot? = poolFor(key).openSnapshot(key)

    override fun openEditor(key: String): DiskCache.Editor? = poolFor(key).openEditor(key)

    override fun remove(key: String): Boolean = poolFor(key).remove(key)

    override fun clear() {
        nasPool.clear()
        localPool.clear()
    }

    override fun shutdown() {
        nasPool.shutdown()
        localPool.shutdown()
    }

    /** 键路由到池；非 http 键属键族外样本，兜底 NAS 池并记档（debug 级，不刷屏） */
    private fun poolFor(key: String): DiskCache {
        val pool = resolveCachePool(key, ServerAddress::isLocalModePreset)
        if (!isHttpCacheKey(key)) {
            Log.d(LOG_TAG, "SplitDiskCache 非 http 缓存键兜底 NAS 池 key=$key")
        }
        return pool(pool)
    }
}

/** logcat 标签单源（验收证据协议：grep 'QimengCache' 看缓存装配/清空痕迹）；CoilModule/分池包装器共用 */
internal const val LOG_TAG = "QimengCache"
