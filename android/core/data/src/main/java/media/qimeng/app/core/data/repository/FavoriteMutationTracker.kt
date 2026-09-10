package media.qimeng.app.core.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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
 * 为什么是本地感知：SSE 无 favorite 事件（openapi.yaml /api/v1/events 事件清单只有
 * scan.progress / library.changed / thumbnail.progress / upload.done），与点赞
 * （[LikeMutationTracker]，任务I I1）同款协议约束——本地指纹是唯一路径。
 *
 * 形态沿用 [LikeMutationTracker] 先例（范式与其放置层级镜像，计数器本体的原子读写
 * 保持逐字同构）：纯进程内状态（无出网/无持久化），@Singleton 构造注入无需 @Binds；
 * volatile/原子保证跨线程读写可见。
 */
@Singleton
class FavoriteMutationTracker @Inject constructor() {

    /** favoriteVersion 计数器：每次收藏变更 +1（单调递增，进程内唯一） */
    private val favoriteVersion = AtomicLong(0L)

    /** 最近一次变更的时间戳（墙钟，仅随指纹携带供观测，不参与相等性以外语义） */
    private val lastMutatedAtMs = AtomicLong(0L)

    /** 详情页收藏/取消收藏成功后调用（失败不上报；重复调用即版本继续递增，语义安全） */
    fun onFavoriteMutated() {
        lastMutatedAtMs.set(System.currentTimeMillis())
        favoriteVersion.incrementAndGet()
    }

    /** 当前指纹快照（收藏页 ON_RESUME 时取走并与上次留存对比） */
    fun fingerprint(): FavoriteFingerprint = FavoriteFingerprint(favoriteVersion.get(), lastMutatedAtMs.get())
}
