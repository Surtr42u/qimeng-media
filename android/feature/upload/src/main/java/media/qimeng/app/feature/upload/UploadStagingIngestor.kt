package media.qimeng.app.feature.upload

import kotlinx.coroutines.flow.first
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadRules

/**
 * 新进暂存摄取器（2026-09-25 暂存区重做，自 UploadViewModel 拆出控制 600 行红线）：
 * 单管道（系统文件 SAF 多选与系统分享共用）「转 StagedUpload + 继承批次默认 +
 * 按 source 去重写持久层」的单源编排。VM 只透传调用，JVM 单测经 VM 断言最终状态。
 * 2026-09-29 入口精简：收件箱扫描/相册多选/浏览文件三管道随上传页入口退役删除
 * （StagingRepository 的收件箱数据面保留——收件箱设置页仍读写）。
 */
internal class UploadStagingIngestor(
    private val uploadRepository: UploadRepository,
    private val stagingRepository: StagingRepository,
) {

    /**
     * SAF/系统分享接收：解元数据后进暂存区。describe 失败返回 false（VM 错误横幅），
     * 不写半批数据。
     */
    suspend fun ingestUris(uris: List<String>): Boolean {
        if (uris.isEmpty()) return true
        val described = try {
            uploadRepository.describe(uris)
        } catch (e: Exception) {
            return false
        }
        val batch = stagingRepository.batchConfig.first()
        stagingRepository.addItems(described.map { it.toStagedItem().withBatchDefaults(batch) })
        return true
    }

    /** describe 条目 → 暂存条目（isVideo 按展示名扩展名判定，历史收件箱扫描同源口径） */
    private fun UploadItem.toStagedItem() = StagedUpload(
        source = uri,
        isPathSource = UploadRules.isAbsoluteFilePath(uri),
        displayName = displayName,
        sizeBytes = sizeBytes,
        isVideo = UploadRules.isVideoExtension(displayName),
        addedAtMs = System.currentTimeMillis(),
    )

    /** 暂存条目继承批次默认（摄取管道唯一口径；已有挂靠值不覆盖） */
    private fun StagedUpload.withBatchDefaults(batch: StagingBatchConfig) = copy(
        attachAuthorId = attachAuthorId ?: batch.authorId,
        attachAuthorName = attachAuthorName ?: batch.authorName,
        attachSources = attachSources ?: batch.sources.takeIf { it.isNotEmpty() },
    )
}
