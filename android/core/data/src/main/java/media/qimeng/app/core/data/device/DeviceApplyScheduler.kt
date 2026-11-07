package media.qimeng.app.core.data.device

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource

/**
 * 设备结果包调度器（task-17）。
 *
 * - 周期链：唯一名 [DeviceApplyWorkSpec.UNIQUE_PERIODIC_NAME]，15 分钟；
 * - 立即链：唯一名 [DeviceApplyWorkSpec.UNIQUE_NOW_NAME]，一次性、KEEP 合并连点；
 * - 约束：远端 CONNECTED；本机回环模式放宽为 NOT_REQUIRED（见 WorkSpec）；
 * - 串行：两条链各自 unique 保证同链串行；跨链并发由 [DeviceApplyRunner] 的进程内
 *   Mutex 再串行（进程被杀后 WorkManager 不会同时启动同一进程的两个实例的场景除外）；
 * - 不升级前台服务：Worker 本身不调 setForeground；本机模式拉起 EmbeddedServerService
 *   仍由既有 Service 负责（后台 FGS 限制见 docs/api/device.md）。
 *
 * 调度失败只记日志：WorkManager 未初始化/系统限制不应崩溃 App；下一轮设置流/冷启动会重试。
 */
@Singleton
class DeviceApplyScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverConfig: ServerConfigDataSource,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** 设置/登录地址变化后的幂等同步：开启则注册周期，关闭则取消两条链。 */
    fun sync(enabled: Boolean, localMode: Boolean) {
        if (!enabled) {
            cancelAll()
            return
        }
        enqueuePeriodic(localMode)
    }

    /** 手动「立即应用」：由设置页触发；后台开关关闭时仍允许。 */
    fun requestNow() {
        val localMode = ServerAddress.isLocalModePreset(serverConfig.currentServerUrl().orEmpty())
        runCatching {
            workManager.enqueueUniqueWork(
                DeviceApplyWorkSpec.UNIQUE_NOW_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DeviceApplyWorker>()
                    .setInputData(workDataOf(DeviceApplyWorkSpec.KEY_MANUAL to true))
                    .setConstraints(constraints(localMode))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(DeviceApplyWorkSpec.TAG_DEVICE_APPLY)
                    .build(),
            )
        }.onFailure { Log.w(DeviceApplyWorkSpec.LOG_TAG, "立即应用入队失败", it) }
    }

    fun cancelAll() {
        runCatching {
            workManager.cancelUniqueWork(DeviceApplyWorkSpec.UNIQUE_PERIODIC_NAME)
            workManager.cancelUniqueWork(DeviceApplyWorkSpec.UNIQUE_NOW_NAME)
        }.onFailure { Log.w(DeviceApplyWorkSpec.LOG_TAG, "取消设备结果包任务失败", it) }
    }

    private fun enqueuePeriodic(localMode: Boolean) {
        runCatching {
            workManager.enqueueUniquePeriodicWork(
                DeviceApplyWorkSpec.UNIQUE_PERIODIC_NAME,
                // UPDATE 让地址模式变化时更新约束；WorkManager 会尽量保留原周期相位。
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DeviceApplyWorker>(
                    DeviceApplyWorkSpec.PERIODIC_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                    .setConstraints(constraints(localMode))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(DeviceApplyWorkSpec.TAG_DEVICE_APPLY)
                    .build(),
            )
        }.onFailure { Log.w(DeviceApplyWorkSpec.LOG_TAG, "注册设备结果包周期任务失败", it) }
    }

    private fun constraints(localMode: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (DeviceApplyWorkSpec.requiresConnectedNetwork(localMode)) {
                NetworkType.CONNECTED
            } else {
                NetworkType.NOT_REQUIRED
            },
        )
        .build()

    private companion object {
        /** WorkManager 系统下限 10 秒（BackoffPolicy.MIN_BACKOFF_MILLIS 的秒口径）。 */
        const val MIN_BACKOFF_SECONDS = 10L
    }
}
