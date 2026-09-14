package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.LibraryKind
import media.qimeng.app.core.model.LibrarySummary

/**
 * 媒体库管理数据端口（U10-6）。协议 = openapi /libraries 族（本任务零协议改动）：
 * GET/POST /api/v1/libraries、DELETE /api/v1/libraries/{id}、
 * POST /api/v1/libraries/{id}/scan（202 已受理）、PUT /api/v1/libraries/{id}/enabled。
 * 全部经生成 SDK 调用（ADR-0009：禁手写客户端、禁手改生成物）。
 */
interface LibraryRepository {

    /** 库列表（GET /libraries） */
    suspend fun libraries(): List<LibrarySummary>

    /**
     * 注册新库（POST /libraries），返回服务端落库条目（含 id，供「注册并扫描」续接）。
     * 响应异常缺 id 时返回 null（调用方跳过续接扫描、仍刷新列表，对齐 Web 的
     * `if (lib.id)` 守卫）；协议级失败以异常抛出。
     */
    suspend fun register(name: String, rootPath: String, kind: LibraryKind): LibrarySummary?

    /** 删除库（DELETE /libraries/{id}；语义=索引清除，磁盘文件不动——服务端职责） */
    suspend fun delete(libraryId: String)

    /** 触发重扫（POST /libraries/{id}/scan，202 受理即成功返回） */
    suspend fun scan(libraryId: String)

    /** 启停库（PUT /libraries/{id}/enabled；停用=浏览面隐藏，记录全保留） */
    suspend fun setEnabled(libraryId: String, enabled: Boolean)
}
