package media.qimeng.app.feature.upload

import kotlinx.coroutines.flow.first
import media.qimeng.app.core.data.repository.BrowserFileEntry
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadRules

/**
 * 新进暂存摄取器（2026-09-25 暂存区重做，自 UploadViewModel 拆出控制 600 行红线）：
 * 四条管道（收件箱扫描 / 相册多选 / SAF 与系统分享 / 浏览文件多选）统一「转 StagedUpload +
 * 继承批次默认 + 按 source 去重写持久层」的单源编排。VM 只透传调用，JVM 单测经 VM 断言最终状态。
 */
internal class UploadStagingIngestor(
    private val uploadRepository: UploadRepository,
    private val stagingRepository: StagingRepository,
) {

    /** 收件箱导入结果（VM 转提示文案；[itemsAdded] = 实际新进暂存的条目数） */
    data class InboxImportResult(val itemsAdded: Int)

    /**
     * 收件箱导入：扫描（白名单过滤 + uploaded/ 排除 + 修改时间倒序）→ 去重已暂存 source
     * → 新文件按批次默认进暂存区。未设收件箱返回 null（VM 提示去设置）。
     */
    suspend fun importFromInbox(): InboxImportResult? {
        if (stagingRepository.inboxPath.first() == null) return null
        val known = stagingRepository.stagedItems.first().mapTo(mutableSetOf()) { it.source }
        val fresh = stagingRepository.scanInbox().filterNot { it.source in known }
        if (fresh.isEmpty()) return InboxImportResult(itemsAdded = 0)
        val batch = stagingRepository.batchConfig.first()
        stagingRepository.addItems(fresh.map { it.withBatchDefaults(batch) })
        return InboxImportResult(itemsAdded = fresh.size)
    }

    /** 相册多选接收：MediaStore 元数据直用（不查 describe），继承批次默认进暂存区 */
    suspend fun ingestPicked(picked: List<LocalMediaItem>) {
        if (picked.isEmpty()) return
        val batch = stagingRepository.batchConfig.first()
        stagingRepository.addItems(picked.map { it.toStagedItem().withBatchDefaults(batch) })
    }

    /**
     * 浏览文件多选接收（2026-09-28「浏览文件」弹层）：绝对路径类条目，File 元数据在
     * 列举侧（listMediaFiles）已带齐（白名单也已在列举侧过滤），无需再查 describe；
     * 去重口径与收件箱导入同款（已暂存 source 跳过）。返回实际新进条目数（VM 转提示）。
     */
    suspend fun ingestPickedFiles(files: List<BrowserFileEntry>): Int {
        if (files.isEmpty()) return 0
        val known = stagingRepository.stagedItems.first().mapTo(mutableSetOf()) { it.source }
        val fresh = files.filterNot { it.path in known }.map { it.toStagedItem() }
        if (fresh.isEmpty()) return 0
        val batch = stagingRepository.batchConfig.first()
        stagingRepository.addItems(fresh.map { it.withBatchDefaults(batch) })
        return fresh.size
    }

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

    /** 选择器条目 → 暂存条目（isVideo 用 MediaStore 给值；文件级上传无相对子目录） */
    private fun LocalMediaItem.toStagedItem() = StagedUpload(
        source = uri,
        isPathSource = UploadRules.isAbsoluteFilePath(uri),
        displayName = displayName,
        sizeBytes = sizeBytes,
        isVideo = isVideo,
        addedAtMs = System.currentTimeMillis(),
    )

    /** 浏览文件条目 → 暂存条目（字段口径与收件箱扫描 scanInbox 同款：isPathSource 恒真、
     *  isVideo 按扩展名判定；缩略图走 file:// 直读，作品名默认=文件基名） */
    private fun BrowserFileEntry.toStagedItem() = StagedUpload(
        source = path,
        isPathSource = true,
        displayName = name,
        sizeBytes = sizeBytes,
        isVideo = UploadRules.isVideoExtension(name),
        addedAtMs = System.currentTimeMillis(),
    )

    /** describe 条目 → 暂存条目（isVideo 按展示名扩展名判定，与收件箱扫描同源口径） */
    private fun UploadItem.toStagedItem() = StagedUpload(
        source = uri,
        isPathSource = UploadRules.isAbsoluteFilePath(uri),
        displayName = displayName,
        sizeBytes = sizeBytes,
        isVideo = UploadRules.isVideoExtension(displayName),
        addedAtMs = System.currentTimeMillis(),
    )

    /** 暂存条目继承批次默认（三条摄取管道共用一条口径；已有挂靠值不覆盖） */
    private fun StagedUpload.withBatchDefaults(batch: StagingBatchConfig) = copy(
        attachAuthorId = attachAuthorId ?: batch.authorId,
        attachAuthorName = attachAuthorName ?: batch.authorName,
        attachSources = attachSources ?: batch.sources.takeIf { it.isNotEmpty() },
    )
}
