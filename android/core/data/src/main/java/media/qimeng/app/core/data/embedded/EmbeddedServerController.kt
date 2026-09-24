package media.qimeng.app.core.data.embedded

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.network.ServerAddress

/**
 * 内嵌服务端拉起/停止单点（任务U11 批次D）。为什么包一层而不是让 UI 直调
 * Service 静态方法：本机模式判定（[ServerAddress.isLocalModePreset]）与
 * FGS 启动语义收在一处，设置页切换与壳层冷启动自检两条消费链不会各写一份
 * 渐行渐远。接口化（同 AuthRepository 模式）供 JVM 单测桩替换。
 *
 * 启动语义=ensure（已运行的 Service 收到重复 START intent 无副作用，见
 * [EmbeddedServerService.ensureServerRunning]）；FGS 启动只允许前台态调用
 * ——消费链都在 Activity 前台生命周期内，满足 Android 12+ 后台 FGS 限制。
 */
interface EmbeddedServerController {

    /** 若 URL 为内嵌预设地址则拉起服务端（幂等）。@return 是否触发了拉起 */
    fun ensureStartedIfLocalMode(serverUrl: String): Boolean

    /**
     * 前台回归健康检查（2026-09-25 冻结事故自愈）：URL 为内嵌预设时向服务发
     * HEALTH_CHECK intent——服务端探测 /healthz，无响应即杀掉重拉。@return 是否触发了检查
     */
    fun ensureHealthyIfLocalMode(serverUrl: String): Boolean

    /** 停止内嵌服务端（切回 NAS 地址回收本机进程；通知随 Service 销毁消失） */
    fun stop()
}

@Singleton
class EmbeddedServerControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmbeddedServerController {

    override fun ensureStartedIfLocalMode(serverUrl: String): Boolean {
        if (!ServerAddress.isLocalModePreset(serverUrl)) return false
        EmbeddedServerService.start(context)
        return true
    }

    override fun ensureHealthyIfLocalMode(serverUrl: String): Boolean {
        if (!ServerAddress.isLocalModePreset(serverUrl)) return false
        EmbeddedServerService.requestHealthCheck(context)
        return true
    }

    override fun stop() {
        EmbeddedServerService.stop(context)
    }
}
