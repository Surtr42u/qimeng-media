package media.qimeng.app.core.data.events

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据新鲜度信号快照（三计数器的原子单读）：门判定一次取全，避免逐计数器读的
 * 中间态被误当成完整快照。
 */
data class FreshnessSnapshot(
    val library: Long,
    val favorite: Long,
    val like: Long,
) {
    /** 无事件历史（进程刚启动且 SSE 未开流的零值快照） */
    companion object {
        val ZERO = FreshnessSnapshot(library = 0L, favorite = 0L, like = 0L)
    }
}

/**
 * 服务端数据新鲜度信号汇（ADR-0029 客户端半，2026-10-01）：SSE 流上三类变更事件的
 * 统一计数器。消费方（FavoriteMutationTracker/LikeMutationTracker 跳过门）在列表拉取
 * 成功时记下当时快照，判定时对比——计数变了即「服务端数据在我拉取后变过」，放行重拉。
 *
 * 为什么是计数器而非事件流：门只需要「变没变」的答案，不需要事件内容（事件是变更信号
 * 不是状态面，收到后重拉权威状态即可，ADR-0029 轻载荷取舍）；计数器把「断线窗口错过
 * 的 N 条事件」压缩成一个可比对的单调值，消费方零缓冲零回放。
 *
 * 事件 → 计数映射（topic 字符串真相源在 [ServerEventTopics]，与服务端
 * server/internal/events/bus.go 主题常量双同步）：
 * - `library.changed` → [onLibraryChanged]（资产集合面，ADR-0026 语义边界）
 * - `favorite.changed` → [onFavoriteChanged]（收藏变更信号，不 bump 修订号）
 * - `like.changed` → [onLikeChanged]（点赞变更信号，不 bump 修订号）
 * - 未知事件（hello 帧/未来新事件）由消费端过滤，不进本类
 *
 * 线程口径：bump 来自 SSE 监听回调（OkHttp 线程），snapshot 读自 Main（跳过门判定），
 * 全 AtomicLong 保证跨线程可见；计数只增不减、溢出在 Long 域不可达（每秒百次变更也要
 * 29 万年），不设回绕。
 */
@Singleton
class DataFreshnessSignal @Inject constructor() {

    private val libraryCount = AtomicLong(0L)
    private val favoriteCount = AtomicLong(0L)
    private val likeCount = AtomicLong(0L)

    /** library.changed 计入（资产集合面变更信号） */
    fun onLibraryChanged() {
        libraryCount.incrementAndGet()
    }

    /** favorite.changed 计入（跨端收藏变更信号） */
    fun onFavoriteChanged() {
        favoriteCount.incrementAndGet()
    }

    /** like.changed 计入（跨端点赞变更信号） */
    fun onLikeChanged() {
        likeCount.incrementAndGet()
    }

    /**
     * SSE 流建立成功（onOpen，含首次连接与每次重连成功）时三计数一起 bump：对齐 Web 端
     * 「重连成功即失效根查询重新校验」语义（第四百一十六笔 onOpen 通道同款）——断线窗口
     * 内错过的事件无法逐条补发，重连成功后让所有门失效一次，多拉一次幂等无害（失败方向
     * 落在「多拉」，与全仓「多拉永远安全」口径一致）。
     */
    fun onStreamOpened() {
        libraryCount.incrementAndGet()
        favoriteCount.incrementAndGet()
        likeCount.incrementAndGet()
    }

    /** 当前快照（跳过门打点与判定各取一次对比） */
    fun snapshot(): FreshnessSnapshot = FreshnessSnapshot(
        library = libraryCount.get(),
        favorite = favoriteCount.get(),
        like = likeCount.get(),
    )
}
