package media.qimeng.app.core.model

/**
 * 历史条目（GET /history 每资产一条、lastViewedAt 倒序——服务端口径）。
 * 浏览时间在 [MediaAsset.lastViewedAtMs]。
 */
data class HistoryEntry(
    val asset: MediaAsset,
)
