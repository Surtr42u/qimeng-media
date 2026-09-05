package media.qimeng.app

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt 应用入口：全 App 依赖注入的根（ADR-0014：新依赖一律走 Hilt，禁手写单例容器）。
 *
 * Coil 3 单例 ImageLoader 装配（官方 SingletonImageLoader.Factory 姿势）：
 * - GIF 解码器（coil-gif，2026-09-06 拍板：动图缩略图必须动画——animated_image 网格项
 *   走原件签名直链由 GIF 解码器逐帧渲染；image 类型照旧用服务端缩略图）；
 * - 网络取图器 coil-network-okhttp 经 ServiceLoader 自动注册（classpath 即生效）；
 * - 内存缓存 25% 堆（Coil 惯例档；动图原件体积大，内存缓存优先、磁盘缓存留 M4-6 C5 拍板档）。
 *
 * WorkManager 自定义初始化（M4-5 上传队列）：Application 实现 Configuration.Provider
 * 注入 HiltWorkerFactory（@HiltWorker 官方姿势）；默认初始化器已在 manifest 用 merge rule 移除。
 */
@HiltAndroidApp
class QimengApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
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
            .crossfade(false)
            .build()

    private companion object {
        /** 内存缓存占最大堆比例（Coil 默认 25% 惯例档） */
        const val MEMORY_CACHE_PERCENT = 0.25
    }
}
