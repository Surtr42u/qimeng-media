package media.qimeng.app.core.data.di

import android.content.Context
import android.os.Build
import android.util.Log
import coil3.EventListener
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.allowHardware
import media.qimeng.app.core.data.coil.SignedMediaDiskKeyInterceptor
import media.qimeng.app.core.data.coil.SignedMediaUriKeyer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.model.DiskCacheQuota
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** logcat 标签（验收证据协议：grep 'QimengCache' 看缓存装配/清空痕迹）；文件级单一定义，CoilModule 与 RealCoilCacheManager 共用 */
private const val CACHE_LOG_TAG = "QimengCache"

/**
 * Coil ImageLoader 全局单例装配（M4-6 C5；ADR-0014：全局单例走 Hilt）。
 *
 * 放在 :core:data 而非任务书初拟的 :core:network——磁盘缓存档位仓库（DataStore）在本模块，
 * 而 :core:data -> :core:network 单向依赖已冻结，network 无法反向拿到档位；模块边界红线
 * 优先于装配位置的字面口径（Handover 报告已注明该取舍）。
 *
 * 磁盘缓存行为拍板（C5）：
 * - LRU 档位 512MB/1GB/2GB/5GB（默认 1GB），字节值来自 [DiskCachePrefsRepository]（DataStore 持久化）；
 * - **档位变更重启生效**：Coil 官方源码明言「同一目录下多个 DiskCache 实例并发会损坏缓存」
 *   （ImageLoader.Builder.diskCache 注释），运行中重建 ImageLoader 官方不支持（SingletonImageLoader.setSafe
 *   语义 = 不可覆盖已创建实例）——重启生效是官方安全路径，设置页 UI 注明；
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
        diskCachePrefs: DiskCachePrefsRepository,
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
            // lazy initializer：首次真正用到磁盘缓存时才读 DataStore（阻塞一拍，毫秒级首读；
            // 不在 App 启动关键路径上）。runBlocking 限此一处，读取失败回落默认档。
            val quotaBytes = runBlocking {
                runCatching { diskCachePrefs.quota.first().bytes }
                    .onFailure { Log.w(CACHE_LOG_TAG, "读缓存档位失败，回落默认档", it) }
                    .getOrDefault(DiskCacheQuota.DEFAULT.bytes)
            }
            Log.i(CACHE_LOG_TAG, "DiskCache 装配 maxSizeBytes=$quotaBytes")
            DiskCache.Builder()
                // coil3.disk 的 File 重载扩展（官方 JVM 桥）：内部走 okio.Path
                .directory(context.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(quotaBytes)
                .build()
        }
        // 动图逐帧解码禁硬件位图（AnimatedImageDecoder 与硬件位图组合会导致动图退化为静态首帧，
        // Coil 官方 FAQ 口径）；静态图无需 crossfade（列表滚动场景，旧版同款）
        .allowHardware(false)
        .crossfade(false)
        // 全局错误可观测性（同上 BUG-A 修因配套）：onError 只记 Throwable 类名进 logcat，
        // 供区分「解码失败」与「网络超时/断流」；不改变请求自身的 onError UI 行为。
        // EventListener（coil3）除 onError 外全部有默认实现，匿名对象只覆写 onError 即可。
        .eventListener(object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.w(CACHE_LOG_TAG, "图片加载失败 throwable=${result.throwable.javaClass.name}")
            }
        })
        .build()

    /** 磁盘缓存目录名（Coil 官方示例同款 image_cache） */
    private const val DISK_CACHE_DIR = "image_cache"

    /** 内存缓存占最大堆比例（Coil 默认 25% 惯例档，M4-2 起沿用） */
    private const val MEMORY_CACHE_PERCENT = 0.25

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

/** [CoilCacheManager] 实现：清空/容量委托单例 ImageLoader 的 DiskCache（容量读持久化档位） */
@Singleton
class RealCoilCacheManager @javax.inject.Inject constructor(
    private val imageLoader: ImageLoader,
    private val diskCachePrefs: DiskCachePrefsRepository,
) : CoilCacheManager {

    override fun clear() {
        // 清空调用点已在 IO 线程（SettingsViewModel 调度）；这里只留证据日志
        Log.i(CACHE_LOG_TAG, "DiskCache.clear() 之前 size=${imageLoader.diskCache?.size}")
        imageLoader.diskCache?.clear()
        Log.i(CACHE_LOG_TAG, "DiskCache.clear() 之后 size=${imageLoader.diskCache?.size}")
    }

    override fun sizeBytes(): Long? = imageLoader.diskCache?.size

    override fun capacityBytes(): Long = runBlocking { diskCachePrefs.quota.first().bytes }
}
