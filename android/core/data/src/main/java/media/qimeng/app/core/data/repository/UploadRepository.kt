package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry

/** 上传入队回执（UI 用它把 WorkManager 状态流对回本会话选择的文件） */
data class QueuedUpload(
    val localId: String,
    val displayName: String,
)

/**
 * 上传主通道数据端口（M4-5）：目标库/目录树/新建目录/客户端配置 + WorkManager 队列。
 * 目录与配置走生成 SDK；字节流上传走 AssetUploader（OkHttp 流式，冻结架构，见该类注释）。
 */
interface UploadRepository {

    /** 可选目标库列表（GET /libraries） */
    suspend fun libraries(): List<LibraryChoice>

    /**
     * 作者联想（GET /authors/suggest）：上传页挂靠（批次默认/逐项编辑）与资产编辑页
     * 添加作者输入框共用数据源。子串匹配/别名命中/大小写不敏感全在服务端，客户端只透传词条。
     */
    suspend fun suggestAuthors(q: String, limit: Int = DEFAULT_SUGGEST_LIMIT): List<AuthorSuggestion>

    /** 目标库目录树（GET /dirs，libraryId 必填；根节点 path=""） */
    suspend fun dirTree(libraryId: String): DirNode

    /** 幂等新建子目录（POST /dirs；已存在视为成功——服务端 MkdirAll 语义） */
    suspend fun createDir(libraryId: String, path: String)

    /** 客户端配置 upload 组（GET /api/v1/config；实时生效，入队前现取现判） */
    suspend fun uploadLimits(): UploadLimits

    /**
     * 解析 SAF/分享 URI 的展示名与字节数（未知大小回 -1，交服务端 413 兜底）。
     * uri 用 String 承载（content:// 形态）：Android Uri 解析收口在实现层，
     * 接口保持纯字符串契约（:core:model 同口径，也便于 JVM 单测）。
     */
    suspend fun describe(uris: List<String>): List<UploadItem>

    /**
     * 入队（串行 unique 链，并发=1）。返回入队回执（含客户端 localId）。
     * 网络约束 + 退避重试在请求侧声明：断网自动等待恢复，中断按官方 retry 语义续跑。
     * 挂靠批：UploadItem 的 effectiveUploadName/attachAuthorId/attachSources 随载荷入队；
     * 挂靠执行在 worker 的 201 之后（UploadAttacher，mode=append，失败不重试不级联）。
     */
    fun enqueue(
        items: List<UploadItem>,
        libraryId: String,
        dir: String,
    ): List<QueuedUpload>

    /**
     * 取消单个队列任务（批C 任务Q C-2，排队中与上传中皆可）。
     * 协作式取消（置标记 → worker 自行终止），不调 WorkManager cancelWorkById——
     * 官方语义下取消会级联取消链上依赖它的后续任务，违反单任务取消（依据记档在
     * [media.qimeng.app.core.data.upload.UploadCancelRegistry]）。
     * 终态经 success+取消标志落盘（failure 同样级联杀链，342 笔返工修正），
     * 取消任务的下游排队任务正常解锁执行。
     */
    fun cancel(localId: String)

    /** 队列状态流（WorkManager WorkInfo 映射；UI 直接渲染 UploadQueueEntry） */
    fun queueUpdates(): Flow<List<UploadQueueEntry>>

    companion object {
        /**
         * 作者联想默认条数——与 openapi.yaml GET /authors/suggest 的 limit default=10 双写，
         * 协议侧改动须同步此处，反之亦然（代码卫生约束 3）。
         */
        const val DEFAULT_SUGGEST_LIMIT = 10
    }
}
