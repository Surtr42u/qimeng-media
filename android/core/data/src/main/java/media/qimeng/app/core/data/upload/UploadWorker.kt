package media.qimeng.app.core.data.upload

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import media.qimeng.app.core.model.UploadItem

/**
 * 上传 Worker（M4-5 串行队列的执行端）：
 * - 执行前升级前台服务（dataSync 类型，API 34+ 强制声明类型），进度走通知；
 * - 上传中节流回写 WorkManager progress（UI 观察队列状态用）；
 * - 201 之后按入队载荷执行自动挂靠序列（[UploadAttacher]：先 authors 后 sources append）；
 * - 终局判定全部收敛在 [UploadWorkSpec.outcomeToResult]（纯函数，单测锁定）。
 *
 * 前台服务升级失败（系统后台限制等）不阻断上传：catch 后继续以普通后台任务执行
 * （WorkManager 官方对 setForeground 失败的建议姿势——降级不失败）。
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploader: AssetUploader,
    private val attacher: UploadAttacher,
    private val cancelRegistry: UploadCancelRegistry,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val spec = UploadWorkSpec.specFromInputData(inputData)
        if (spec == null) {
            Log.e(LOG_TAG, "任务载荷缺失，终局失败")
            return Result.failure(workDataOf(UploadWorkSpec.KEY_ERROR_MESSAGE to "任务数据异常（载荷缺失）"))
        }

        // C-2 用户取消：排队中任务被标记取消时，轮到执行即直接落取消终态（不发起上传）。
        // 不调 WorkManager cancelWorkById 的原因见 [UploadCancelRegistry]（链级联取消违单任务语义）。
        if (cancelRegistry.isCancelled(spec.localId)) {
            Log.i(LOG_TAG, "cancelled-before-start file=${spec.displayName}")
            cancelRegistry.consume(spec.localId)
            return UploadWorkSpec.outcomeToResult(UploadOutcome.Cancelled, runAttemptCount)
        }

        Log.i(
            LOG_TAG,
            "start attempt=${runAttemptCount + 1} file=${spec.displayName} lib=${spec.libraryId} dir=${spec.dir}",
        )
        setForegroundSafely(spec.displayName)

        val outcome = resolveOutcome(
            uploadOutcome = uploader.upload(
                UploadItem(
                    uri = spec.uri,
                    displayName = spec.displayName,
                    sizeBytes = spec.sizeBytes,
                    uploadFileName = spec.uploadFileName,
                ),
                spec.libraryId,
                spec.dir,
                isCancelled = { cancelRegistry.isCancelled(spec.localId) },
            ) { done, total, percent ->
                setProgress(
                    workDataOf(
                        UploadWorkSpec.KEY_PROGRESS_PERCENT to percent,
                        UploadWorkSpec.KEY_DISPLAY_NAME to spec.displayName,
                    ),
                )
                updateForegroundNotification(spec.displayName, percent)
                if (percent % LOG_PROGRESS_STEP == 0) {
                    Log.d(LOG_TAG, "progress file=${spec.displayName} $done/$total ($percent%)")
                }
            },
            spec = spec,
        )
        cancelRegistry.consume(spec.localId)

        val result = UploadWorkSpec.outcomeToResult(outcome, runAttemptCount)
        when (outcome) {
            is UploadOutcome.Success -> {
                Log.i(LOG_TAG, "success file=${spec.displayName} final=${outcome.finalFileName}")
                notifyDone(spec.localId, spec.displayName, "上传完成：${outcome.finalFileName}")
            }

            is UploadOutcome.AttachFailed -> {
                // 挂靠批失败语义：文件已入库，绝不重试（重试=重复上传）；success+标志终态
                Log.w(LOG_TAG, "attach-failed file=${spec.displayName} final=${outcome.finalFileName}")
                notifyDone(
                    spec.localId,
                    spec.displayName,
                    "上传完成：${outcome.finalFileName}，" +
                        UploadWorkSpec.attachFailedMessage(outcome.attachMessage),
                )
            }

            is UploadOutcome.Permanent -> {
                Log.w(LOG_TAG, "rejected file=${spec.displayName} msg=${outcome.serverMessage}")
                notifyDone(spec.localId, spec.displayName, "上传失败：${outcome.serverMessage}")
            }

            is UploadOutcome.Cancelled -> {
                // C-2 取消：状态行翻「已取消」+ 完成通知同步；前台进度通知随 worker
                // 正常结束由 WorkManager 自动撤销。终态映射为 success+取消标志
                // （outcomeToResult）——failure 会级联杀链，342 笔返工修正
                Log.i(LOG_TAG, "cancelled file=${spec.displayName}")
                notifyDone(spec.localId, spec.displayName, "上传已取消：${spec.displayName}")
            }

            is UploadOutcome.Retryable -> {
                // 终局失败（重试耗尽）时才有完成通知；中途 retry 由退避调度自动续跑
                // （判定与 outcomeToResult 共用 isRetryExhausted，不触碰受限的 Result.Failure 类型）
                if (UploadWorkSpec.isRetryExhausted(runAttemptCount)) {
                    Log.w(LOG_TAG, "exhausted file=${spec.displayName} reason=${outcome.reason}")
                    notifyDone(
                        spec.localId,
                        spec.displayName,
                        "上传失败：重试 ${UploadWorkSpec.MAX_RETRIES} 次后仍失败",
                    )
                } else {
                    Log.w(
                        LOG_TAG,
                        "retry scheduled file=${spec.displayName} attempt=${runAttemptCount + 1} reason=${outcome.reason}",
                    )
                }
            }
        }
        return result
    }

    /**
     * 上传终局 → 本次执行终局：上传成功且载荷带挂靠参数时执行挂靠序列（先 authors 后
     * sources，见 [UploadAttacher]），失败收敛为 [UploadOutcome.AttachFailed]；上传非成功
     * 或无挂靠参数时原样透传（既有重试/取消/永久失败路径分毫不动）。
     *
     * 为什么挂靠失败不走 Result.retry：本函数返回后 outcomeToResult 对 AttachFailed 落
     * success+标志——worker 级重试会连文件一起重传（挂靠批失败语义红线）；挂靠调用本身
     * 一次机会，补挂由用户到作品编辑页完成。进程死亡窗口（201 落库后、挂靠完成前）由
     * WorkManager 重跑任务 + 服务端冲突自动重命名兜底，不引入主动重试路径。
     */
    private suspend fun resolveOutcome(
        uploadOutcome: UploadOutcome,
        spec: UploadWorkSpec.UploadRequestSpec,
    ): UploadOutcome {
        val needsAttach = spec.attachAuthorId != null || !spec.attachSources.isNullOrEmpty()
        if (uploadOutcome !is UploadOutcome.Success || !needsAttach) return uploadOutcome
        val assetId = uploadOutcome.assetId
            ?: return UploadOutcome.AttachFailed(uploadOutcome.finalFileName, ATTACH_NO_ASSET_ID)
        return when (val attach = attacher.attach(assetId, spec.attachAuthorId, spec.attachSources)) {
            is AttachOutcome.Done -> uploadOutcome
            is AttachOutcome.Failed -> UploadOutcome.AttachFailed(uploadOutcome.finalFileName, attach.message)
        }
    }

    /** 前台服务升级：API 34+ 必须带 dataSync 类型；任何失败降级为普通后台任务。 */
    private suspend fun setForegroundSafely(title: String) {
        try {
            UploadNotifications.ensureChannel(applicationContext)
            val notification = UploadNotifications.progress(applicationContext, title, 0)
            val info = if (Build.VERSION.SDK_INT >= 34) {
                ForegroundInfo(
                    UploadNotifications.FOREGROUND_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(UploadNotifications.FOREGROUND_NOTIF_ID, notification)
            }
            setForeground(info)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "前台服务升级失败，降级为后台任务继续上传", t)
        }
    }

    /** 前台通知进度刷新（未获通知权限/构建失败一律静默跳过，不影响上传） */
    private fun updateForegroundNotification(title: String, percent: Int) {
        if (!UploadNotifications.canNotify(applicationContext)) return
        try {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                UploadNotifications.FOREGROUND_NOTIF_ID,
                UploadNotifications.progress(applicationContext, title, percent),
            )
        } catch (t: Throwable) {
            Log.d(LOG_TAG, "通知刷新失败（忽略）", t)
        }
    }

    private fun notifyDone(localId: String, title: String, text: String) {
        if (!UploadNotifications.canNotify(applicationContext)) return
        try {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                UploadNotifications.doneNotifId(localId),
                UploadNotifications.done(applicationContext, title, text),
            )
        } catch (t: Throwable) {
            Log.d(LOG_TAG, "完成通知失败（忽略）", t)
        }
    }

    companion object {
        /** logcat 证据标签（grep 'QimengUpload' 得上传时间线，文本证据协议 §4.7） */
        const val LOG_TAG = "QimengUpload"

        /** 进度日志步长（%）：全量打点刷屏，25% 粒度足够串行时间线举证 */
        private const val LOG_PROGRESS_STEP = 25

        /** 201 响应体缺 id 时的挂靠失败原因（协议 AssetDetail.id 理论恒在，防御性兜底） */
        private const val ATTACH_NO_ASSET_ID = "服务端未返回资产 id，无法挂靠"
    }
}
