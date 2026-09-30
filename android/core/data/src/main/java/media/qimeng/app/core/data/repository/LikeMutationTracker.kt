package media.qimeng.app.core.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ON_RESUME 列表跳过门的陈旧上界（staleTime 语义，2026-10-01 跳过门 TTL 化）。
 *
 * 为什么需要：服务端对收藏/点赞不发事件（openapi.yaml /api/v1/events 事件清单只有
 * scan.progress / library.changed / thumbnail.progress / upload.done），进程内指纹只统计
 * **本进程本端**的改动——Web 端/另一设备的收藏/点赞改动指纹永不变化，跳过门若只看
 * 指纹则永不放行，列表与库不符直到手动下拉或杀进程（第四百一十五笔修复动机）。
 *
 * 语义：跳过条件 = 「指纹未变」且「距上次成功列表拉取 < 本值」。打点见
 * [LikeMutationTracker.noteListFetchCompleted] / [FavoriteMutationTracker.noteListFetchCompleted]，
 * 判定见 [LikeMutationTracker.isListFetchFresh]（指纹对比留在消费点 VM）。跨端改动至多
 * 5 分钟 + 一次 ON_RESUME 即纠正；TTL 内仍跳过，「纯浏览返回零网络零重组」的优化
 * 价值保留。取 5 分钟 = 主流客户端缓存 staleTime 常用档（TanStack Query
 * refetchOnWindowFocus 同款思路）。进程内指纹仍负责「本端刚改过立即刷新」，不受本上界影响。
 */
const val STALE_AFTER_MS = 5L * 60 * 1000

/** 点赞变更指纹快照（likeVersion 计数器 + 最近变更时间戳；相等即「无新变更」） */
data class LikeFingerprint(
    val likeVersion: Long,
    val lastMutatedAtMs: Long,
)

/**
 * 本地点赞变更指纹（GUIDE_UI §下拉刷新 L89「点赞后返回自动重排」的感知基座，任务I I1）。
 *
 * 为什么是本地感知：SSE 无 like 事件（openapi.yaml /api/v1/events 事件清单只有
 * scan.progress / library.changed / thumbnail.progress / upload.done），
 * 本地感知是协议内唯一路径。
 *
 * 本地路径的盲区与 TTL 兜底（2026-10-01 跳过门 TTL 化，完整语义见 [STALE_AFTER_MS]）：
 * 指纹只统计本进程本端的点赞改动，跨端（Web/另一设备）改动指纹永不变化——跳过门
 * 叠加 [STALE_AFTER_MS] 时间上界兜底：指纹未变但距上次成功列表拉取超时即放行重拉，
 * 跨端陈旧至多 5 分钟自愈；本端刚改过立即刷新的语义不变。
 *
 * **detail 侧上报点：I7 批在详情页点赞成功处调用 [onLikeMutated]**（本批 I1 只交付
 * tracker 本体 + 首页响应端，全链实测随 I7 验收）。首页侧在返回/回前台（ON_RESUME）时
 * 对比指纹，变化则重拉当前 tab（推荐/排行榜各自重拉，重排效果由服务端打分决定，
 * 客户端不做语义假设）；「浏览退出保持原样」半边由「无 like 变更→指纹不变→不重拉」天然满足。
 * 消费点：HomeViewModel.onHomeResumed（跳过判定 = 指纹未变 且 [isListFetchFresh]）。
 *
 * 形态沿用 [MediaBatchIndex] 先例：纯进程内状态（无出网/无持久化），
 * @Singleton 构造注入无需 @Binds；volatile/原子保证跨线程读写可见。
 */
@Singleton
class LikeMutationTracker @Inject constructor() {

    /** likeVersion 计数器：每次点赞变更 +1（单调递增，进程内唯一） */
    private val likeVersion = AtomicLong(0L)

    /** 最近一次变更的时间戳（墙钟，仅随指纹携带供观测，不参与相等性以外语义） */
    private val lastMutatedAtMs = AtomicLong(0L)

    /** 上次成功列表拉取完成时刻（墙钟 ms；0=本进程内尚未成功拉取过，视为陈旧） */
    private val lastListFetchAtMs = AtomicLong(0L)

    /**
     * 时钟源（TTL 打点与判定用；public 可变=单测注入假钟入口，生产恒默认墙钟）。
     * 为什么 public 而非 internal：跳过门消费点在 feature 模块（Favorite/HomeViewModel），
     * 其单测要推进时钟构造「超 TTL」场景，internal 对 feature 模块不可见；公开可变
     * 进程内状态有 [MediaBatchIndex.ids] 先例。为什么不是构造注入：Hilt @Inject
     * constructor 无法提供函数类型绑定（HomeViewModel.clockMs 同款裁决，注释互指）。
     */
    var clockMs: () -> Long = System::currentTimeMillis

    /** 详情页点赞成功后调用（I7 批接线；重复调用即版本继续递增，语义安全） */
    fun onLikeMutated() {
        lastMutatedAtMs.set(clockMs())
        likeVersion.incrementAndGet()
    }

    /** 首页列表拉取成功落地后调用（含翻页追加；TTL 计时起点，失败不打点。推荐流换 seed 续拉 appendNextSeedRound 不打点——方向保守，至多多拉一次） */
    fun noteListFetchCompleted() {
        lastListFetchAtMs.set(clockMs())
    }

    /**
     * 跳过门的 TTL 半边：距上次成功列表拉取是否仍在 [STALE_AFTER_MS] 内（严格小于，
     * 恰等于即陈旧）。与「指纹未变」（消费点 VM 对比）相与才是完整跳过条件；从未成功
     * 拉取（0 哨兵，如首载失败）恒不新鲜——放行 ON_RESUME 兜一次重拉，方向与
     * 「多拉一次无害」一致。
     */
    fun isListFetchFresh(): Boolean {
        val at = lastListFetchAtMs.get()
        if (at <= 0L) return false
        return clockMs() - at < STALE_AFTER_MS
    }

    /** 当前指纹快照（首页 ON_RESUME 时取走并与上次留存对比） */
    fun fingerprint(): LikeFingerprint = LikeFingerprint(likeVersion.get(), lastMutatedAtMs.get())
}
