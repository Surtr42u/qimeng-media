package media.qimeng.app.core.data.events

import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest

/**
 * 行为补传的工作契约（M4-4，照 UploadWorkSpec 范式）：unique 链名 / 日志标签 / 周期口径 /
 * worker 终局状态机，全部纯常量+纯函数，单测锁定。
 *
 * 与上传链隔离：上传链 unique 名 = `qm-upload-serial-queue`（UploadWorkSpec），本队列用
 * [UNIQUE_SYNC_NOW_NAME]/[UNIQUE_SYNC_PERIODIC_NAME] 两个独立 unique 名——事件补传是
 * 无通知的低优先后台活，绝不与前台可见的上传任务互抢链位。
 */
object EventSyncWorkSpec {

    /** logcat 证据标签（grep 'QimengEventSync' 得补传时间线，文本证据协议 HANDOVER_APP §4.7） */
    const val LOG_TAG = "QimengEventSync"

    /** 即时补传 unique 链名（通道①写入即触发 / 通道②回前台共用；KEEP 合并并发触发） */
    const val UNIQUE_SYNC_NOW_NAME = "qm-event-sync-now"

    /** 周期兜底 unique 链名（通道③；KEEP 幂等注册，App 多次 onCreate 不叠加） */
    const val UNIQUE_SYNC_PERIODIC_NAME = "qm-event-sync-periodic"

    /** 两条链共用的诊断 tag */
    const val TAG_EVENT_SYNC = "qm-event-sync"

    /**
     * 周期兜底间隔 = WorkManager 系统下限 15 分钟（PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS，
     * 取官方常量而非手抄数值）：系统对更小间隔一律钳制到 15min，写小值只会自欺。
     * 弱网恢复语义不靠它独扛——即时通道（写入/回前台）先行，本周期只是兜底。
     */
    val PERIODIC_INTERVAL_MILLIS: Long = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS

    /**
     * drain 摘要 → worker 终局：恒 success。为什么不用 retry/failure——队列自持久化
     * （RETRY 行已回队、毒丸已裁决），WorkManager 层再叠加重试只会放大出网次数；
     * 恢复语义由三通道覆盖（下次写入 / 回前台 ON_START / 周期兜底）。
     */
    fun drainSummaryToResult(summary: ViewEventQueue.DrainSummary): ListenableWorker.Result =
        ListenableWorker.Result.success()
}
