package media.qimeng.app.core.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.data.events.DataFreshnessSignal
import media.qimeng.app.core.data.events.FreshnessSnapshot

/**
 * ON_RESUME 列表跳过门的陈旧上界（staleTime 语义，2026-10-01 跳过门 TTL 化）。
 *
 * 历史（第四百一十五笔引入时）：服务端对收藏/点赞不发事件，进程内指纹只统计
 * **本进程本端**的改动——Web 端/另一设备的收藏/点赞改动指纹永不变化，跳过门若只看
 * 指纹则永不放行，列表与库不符直到手动下拉或杀进程。
 *
 * 现口径（2026-10-01 ADR-0029 门收敛修订）：服务端已补发 favorite.changed /
 * like.changed，Android 经 SSE 信号汇（[DataFreshnessSignal]）秒级感知跨端变更——
 * 本值从「唯一的跨端自愈手段」**降级为离线兜底**：SSE 断线/离线窗口内信号收不到，
 * TTL 是最后的时间上界（至多 5 分钟 + 一次 ON_RESUME 自愈）。事件驱动为主、TTL 兜底
 * 为辅，两层不互斥；TTL 内仍跳过，「纯浏览返回零网络零重组」的优化价值保留。
 * 取 5 分钟 = 主流客户端缓存 staleTime 常用档（TanStack Query refetchOnWindowFocus
 * 同款思路）。进程内指纹仍负责「本端刚改过立即刷新」，不受本上界影响。
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
 * 三层新鲜度（2026-10-01 ADR-0029 门收敛修订，与 [FavoriteMutationTracker] 镜像同构）：
 * 本端指纹（[fingerprint]，消费点 VM 对比——详情页点赞成功 [onLikeMutated] 后返回，
 * 「浏览退出保持原样」半边由「无 like 变更→指纹不变→不重拉」天然满足）+ 服务端 SSE
 * 信号（[DataFreshnessSignal]，历史记录：SSE 曾无 like 事件、本地感知是协议内唯一路径，
 * ADR-0029 服务端补发后跨端变更秒级感知）+ TTL（[STALE_AFTER_MS]，SSE 断线/离线窗口
 * 的兜底上界）。本类承担后两半边的判定（[isListFetchFresh]）。
 *
 * 门收敛关联（哪个门关联哪几个信号计数，按数据来源定）：首页三 tab（推荐/排行榜/COS）
 * 的数据面 = 打分/榜单排序输入 + 资产集合——点赞变化影响服务端打分与榜单（重排效果由
 * 服务端决定，客户端不做语义假设），故 [isListFetchFresh] 关联 like.changed +
 * library.changed 两计数（favorite 变更与首页列表无数据关联，不触发）。
 * 消费点：HomeViewModel.onHomeResumed（跳过判定 = 指纹未变 且 [isListFetchFresh]）。
 *
 * 形态沿用 [MediaBatchIndex] 先例：纯进程内状态（无出网/无持久化），
 * @Singleton 构造注入无需 @Binds；volatile/原子保证跨线程读写可见。
 */
@Singleton
class LikeMutationTracker @Inject constructor(
    private val freshnessSignal: DataFreshnessSignal,
) {

    /** likeVersion 计数器：每次点赞变更 +1（单调递增，进程内唯一） */
    private val likeVersion = AtomicLong(0L)

    /** 最近一次变更的时间戳（墙钟，仅随指纹携带供观测，不参与相等性以外语义） */
    private val lastMutatedAtMs = AtomicLong(0L)

    /** 上次成功列表拉取完成时刻（墙钟 ms；0=本进程内尚未成功拉取过，视为陈旧） */
    private val lastListFetchAtMs = AtomicLong(0L)

    /** 上次成功列表拉取完成时的信号快照（[isListFetchFresh] 信号半边的对比基线；零值=尚未采样） */
    @Volatile
    private var signalAtLastFetch: FreshnessSnapshot = FreshnessSnapshot.ZERO

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

    /** 首页列表拉取成功落地后调用（含翻页追加；TTL 计时起点 + 信号基线采样，失败不打点。推荐流换 seed 续拉 appendNextSeedRound 不打点——方向保守，至多多拉一次） */
    fun noteListFetchCompleted() {
        lastListFetchAtMs.set(clockMs())
        signalAtLastFetch = freshnessSignal.snapshot()
    }

    /**
     * 跳过门的两半边判定：与「指纹未变」（消费点 VM 对比）相与才是完整跳过条件。半边①
     * TTL——距上次成功列表拉取仍在 [STALE_AFTER_MS] 内（严格小于，恰等于即陈旧），从未
     * 成功拉取（0 哨兵，如首载失败）恒不新鲜——放行 ON_RESUME 兜一次重拉，方向与「多拉
     * 一次无害」一致；半边② 信号——[noteListFetchCompleted] 采样后的 like.changed /
     * library.changed 计数任一增长即不新鲜（跨端点赞/资产集合变更秒级感知；幂等 no-op
     * 事件多 bump 无害只是多拉一次）。TTL 在 SSE 断线/离线窗口内独立兜底（信号收不到时
     * 至多 5 分钟自愈）。
     */
    fun isListFetchFresh(): Boolean {
        val at = lastListFetchAtMs.get()
        if (at <= 0L) return false
        if (clockMs() - at >= STALE_AFTER_MS) return false
        val sampled = signalAtLastFetch
        val now = freshnessSignal.snapshot()
        return sampled.like == now.like && sampled.library == now.library
    }

    /** 当前指纹快照（首页 ON_RESUME 时取走并与上次留存对比） */
    fun fingerprint(): LikeFingerprint = LikeFingerprint(likeVersion.get(), lastMutatedAtMs.get())
}
