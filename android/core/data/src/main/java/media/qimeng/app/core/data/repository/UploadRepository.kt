package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
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
     */
    fun enqueue(items: List<UploadItem>, libraryId: String, dir: String): List<QueuedUpload>

    /** 队列状态流（WorkManager WorkInfo 映射；UI 直接渲染 UploadQueueEntry） */
    fun queueUpdates(): Flow<List<UploadQueueEntry>>
}
