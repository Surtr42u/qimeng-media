package media.qimeng.app.core.data.upload

import androidx.work.WorkInfo
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus

/** 只适配队列显示；不承担出网、入队或上传业务。取消与挂靠终态在此单源映射。 */
@Singleton
class UploadQueuePresenter @Inject constructor(private val cancelRegistry: UploadCancelRegistry) {
    private val lock = Any()
    private val knownNames = LinkedHashMap<String, String>()
    private val queueOrder = mutableListOf<String>()

    fun remember(localId: String, displayName: String) = synchronized(lock) {
        knownNames[localId] = displayName
        queueOrder.add(localId)
        Unit
    }

    fun entries(infos: List<WorkInfo>): List<UploadQueueEntry> {
        val (names, order) = synchronized(lock) { knownNames.toMap() to queueOrder.toList() }
        return infos.mapNotNull { toEntry(it, names) }.sortedBy { entry ->
            order.indexOf(entry.localId).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    private fun toEntry(info: WorkInfo, knownNames: Map<String, String>): UploadQueueEntry? {
        val localId = UploadWorkSpec.localIdFromTag(info.tags) ?: return null
        val displayName = info.progress.getString(UploadWorkSpec.KEY_DISPLAY_NAME)
            ?: UploadWorkSpec.displayNameFromTags(info.tags)
            ?: knownNames[localId]
            ?: "上传任务"
        // 取消标志两侧识别（342 笔返工）：取消终态走 success 载荷（failure 会级联杀链，
        // 见 UploadWorkSpec.outcomeToResult）——SUCCEEDED+标志=CANCELLED；FAILED+标志
        // 保留兜底（防旧数据/其他路径）。WorkManager 的 State.CANCELLED 是引擎自身
        // 取消态，客户端从不主动触发（见 UploadCancelRegistry），出现时按失败兜底
        // 挂靠批：SUCCEEDED+挂靠失败标志=ATTACH_FAILED（文件已入库、挂靠未成的专项态，
        // 同样走 success 通道——failure 会级联杀链，重试路径被挂靠批失败语义红线禁用）
        val output = info.outputData
        val cancelledByUser = output.getBoolean(UploadWorkSpec.KEY_CANCELLED, false)
        val attachFailed = output.getBoolean(UploadWorkSpec.KEY_ATTACH_FAILED, false)
        val status = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStatus.QUEUED
            WorkInfo.State.RUNNING -> UploadStatus.UPLOADING
            WorkInfo.State.SUCCEEDED ->
                when {
                    output.getBoolean(UploadWorkSpec.KEY_TARGET_REJECTED, false) -> UploadStatus.FAILED
                    cancelledByUser -> UploadStatus.CANCELLED
                    attachFailed -> UploadStatus.ATTACH_FAILED
                    else -> UploadStatus.SUCCEEDED
                }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                if (cancelledByUser) UploadStatus.CANCELLED else UploadStatus.FAILED
        }
        // 取消标记已置位但 WorkManager 终态未落（排队中刚点取消）：立即展示「已取消」，
        // 等任务轮到时 worker 短路落终态，状态不回跳（终态映射与标记一致）
        val effectiveStatus = if (status != UploadStatus.SUCCEEDED && status != UploadStatus.FAILED &&
            cancelRegistry.isCancelled(localId)
        ) {
            UploadStatus.CANCELLED
        } else {
            status
        }
        return UploadQueueEntry(
            localId = localId,
            displayName = displayName,
            status = effectiveStatus,
            progressPercent = info.progress.getInt(UploadWorkSpec.KEY_PROGRESS_PERCENT, -1)
                .takeIf { it >= 0 },
            finalFileName = info.outputData.getString(UploadWorkSpec.KEY_FINAL_FILE_NAME),
            errorMessage = info.outputData.getString(UploadWorkSpec.KEY_ERROR_MESSAGE),
        )
    }

}
