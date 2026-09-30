package media.qimeng.app.core.data.events

/**
 * 服务端 SSE 事件 topic 字面量（Android 消费侧唯一定义点）。
 *
 * 真相源：server/internal/events/bus.go 主题常量（运行时事件面**不入 openapi.yaml**，
 * 事件清单以 docs/GUIDE_API.md「实时推送」行为准，ADR-0029 记档的例外）——服务端
 * 增改 topic 名须同步此处与 [DataFreshnessSignal] 的映射，反之亦然（代码卫生约束 2/3）。
 * 未知 topic（含连接首帧 hello、scan.progress/thumbnail.progress/upload.done 等本门
 * 未接线的既有事件）由 [ServerEventConsumer] 忽略，零害。
 */
internal object ServerEventTopics {

    /** 库内容变更（资产集合面；订阅方：预取 revision 门一处收口 + 门信号汇） */
    const val LIBRARY_CHANGED = "library.changed"

    /** 收藏变更（设置/取消；幂等 no-op 照发，载荷 {"assetId"} 变更信号非状态面） */
    const val FAVORITE_CHANGED = "favorite.changed"

    /** 点赞变更（点赞/取消；载荷同构 {"assetId"}） */
    const val LIKE_CHANGED = "like.changed"
}
