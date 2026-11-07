package media.qimeng.app.core.data.device

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 设备结果包后台 Worker（task-17，ADR-0055）。
 *
 * 只做输入解析与 WorkManager 终局映射；业务流程全部在 [DeviceApplyRunner]。
 * 不升级前台服务；周期链返回 failure 会永久停摆，因此可重试失败用 retry（有上限），
 * 其余一律 success 并携带本机摘要，等下一周期/用户手动触发。
 */
@HiltWorker
class DeviceApplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: DeviceApplyRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(DeviceApplyWorkSpec.KEY_MANUAL, false)
        Log.i(
            DeviceApplyWorkSpec.LOG_TAG,
            "worker run manual=$manual attempt=$runAttemptCount tags=$tags",
        )
        return try {
            when (val outcome = runner.run(manual)) {
                is DeviceApplyRunResult.Retry -> {
                    if (DeviceApplyWorkSpec.shouldRetry(runAttemptCount)) {
                        Log.i(DeviceApplyWorkSpec.LOG_TAG, "retry: ${outcome.message}")
                        Result.retry()
                    } else {
                        Result.success(workDataOf(DeviceApplyWorkSpec.KEY_MESSAGE to outcome.message))
                    }
                }

                is DeviceApplyRunResult.NoOp,
                is DeviceApplyRunResult.Completed -> Result.success(
                    workDataOf(DeviceApplyWorkSpec.KEY_MESSAGE to outcome.message),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.e(DeviceApplyWorkSpec.LOG_TAG, "worker unexpected failure", failure)
            if (DeviceApplyWorkSpec.shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                Result.success(
                    workDataOf(
                        DeviceApplyWorkSpec.KEY_MESSAGE to
                            "设备结果包执行失败（${failure.javaClass.simpleName}），请查看应用日志",
                    ),
                )
            }
        }
    }
}
