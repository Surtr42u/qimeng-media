package media.qimeng.app.core.data.events

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 行为补传 Worker（M4-4 三通道的统一执行端：即时链与周期链都跑它）。
 * 执行体只有 drain 一句：失败处置/毒丸/串行全部收敛在 [ViewEventQueue]（单测锁定），
 * worker 层不叠加 WorkManager retry（见 [EventSyncWorkSpec.drainSummaryToResult]）。
 * 前例：UploadWorker.kt:26（@HiltWorker + CoroutineWorker）。
 */
@HiltWorker
class EventSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val queue: ViewEventQueue,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 触发来源进日志（即时/周期链 tag 不同，诊断时可对照），补传关键行以 drain 摘要为准
        Log.i(EventSyncWorkSpec.LOG_TAG, "worker run tags=$tags attempt=$runAttemptCount")
        val summary = queue.drain()
        return EventSyncWorkSpec.drainSummaryToResult(summary)
    }
}
