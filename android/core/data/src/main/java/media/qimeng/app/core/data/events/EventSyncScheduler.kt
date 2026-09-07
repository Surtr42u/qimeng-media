package media.qimeng.app.core.data.events

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行为补传调度器（M4-4）：把「什么时候 drain」收口到一处。
 *
 * **刻意不加 NetworkType.CONNECTED 约束**（与备忘录 §3.2 相反，主会话拍板）：同仓上传链实测
 * 该约束依赖系统网络 VALIDATED 判定，弱网/隔离网段下任务悬置不跑（先例注释
 * SdkUploadRepository.kt:104-108）；恢复语义由即时触发（写入/回前台）+ 周期兜底覆盖，
 * 不依赖「系统宣告网络可用」这一前提。
 */
@Singleton
class EventSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * 通道①/②：请求一次即时补传。KEEP——drain 本身清空全队列（幂等），瞬时多次触发
     * （连开几个详情页）合并成一次执行即可，无需 APPEND 叠任务。
     */
    fun requestSyncNow() {
        workManager.enqueueUniqueWork(
            EventSyncWorkSpec.UNIQUE_SYNC_NOW_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EventSyncWorker>()
                .addTag(EventSyncWorkSpec.TAG_EVENT_SYNC)
                .build(),
        )
    }

    /**
     * 通道③：注册周期兜底（KEEP 幂等，App 每次 onCreate 调用不叠加）。
     * 间隔=系统 15min 下限（见 [EventSyncWorkSpec.PERIODIC_INTERVAL_MILLIS] 注释）。
     */
    fun ensurePeriodicSync() {
        workManager.enqueueUniquePeriodicWork(
            EventSyncWorkSpec.UNIQUE_SYNC_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EventSyncWorker>(
                EventSyncWorkSpec.PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
                .addTag(EventSyncWorkSpec.TAG_EVENT_SYNC)
                .build(),
        )
    }
}
