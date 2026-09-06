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
import coil3.request.crossfade
import coil3.request.allowHardware
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
 *   解码仍由 GIF 解码器逐帧动画）；
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
        .build()

    /** 磁盘缓存目录名（Coil 官方示例同款 image_cache） */
    private const val DISK_CACHE_DIR = "image_cache"

    /** 内存缓存占最大堆比例（Coil 默认 25% 惯例档，M4-2 起沿用） */
    private const val MEMORY_CACHE_PERCENT = 0.25
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
