package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind

/**
 * 详情页数据端口（M4-3）：详情读取 + 互动（点赞/收藏/关注走 [AuthorRepository]）+ 标签管理
 * + 「接下来播放」推荐流。实现经生成 SDK 访问服务端（UI 禁直调 SDK/网络，ADR-0008 铁律 7）。
 */
interface DetailRepository {

    /** 资产详情（GET /assets/{id}；tags 服务端按关联时间倒序——「最近添加置顶」，LEGACY §A） */
    suspend fun assetDetail(assetId: String): AssetDetail

    /** 点赞 toggle（PUT /assets/{id}/like → LikeState；语义=每资产每日一次，服务端判定） */
    suspend fun toggleLike(assetId: String): LikeToggleResult

    /** 收藏显式设置（PUT /assets/{id}/favorite，204 无响应体；成功与否看是否抛异常） */
    suspend fun setFavorite(assetId: String, favorite: Boolean)

    /** 全量标签池（GET /tags；服务端名字序——详情弹窗「其他标签」名字序降级，LEGACY §A 差异已拍板） */
    suspend fun allTags(): List<TagChip>

    /** 新建标签（POST /tags；重名由服务端报错，调用方透传错误文案） */
    suspend fun createTag(name: String): TagChip

    /** 标签整体替换（PUT /assets/{id}/tags；替换式绑定是唯一标签端点——DOMAIN_RULES §7） */
    suspend fun replaceAssetTags(assetId: String, tagIds: List<String>)

    /**
     * 「接下来播放」推荐流（GET /recommendations offset=0 + cosOnly 收窄；Web useUpNextList 同参数）。
     * 为什么不在 [MediaRepository.recommendations] 加 cosOnly 参数：该接口被列表族四个 VM 的
     * 测试 fake 实现，改签名会撞 M4-2A 并行边界——详情侧独立端口零侵入。
     * 当前资产由调用方（ViewModel）过滤，仓库层保持「纯取数」。
     */
    suspend fun upNext(seed: Long, limit: Int, mediaType: MediaKind?, cosOnly: Boolean): List<MediaAsset>

    // ------------------------------------------------------------------
    // M4-3 3d 新增（播放进度 / 行为打点 / 时间轴标签）。
    // 接线批（3d 集成）已收拢为抽象方法：唯一生产实现 SdkDetailRepository 全量
    // override，测试 fake（DetailViewModelTest.FakeDetailRepository）同步实现——
    // 并行期的「UnsupportedOperationException 默认体」妥协债随本批清偿。
    // ------------------------------------------------------------------

    /**
     * 播放进度上报（PUT /assets/{id}/progress，204；服务端只存最新值）。
     * 调用方必须经进度节流策略放行后才调（本项目冻结 5s 心跳，严于协议建议 10s；
     * 暂停/离开立即补报），禁止逐帧直发。
     */
    suspend fun reportProgress(assetId: String, positionSeconds: Double)

    /**
     * 行为打点直连上报（POST /events/view；open/play/dwell，DOMAIN_RULES §5 ViewEvent 事件流）。
     * TODO(M4-4): 改走离线队列（弱网/退后台不丢打点），此处直连为 M4-3 过渡实现。
     *
     * @param startedAtMs 事件开始时刻（客户端时钟 epochMilli；服务端转 OffsetDateTime 存储）
     * @param sessionId 客户端会话标识（客户端会话级 UUID；服务端按 assetId+kind+sessionId+当日去重）
     * @param dwellSeconds 仅 dwell 携带：本次停留秒数（长整型截断）
     */
    suspend fun reportViewEvent(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        sessionId: String,
        dwellSeconds: Long? = null,
    )

    /** 时间轴标签列表（GET /assets/{id}/timeline-tags；按 timeMillis 升序由服务端返回） */
    suspend fun timelineTags(assetId: String): List<TimelineTag>

    /** 新建时间轴标签（POST /assets/{id}/timeline-tags → TimelineTag，id 服务端生成） */
    suspend fun addTimelineTag(assetId: String, timeMillis: Long, name: String): TimelineTag

    /** 删除时间轴标签（DELETE /assets/{id}/timeline-tags/{tagId}，204） */
    suspend fun deleteTimelineTag(assetId: String, tagId: String)
}
