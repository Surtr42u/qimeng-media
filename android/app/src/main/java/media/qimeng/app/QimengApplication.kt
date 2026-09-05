package media.qimeng.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt 应用入口：全 App 依赖注入的根（ADR-0014：新依赖一律走 Hilt，禁手写单例容器）。
 *
 * Coil 3 单例 ImageLoader（M4-6 起）：装配全部收敛到 :core:data 的 CoilModule
 * （磁盘缓存 LRU 档位 + GIF 解码器 + 内存缓存），此处只实现官方的
 * SingletonImageLoader.Factory 回调把 Hilt 单例接给 Coil——保证全 App 唯一实例，
 * 设置页的档位/清空操作与本处是同一个 ImageLoader。
 *
 * WorkManager 自定义初始化（M4-5 上传队列）：Application 实现 Configuration.Provider
 * 注入 HiltWorkerFactory（@HiltWorker 官方姿势）；默认初始化器已在 manifest 用 merge rule 移除。
 */
@HiltAndroidApp
class QimengApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
