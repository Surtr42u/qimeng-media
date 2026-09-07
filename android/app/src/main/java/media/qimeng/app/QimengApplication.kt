package media.qimeng.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import media.qimeng.app.core.data.events.EventSyncBootstrapper

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
 *
 * 行为上报离线队列（M4-4）：onCreate 一次接好补传三通道中的②③——周期兜底注册 +
 * ProcessLifecycleOwner ON_START（启动进前台/回前台即补传），接线细节在 EventSyncBootstrapper。
 */
@HiltAndroidApp
class QimengApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var eventSyncBootstrapper: EventSyncBootstrapper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        eventSyncBootstrapper.onAppCreate()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
