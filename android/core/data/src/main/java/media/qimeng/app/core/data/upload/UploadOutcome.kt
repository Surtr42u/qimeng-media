package media.qimeng.app.core.data.upload

/**
 * 单次上传的终局分类（AssetUploader 的返回值；到 WorkManager Result 的映射在
 * [UploadWorkSpec.outcomeToResult]——分离是为了状态机可纯 JVM 单测）。
 */
sealed interface UploadOutcome {

    /**
     * 服务端 2xx：已入库；finalFileName = 冲突自动重命名后的最终名（UI 展示口径）。
     * assetId = 201 响应体 AssetDetail.id（自动挂靠的目标；解析失败为 null——此时
     * 挂靠序列无法发起，worker 收敛为 [AttachFailed]）。
     */
    data class Success(val finalFileName: String, val assetId: String? = null) : UploadOutcome

    /**
     * 已入库但自动挂靠失败（挂靠批）：文件本身已成功上传（重试挂靠绝不能重传文件），
     * worker 对本分支落 **Result.success + 挂靠失败标志**（failure 会级联杀链，同取消
     * 通道），队列行/UI 通知按「已入库但挂靠失败」专项文案指引补挂。
     * attachMessage = 失败环节与原因（作者/来源分步透传）。
     */
    data class AttachFailed(val finalFileName: String, val attachMessage: String) : UploadOutcome

    /**
     * 服务端 4xx：重试无意义（类型不白名单/超限/上传已关闭等）。
     * serverMessage = 服务端 Error{code,message} 文案透传（M4-5 冻结口径）。
     */
    data class Permanent(val serverMessage: String) : UploadOutcome

    /** 网络中断/服务端 5xx/本地流读取失败：按 WorkManager 官方 retry 语义保留重试路径 */
    data class Retryable(val reason: String) : UploadOutcome

    /**
     * 用户取消（批C 任务Q C-2）：写流中检测到取消标记（[UploadCancelRegistry]）→
     * 立即断流。不走 retry（取消不重试）、不走 4xx 文案透传，worker 据此落
     * CANCELLED 终态并同步通知。
     */
    data object Cancelled : UploadOutcome
}
