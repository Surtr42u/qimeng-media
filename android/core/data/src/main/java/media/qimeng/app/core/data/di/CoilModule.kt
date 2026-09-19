package media.qimeng.app.core.data.di

import android.content.Context
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.request.allowHardware
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.data.coil.LEGACY_DISK_CACHE_DIR
import media.qimeng.app.core.data.coil.LOCAL_DISK_CACHE_DIR
import media.qimeng.app.core.data.coil.LOG_TAG
import media.qimeng.app.core.data.coil.NAS_DISK_CACHE_DIR
import media.qimeng.app.core.data.coil.SplitDiskCache
import media.qimeng.app.core.data.coil.SignedMediaDiskKeyInterceptor
import media.qimeng.app.core.data.coil.SignedMediaUriKeyer
import media.qimeng.app.core.data.coil.migrateLegacyImageCacheDir
import media.qimeng.app.core.data.diagnostics.ClientLogRecorder
import media.qimeng.app.core.data.diagnostics.CoilErrorLogListener
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.model.DiskCacheQuota
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Coil ImageLoader 全局单例装配（M4-6 C5；ADR-0014：全局单例走 Hilt）。
 *
 * 放在 :core:data 而非任务书初拟的 :core:network——磁盘缓存档位仓库（DataStore）在本模块，
 * 而 :core:data -> :core:network 单向依赖已冻结，network 无法反向拿到档位；模块边界红线
 * 优先于装配位置的字面口径（Handover 报告已注明该取舍）。
 *
 * 磁盘缓存行为拍板（C5）：
 * - LRU 档位 512MB/1GB/2GB/5GB（默认 1GB），字节值来自 [DiskCachePrefsRepository]（DataStore 持久化）；
 * - **档位变更重启生效**：运行中重建 ImageLoader 官方不支持（SingletonImageLoader.setSafe
 *   语义 = 不可覆盖已创建实例）——重启生效是官方安全路径，设置页 UI 注明；
 * - **批S5（2026-09-19 用户拍板）按连接来源分池**：缩略图缓存 = 手机 App 侧缓存，分
 *   「服务器（NAS）池/本地端池」两个物理目录（[SplitDiskCache] 按键路由），互不共享
 *   （两端库内容不同，共享会串图）；NAS 池容量 = 档位字节（保持现值不回退），本地池
 *   = 档位一半（本地端=手机内嵌服务端，库内容远小于 NAS 全库，半档足用，[LOCAL_POOL_QUOTA_DIVISOR]）；
 * - GIF 原件直链**进**磁盘缓存（C5 拍板：网络缓存不损动画语义——落盘的是原始 GIF 字节，
 *   解码仍由 GIF 解码器逐帧动画）。**任务U10-5（2026-09-14 用户拍板）调整**：详情页
 *   原件请求改为 request 级 `diskCachePolicy(DISABLED)`（GIF 原件含在内，即看即取不落盘，
 *   防磁盘缓存膨胀与写盘损耗）——本加载器的磁盘缓存自此主要承载缩略图/海报帧等小对象；
 * - **视频不落盘**：播放走 Media3 直链流式（不经 Coil），上传走 WorkManager/okhttp（不经 Coil），
 *   全 App 无任何「用 Coil 加载视频 URL」的路径——不落盘由架构保证而非开关。
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        splitDiskCache: Lazy<SplitDiskCache>,
        clientLogRecorder: ClientLogRecorder,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            // 任务U10-5 缓存键漂移根治（2026-09-15 批次2）：媒体直链 = HMAC 签名 URL，
            // 服务端对每次响应重算 exp/sig（DefaultTokenTTL=6h），同一缩略图的 URL 字符串
            // 随响应变化 → Coil 默认以完整 URL 为缓存键 → 键 miss → 同图反复下载
            // （「浏览越多越吃网速」根因）。用户拍板口径：纯客户端剥签名 query 做键，
            // 不动协议、不改服务端；服务端仍逐请求验签，安全语义不变。两个组件缺一不可：
            // - Keyer 管内存缓存键（注意键的是映射后的 coil3.Uri 而非 String——Coil 3
            //   键器链收的是 StringMapper 映射后数据，3.6.2 产物字节码核实）；
            // - Interceptor 管磁盘缓存键（coil3 NetworkFetcher 按
            //   options.diskCacheKey ?: 原始URL 读写磁盘、不走键器链，3.6.2 源码核实），
            //   缺它则杀进程重进后二次浏览仍 miss 磁盘缓存（验收口径明确要求该场景零网络）。
            // 两者只改键、不改请求/响应数据，非签名家族 URL 原样回退默认行为。
            add(SignedMediaDiskKeyInterceptor())
            add(SignedMediaUriKeyer())
            // 网络层显式装配 OkHttp 取图器（2026-09-13 用户真机 BUG-A「图片详情偶现无法解码」主修）：
            // 不配时 coil-network-okhttp 经 service-loader 自动注册默认 OkHttpClient
            // （无任何超时配置 → OkHttp 默认 readTimeout=10s），NAS 缩略图懒生成风暴拖慢
            // original 流 >10s → SocketTimeoutException → onError → UI 误报「无法解码」。
            // 口径：connect 15s / read 60s / call 0（不设总时限）——「查看永远发原件」口径下
            // 大文件长传输是常态，读超时必须给足；ComponentRegistry 首个可处理该数据的
            // 工厂胜出，且用户组件排在 service-loader 组件之前（RealImageLoader 装配顺序），
            // 显式装配必然覆盖默认注册（已对 3.4.0 源码核实，非凭记忆）。
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        OkHttpClient.Builder()
                            .connectTimeout(NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            // callTimeout=0 即 OkHttp 官方语义「不设总时限」：原件长传输不设上限
                            .callTimeout(NETWORK_CALL_TIMEOUT_DISABLED, TimeUnit.SECONDS)
                            .build()
                    },
                ),
            )
            // GIF 解码分档（Coil 官方建议）：API 28+ 用 AnimatedImageDecoder（硬件加速逐帧），
            // 26/27 回退 GifDecoder（软件解码；minSdk 26 全覆盖，lint NewApi 亦要求显式分档）
            if (Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                .build()
        }
        .diskCache {
            // 批S5 分池磁盘缓存：dagger.Lazy 保持「首次真正用到磁盘缓存才构建」的既有
            // 惰性时机（DataStore 档位读 + 存量迁移都在 [provideSplitDiskCache] 里，
            // ImageLoader 构建本身不触发）。 dagger.Lazy.get() 幂等返回同一单例，
            // 与 RealCoilCacheManager 注入的是同一实例。
            splitDiskCache.get()
        }
        // 动图逐帧解码禁硬件位图（AnimatedImageDecoder 与硬件位图组合会导致动图退化为静态首帧，
        // Coil 官方 FAQ 口径）；静态图无需 crossfade（列表滚动场景，旧版同款）
        .allowHardware(false)
        .crossfade(false)
        // 全局错误可观测性（同上 BUG-A 修因配套）：onError 记 Throwable 进 logcat 并
        // 送客户端异常上报通道（2026-09-18 接线——本机设备 logcat 不可用，服务端
        // 维护页异常表是唯一现场来源）；不改变请求自身的 onError UI 行为。
        .eventListener(CoilErrorLogListener(clientLogRecorder))
        .build()

    /**
     * 分池磁盘缓存单例（批S5）：存量迁移 + 双池装配。注入 [Lazy] 给 ImageLoader，
     * 保持「首次用到磁盘缓存才读 DataStore/开目录」的启动关键路径零负担。
     */
    @Provides
    @Singleton
    fun provideSplitDiskCache(
        @ApplicationContext context: Context,
        diskCachePrefs: DiskCachePrefsRepository,
    ): SplitDiskCache {
        // 档位读（runBlocking 自原 diskCache lambda 迁移至此；lazy 构建时才执行，毫秒级首读，
        // 不在 App 启动关键路径上）。读取失败回落默认档。
        val quotaBytes = runBlocking {
            runCatching { diskCachePrefs.quota.first().bytes }
                .onFailure { Log.w(LOG_TAG, "读缓存档位失败，回落默认档", it) }
                .getOrDefault(DiskCacheQuota.DEFAULT.bytes)
        }
        // 存量迁移（幂等）：旧 image_cache 混合目录整体重命名为 NAS 池目录（历史缓存
        // 几乎全来自 NAS 连接，整体归 NAS 口径；本地池从空开始惰性创建）。
        // 必须在两个池打开目录之前执行——两实例共管同一目录才是官方损坏场景，迁移完成后
        // 各池目录只归各自实例独管。
        val migrated = migrateLegacyImageCacheDir(context.cacheDir)
        if (migrated) {
            Log.i(LOG_TAG, "存量缓存目录迁移完成：$LEGACY_DISK_CACHE_DIR → $NAS_DISK_CACHE_DIR")
        }
        Log.i(
            LOG_TAG,
            "SplitDiskCache 装配 quotaBytes=$quotaBytes " +
                "(nas=${NAS_DISK_CACHE_DIR} maxSize=$quotaBytes, " +
                "local=${LOCAL_DISK_CACHE_DIR} maxSize=${quotaBytes / LOCAL_POOL_QUOTA_DIVISOR})",
        )
        return SplitDiskCache(
            nasPool = DiskCache.Builder()
                // coil3.disk 的 File 重载扩展（官方 JVM 桥）：内部走 okio.Path
                .directory(context.cacheDir.resolve(NAS_DISK_CACHE_DIR))
                .maxSizeBytes(quotaBytes)
                .build(),
            localPool = DiskCache.Builder()
                .directory(context.cacheDir.resolve(LOCAL_DISK_CACHE_DIR))
                .maxSizeBytes(quotaBytes / LOCAL_POOL_QUOTA_DIVISOR)
                .build(),
        )
    }

    /** 内存缓存占最大堆比例（Coil 默认 25% 惯例档，M4-2 起沿用） */
    private const val MEMORY_CACHE_PERCENT = 0.25

    /**
     * 本地池容量 = 档位字节的除数（批S5 口径：NAS 池保持现值不回退，本地池给现值一半——
     * 本地端为手机内嵌服务端，库内容远小于 NAS 全库；且两池上限独立、各池 LRU 自治）。
     */
    private const val LOCAL_POOL_QUOTA_DIVISOR = 2L

    /** 网络层连接超时（秒）：局域网/NAS 握手 10s（:core:network 同款）足够，略放余量。 */
    private const val NETWORK_CONNECT_TIMEOUT_SECONDS = 15L

    /**
     * 网络层读超时（秒）：两次数据到达之间的窗口而非整个传输时长。NAS 缩略图懒生成
     * 风暴下 original 流慢（>10s 曾被默认 readTimeout 掐断，BUG-A 误报根因），给足 60s；
     * callTimeout 已禁用，长传输不受此值截断。
     */
    private const val NETWORK_READ_TIMEOUT_SECONDS = 60L

    /** 网络层总时限（秒）：0 = OkHttp 语义「不设总时限」（查看永远发原件，长传输是常态）。 */
    private const val NETWORK_CALL_TIMEOUT_DISABLED = 0L
}

/** [media.qimeng.app.core.data.repository.CoilCacheManager] 实现：分池统计/清空委托 [SplitDiskCache] 单例 */
@Singleton
class RealCoilCacheManager @javax.inject.Inject constructor(
    private val splitDiskCache: SplitDiskCache,
) : media.qimeng.app.core.data.repository.CoilCacheManager {

    override fun clearPool(pool: CachePool) {
        val target = splitDiskCache.pool(pool)
        // 清空调用点已在 IO 线程（ViewModel 调度）；这里只留证据日志
        Log.i(LOG_TAG, "DiskCache.clearPool($pool) 之前 size=${target.size}")
        target.clear()
        Log.i(LOG_TAG, "DiskCache.clearPool($pool) 之后 size=${target.size}")
    }

    override fun poolSizeBytes(pool: CachePool): Long = splitDiskCache.pool(pool).size

    override fun poolFileCount(pool: CachePool): Int {
        val target = splitDiskCache.pool(pool)
        // 纯谓词 [isDiskCacheDataFileName] 已有单测锁定；磁盘遍历属 IO 性质，
        // 调用点（ViewModel 读用量链路）统一挂 IO 调度器
        return target.fileSystem.listRecursively(target.directory)
            .count { isDiskCacheDataFileName(it.name) }
    }
}

/**
 * 磁盘缓存目录内「数据文件」文件名谓词（批S4 2026-09-19 缓存条目数口径的单一来源；
 * 批S5 起按池分目录计数，谓词本身不变）。Coil 3.6.2 磁盘缓存布局（DiskLruCache.kt/
 * RealDiskCache.kt 官方源码核实）：每条目 = `{key}.0`（元数据）+ `{key}.1`（数据），
 * 写盘中转 = `{key}.N.tmp`，日志文件 = journal / journal.tmp / journal.bkp——只有 `.1`
 * 结尾是数据文件，一条 = 一张缓存图。接口 KDoc 与本谓词互为口径注记，改动须同批同步测试。
 */
internal fun isDiskCacheDataFileName(name: String): Boolean = name.endsWith(DATA_FILE_SUFFIX)

/** 数据文件后缀（ENTRY_DATA=1，见 [isDiskCacheDataFileName] 口径注记） */
private const val DATA_FILE_SUFFIX = ".1"
