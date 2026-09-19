package media.qimeng.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.scan.ScanChargeController

/**
 * 电源接通广播接收器（批C 任务Q C-3，PROJECT_PLAN M6 性能项）：
 * ACTION_POWER_CONNECTED → 有待扫标记且充电中 → 自动补扫。
 *
 * 为什么运行时注册且挂 :app 级：ACTION_POWER_CONNECTED 属隐式广播例外清单之外，
 * manifest 注册在 targetSdk 26+ 不生效（官方隐式广播限制）；API 33+（targetSdk 34+
 * 强制，本应用 targetSdk 37）运行时注册必须显式 RECEIVER_NOT_EXPORTED——本接收器
 * 只收系统受保护广播，不暴露给其他应用。进程被杀后 receiver 随之消失，待扫标记
 * 持久化在 DataStore、由 [ScanChargeController.resumeDeferredIfCharging] 启动兜底补扫。
 */
class ScanChargeReceiver(
    private val onPowerConnected: suspend () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        // goAsync 给补扫网络请求让出 10s 窗口；超时/异常由协程内 catch 兜住
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                onPowerConnected()
            } finally {
                pending.finish()
            }
        }
    }
}

/** 接线单例（Application onCreate 调一次 [onAppCreate]）：注册 receiver + 启动兜底补扫 */
@Singleton
class ScanChargeRegistrar @Inject constructor(
    private val controller: ScanChargeController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onAppCreate(context: Context) {
        val receiver = ScanChargeReceiver { controller.onPowerConnected() }
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            // 原生 Context.RECEIVER_NOT_EXPORTED（非 ContextCompat 派生）：lint WrongConstant
            // 校验只认 Context 的常量面（批C 门禁实证）；仅系统受保护广播可抵达
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        // 启动兜底：进程回收导致上一次注册丢失、或充电中入队待扫后直接重启的场景
        scope.launch { controller.resumeDeferredIfCharging() }
    }
}
