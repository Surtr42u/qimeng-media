package media.qimeng.app.core.data.upload

import android.util.Log
import media.qimeng.app.core.data.repository.AuthorRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 挂靠序列结果：Done = 作者与来源全部挂上；Failed = 失败环节说明（透传给 ATTACH_FAILED 文案） */
sealed interface AttachOutcome {
    data object Done : AttachOutcome
    data class Failed(val message: String) : AttachOutcome
}

/**
 * 上传成功后的自动挂靠序列（挂靠批）：先 authors（PUT /assets/{id}/authors 单项整体替换，
 * 新上传资产零关联、传单项安全）后 sources（PUT /authors/{authorId}/sources mode=append，
 * 并入去重永不覆盖既有来源）——顺序固定（authors 可能触发服务端建作者块，sources append
 * 依赖块存在），两调用服务端均自带建块兜底。
 *
 * 失败语义（挂靠批红线）：**任何挂靠失败都不触发 worker 重试**（重试 = 重复上传文件），
 * 调用方（UploadWorker）把 Failed 收敛为 UploadOutcome.AttachFailed（success+标志终态）。
 * 本类只管「跑序列、分步归类原因」，终态映射在 UploadWorkSpec.outcomeToResult（纯函数单测锁定）。
 *
 * 走注入的 [AuthorRepository]（生产实现 = 生成 SDK，token/地址随 SDK 客户端；worker 依赖
 * 注入既有方式择简——与 AssetUploader 同为 Hilt 单例直注，不另起裸 HTTP 通道）。
 * SDK 的 4xx/5xx/网络异常统一以异常抛出，此处全部捕获归类为 Failed（挂靠失败不区分
 * 可否重试——本通道语义下永不重试）。
 */
@Singleton
class UploadAttacher @Inject constructor(
    private val authorRepository: AuthorRepository,
) {

    /**
     * 执行挂靠序列。[assetId] 为上传 201 响应体解析出的资产 id（UUID 字符串，非法形态
     * 在 SDK 侧回解析时抛出，同样归类 Failed）；[authorId]/[sources] 来自入队载荷。
     */
    suspend fun attach(assetId: String, authorId: String?, sources: List<String>?): AttachOutcome {
        val trimmedSources = sources.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (authorId.isNullOrBlank() && trimmedSources.isEmpty()) return AttachOutcome.Done
        if (authorId.isNullOrBlank()) {
            // 理论不可达（UI 侧来源以作者为前提、清作者连带清来源），防御性兜底给明确文案
            return AttachOutcome.Failed(ATTACH_SOURCES_NEED_AUTHOR)
        }
        return try {
            Log.i(UploadWorker.LOG_TAG, "attach authors asset=$assetId authorId=$authorId")
            authorRepository.replaceAssetAuthors(assetId, listOf(authorId))
            if (trimmedSources.isNotEmpty()) {
                Log.i(UploadWorker.LOG_TAG, "attach sources authorId=$authorId count=${trimmedSources.size}")
                authorRepository.appendAuthorSources(authorId, trimmedSources)
            }
            AttachOutcome.Done
        } catch (t: Throwable) {
            Log.w(UploadWorker.LOG_TAG, "attach failed asset=$assetId authorId=$authorId", t)
            AttachOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private companion object {
        const val ATTACH_SOURCES_NEED_AUTHOR = "已选来源但未选作者，来源未挂靠"
    }
}
