package media.qimeng.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt 应用入口：全 App 依赖注入的根（ADR-0014：新依赖一律走 Hilt，禁手写单例容器）。 */
@HiltAndroidApp
class QimengApplication : Application()
