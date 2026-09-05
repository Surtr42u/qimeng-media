package media.qimeng.app.core.model

/**
 * 首页推荐流分批切片（拍板口径：`GET /recommendations?limit=200` 一次拉满，
 * 本地按约 20 条分批渲染，滚到底用新 seed 重拉；后处理全服务端承担，客户端只管分批）。
 * 纯函数，单测锁定。
 */
object RecommendPaging {

    /** 单次拉取上限（协议 limit ≤200；拍板「一次拉满」取上限） */
    const val PULL_LIMIT = 200

    /** 本地分批渲染步长（旧版首页触底 +20 的节奏延续） */
    const val BATCH_SIZE = 20

    /** 距底部 ≤6 项时预加载下一批（LEGACY §H 性能约束） */
    const val PRELOAD_DISTANCE = 6

    /**
     * 已展示 [revealed] 条、已拉取 [pulled] 条时，触底应揭示到的目标条数。
     * @return 新的揭示数；null = 本轮 200 条已全部揭示，需换 seed 重拉
     */
    fun nextReveal(revealed: Int, pulled: Int): Int? {
        if (revealed >= pulled) return null
        return minOf(revealed + BATCH_SIZE, pulled)
    }

    /** 是否应触发加载（揭示数距已渲染末尾 ≤ PRELOAD_DISTANCE 项） */
    fun shouldLoadMore(visibleLastIndex: Int, revealed: Int): Boolean =
        visibleLastIndex >= revealed - 1 - PRELOAD_DISTANCE
}
