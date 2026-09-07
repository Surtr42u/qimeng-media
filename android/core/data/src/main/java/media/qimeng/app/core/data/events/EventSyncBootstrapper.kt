package media.qimeng.app.core.data.events

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行为补传三通道的启动接线（M4-4，App onCreate 调一次 [onAppCreate]）：
 * - 通道③ 周期兜底：KEEP 幂等注册（[EventSyncScheduler.ensurePeriodicSync]）；
 * - 通道② 应用启动与回前台：ProcessLifecycleOwner ON_START 监听——进程冷启动进前台与
 *   每次退后台后回前台都会回调 onStart，正好是「回到可用网络环境」的高概率时刻
 *   （弱网下离线攒的事件在此刻补传）。
 * 通道① 写入即触发不在此处（写路径在 SdkDetailRepository.reportViewEvent）。
 */
@Singleton
class EventSyncBootstrapper @Inject constructor(
    private val scheduler: EventSyncScheduler,
) : DefaultLifecycleObserver {

    fun onAppCreate() {
        scheduler.ensurePeriodicSync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** 回前台（含首启进前台）：请求一次即时补传（KEEP 与并发触发合并） */
    override fun onStart(owner: LifecycleOwner) {
        scheduler.requestSyncNow()
    }
}
