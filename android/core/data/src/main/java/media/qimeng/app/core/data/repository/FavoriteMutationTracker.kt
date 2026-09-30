package media.qimeng.app.core.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.data.events.DataFreshnessSignal
import media.qimeng.app.core.data.events.FreshnessSnapshot

/** 收藏变更指纹快照（favoriteVersion 计数器 + 最近变更时间戳；相等即「无新变更」） */
data class FavoriteFingerprint(
    val favoriteVersion: Long,
    val lastMutatedAtMs: Long,
)

/**
 * 本地收藏变更指纹（任务V V1，2026-09-10 返回刷新缺陷修复）。
 *
 * 为什么存在：收藏页此前 ON_RESUME 无条件 refresh()，共享元素转场合入后缺陷显性化——
 * 详情返回 morph（缩略图飞回）进行中列表整体重显（items 整组替换 + PullToRefresh 指示器
 * 闪一轮），用户 2026-09-10 拍板「返回时不要刷新界面…应该是原来的不变」。改指纹门控：
 * 仅详情页收藏/取消收藏成功（[onFavoriteMutated] 上报点在 DetailViewModel.toggleFavorite）
 * 后的返回才重拉，纯浏览返回零网络零重组。
 *
 * 三层新鲜度（2026-10-01 ADR-0029 门收敛修订，语义见 [STALE_AFTER_MS] 与
 * [LikeMutationTracker] 类 KDoc）：本端指纹（[fingerprint]，消费点 VM 对比）+ 服务端
 * SSE 信号（[DataFreshnessSignal]，跨端变更秒级感知）+ TTL（[STALE_AFTER_MS]，SSE
 * 断线/离线窗口的兜底上界）。本类承担后两半边的判定（[isListFetchFresh]）；历史记录：
 * SSE 曾无 favorite 事件（只有本地指纹 + TTL 两层），ADR-0029 服务端补发后收敛为三层。
 *
 * 门收敛关联（哪个门关联哪几个信号计数，按数据来源定）：收藏列表的数据面 = 收藏状态
 * + 资产集合（资产被删/恢复会改变收藏列表内容）——故 [isListFetchFresh] 关联
 * favorite.changed + library.changed 两计数（like 变更与收藏列表无数据关联，不触发）。
 * 与 [LikeMutationTracker] 镜像同构（其关联 like.changed + library.changed）。
 *
 * 形态沿用 [LikeMutationTracker] 先例（范式与其放置层级镜像，计数器本体的原子读写
 * 保持逐字同构）：纯进程内状态（无出网/无持久化），@Singleton 构造注入无需 @Binds；
 * volatile/原子保证跨线程读写可见。
 */
@Singleton
class FavoriteMutationTracker @Inject constructor(
    private val freshnessSignal: DataFreshnessSignal,
) {

    /** favoriteVersion 计数器：每次收藏变更 +1（单调递增，进程内唯一） */
    private val favoriteVersion = AtomicLong(0L)

    /** 最近一次变更的时间戳（墙钟，仅随指纹携带供观测，不参与相等性以外语义） */
    private val lastMutatedAtMs = AtomicLong(0L)

    /** 上次成功列表拉取完成时刻（墙钟 ms；0=本进程内尚未成功拉取过，视为陈旧） */
    private val lastListFetchAtMs = AtomicLong(0L)

    /** 上次成功列表拉取完成时的信号快照（[isListFetchFresh] 信号半边的对比基线；零值=尚未采样） */
    @Volatile
    private var signalAtLastFetch: FreshnessSnapshot = FreshnessSnapshot.ZERO

    /**
     * 时钟源（TTL 打点与判定用；public 可变=单测注入假钟入口，生产恒默认墙钟）。
     * 取舍详见 [LikeMutationTracker.clockMs] 同名 KDoc（两侧镜像同构防漂移，不重复展开）。
     */
    var clockMs: () -> Long = System::currentTimeMillis

    /** 详情页收藏/取消收藏成功后调用（失败不上报；重复调用即版本继续递增，语义安全） */
    fun onFavoriteMutated() {
        lastMutatedAtMs.set(clockMs())
        favoriteVersion.incrementAndGet()
    }

    /** 收藏页列表拉取成功落地后调用（含翻页追加；TTL 计时起点 + 信号基线采样，失败不打点） */
    fun noteListFetchCompleted() {
        lastListFetchAtMs.set(clockMs())
        signalAtLastFetch = freshnessSignal.snapshot()
    }

    /**
     * 跳过门的两半边判定（「指纹未变」第三边留在消费点 VM 对比 [fingerprint]）：
     * ① TTL 半边——距上次成功列表拉取仍在 [STALE_AFTER_MS] 内（严格小于，恰等于即陈旧），
     * 0 哨兵=从未成功拉取恒不新鲜；② 信号半边——[noteListFetchCompleted] 采样后的
     * favorite.changed / library.changed 计数任一增长即不新鲜（跨端收藏/资产集合变更，
     * 秒级感知；幂等 no-op 事件多 bump 无害只是多拉一次）。TTL 在 SSE 断线/离线窗口内
     * 独立兜底（信号收不到时至多 5 分钟自愈）。完整语义详见
     * [LikeMutationTracker.isListFetchFresh] 同名 KDoc（两侧镜像同构防漂移，不重复展开）。
     */
    fun isListFetchFresh(): Boolean {
        val at = lastListFetchAtMs.get()
        if (at <= 0L) return false
        if (clockMs() - at >= STALE_AFTER_MS) return false
        val sampled = signalAtLastFetch
        val now = freshnessSignal.snapshot()
        return sampled.favorite == now.favorite && sampled.library == now.library
    }

    /** 当前指纹快照（收藏页 ON_RESUME 时取走并与上次留存对比） */
    fun fingerprint(): FavoriteFingerprint = FavoriteFingerprint(favoriteVersion.get(), lastMutatedAtMs.get())
}
