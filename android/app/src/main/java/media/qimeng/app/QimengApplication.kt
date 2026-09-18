package media.qimeng.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import media.qimeng.app.core.data.diagnostics.DiagnosticsBootstrapper
import media.qimeng.app.core.data.events.EventSyncBootstrapper
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetcher

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
 *
 * 客户端异常上报（2026-09-18）：onCreate 挂 DiagnosticsBootstrapper——全局未捕获异常
 * 上报 + 回前台补发（POST /api/v1/client-logs，Web 端 lib/client-logs.ts 同款策略），
 * Coil 图片加载错误在 CoilModule 经 CoilErrorLogListener 汇入同一通道。
 *
 * 缩略图预取（2026-09-18）：onCreate 构造 ThumbnailPrefetcher 挂上登录态观察——
 * 登录后自动把全库缩略图预取进 Coil 磁盘缓存（逻辑全在 :core:data prefetch 包）。
 */
@HiltAndroidApp
class QimengApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var eventSyncBootstrapper: EventSyncBootstrapper

    /** 客户端异常上报接线（2026-09-18）：构造即挂全局崩溃处理器 + 回前台补传 */
    @Inject
    lateinit var diagnosticsBootstrapper: DiagnosticsBootstrapper

    /** 缩略图预取器（2026-09-18）：此处唯一作用是进程启动即构造，让登录态观察挂上电 */
    @Inject
    lateinit var thumbnailPrefetcher: ThumbnailPrefetcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        eventSyncBootstrapper.onAppCreate()
        diagnosticsBootstrapper.onAppCreate()
        thumbnailPrefetcher.onAppCreate()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
