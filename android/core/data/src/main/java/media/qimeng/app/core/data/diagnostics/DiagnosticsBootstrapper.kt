package media.qimeng.app.core.data.diagnostics

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端异常上报的启动接线（App onCreate 调一次 [onAppCreate]），对齐
 * EventSyncBootstrapper 的三通道思路：
 * - 全局未捕获异常：包装既有 defaultUncaughtExceptionHandler——先记录 + 限时同步
 *   补发（进程将死，异步来不及），再交还原处理器走系统崩溃流程；包装全程吞异常，
 *   绝不干扰原崩溃链路；
 * - 回前台补传：ProcessLifecycleOwner ON_START——弱网期攒下的错误在回到可用网络
 *   环境的高概率时刻补发（行为上报 M4-4 同款时机）。
 */
@Singleton
class DiagnosticsBootstrapper @Inject constructor(
    private val recorder: ClientLogRecorder,
) : DefaultLifecycleObserver {

    fun onAppCreate() {
        installCrashHandler()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** 回前台（含首启进前台）：异步补发一次（失败静默） */
    override fun onStart(owner: LifecycleOwner) {
        recorder.flushAsync()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recorder.record(
                    level = ClientLogLevel.ERROR,
                    message = "Uncaught exception on thread=${thread.name}: " +
                        "${throwable::class.java.name}: ${throwable.message}",
                    stack = Log.getStackTraceString(throwable),
                    page = PAGE_CRASH,
                )
                recorder.flushBlockingOnCrash()
            } catch (ignored: Throwable) {
                // 上报器自身异常绝不外抛（必须把崩溃原样交还给系统流程）
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** 崩溃条目的 page 定位标记（无路由上下文，粗粒度即可） */
        const val PAGE_CRASH = "crash"
    }
}
