package media.qimeng.app.core.data.repository

/**
 * 库修订号单值端口（2026-09-30 预取 revision 跳过批）。协议 = openapi
 * GET /api/v1/library/revision（本任务零协议改动）：全局单计数器，资产集合任何
 * 变更（扫描入库/上传/回收站/恢复/导入等）都单调自增、重启不回退。
 *
 * **失败降级语义（端口约定，调用方无需再捕获）**：任何失败——网络错、旧服务端
 * 404、响应解析错、构造 API 时地址未就绪——一律返回 null，**不抛出**；null 由
 * media.qimeng.app.core.data.prefetch.PrefetchRevisionGate 判 FULL 走原全量轮
 * （缺省偏多拉，服务端语义「多拉永远安全」同口径）。唯一例外是协程取消照常上抛。
 */
interface LibraryRevisionRepository {

    /** 当前库内容修订号；端点不可用/失败时为 null（降级，不许抛出） */
    suspend fun revision(): Long?
}
