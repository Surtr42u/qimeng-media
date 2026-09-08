package media.qimeng.app.core.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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
 * **detail 侧上报点：I7 批在详情页点赞成功处调用 [onLikeMutated]**（本批 I1 只交付
 * tracker 本体 + 首页响应端，全链实测随 I7 验收）。首页侧在返回/回前台（ON_RESUME）时
 * 对比指纹，变化则重拉当前 tab（推荐/排行榜各自重拉，重排效果由服务端打分决定，
 * 客户端不做语义假设）；「浏览退出保持原样」半边由「无 like 变更→指纹不变→不重拉」天然满足。
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

    /** 详情页点赞成功后调用（I7 批接线；重复调用即版本继续递增，语义安全） */
    fun onLikeMutated() {
        lastMutatedAtMs.set(System.currentTimeMillis())
        likeVersion.incrementAndGet()
    }

    /** 当前指纹快照（首页 ON_RESUME 时取走并与上次留存对比） */
    fun fingerprint(): LikeFingerprint = LikeFingerprint(likeVersion.get(), lastMutatedAtMs.get())
}
