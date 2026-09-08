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
     * 行为打点入队（POST /events/view 的离线队列写入口，DOMAIN_RULES §5 ViewEvent 事件流）。
     * M4-4 落地：本方法只把事件写进 Room 离线队列（写成功即返回，弱网/退后台不丢），
     * 出网补传由队列三通道异步完成（写入即触发 / 回前台 ON_START / 周期兜底）；
     * 写失败抛异常，静默口径收敛为「写队列失败才静默」（调用方 DetailViewModel）。
     *
     * @param startedAtMs 事件开始时刻（客户端时钟 epochMilli；出网时转 UTC OffsetDateTime 存储）
     * @param sessionId 客户端会话标识（每详情停留会话一个 UUID；服务端按 assetId+kind+sessionId+当日去重）
     * @param dwellSeconds 仅 dwell 携带：本次停留秒数（长整型截断；open/play 传 null）
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

    // ------------------------------------------------------------------
    // 文件操作（任务G G1b：详情页整理/删除，基准 = Web FileOpsButton/FileOpsDialogs）
    // ------------------------------------------------------------------

    /**
     * 整理文件（POST /assets/{id}/move：一个端点两用——改名 = 原目录 + 新名；移动 = 新目录 + 原名）。
     * 目标位置已有同名文件时服务端拒绝（不覆盖，409 → [MoveConflictException] 领域化）。
     *
     * @param targetDir 库内目标目录（相对路径，空串 = 库根）
     * @param newName 可选新文件名（null = 保持原名；含扩展名）
     */
    suspend fun moveAsset(assetId: String, targetDir: String, newName: String?)

    /**
     * 移入回收站（DELETE /assets/{id}；铁律 4：DELETE 语义 = 回收站，物理删除是独立管理操作，
     * 恢复走维护页回收站——调用方文案必须明示该语义）。
     */
    suspend fun deleteAsset(assetId: String)
}

/**
 * 整理目标位置同名冲突（服务端 POST /move 返回 409）的领域化异常（TagNameConflictException
 * 同范式）：生成 SDK 的 ClientException 不带响应体文案，仓库层翻译成固定中文领域文案，
 * 调用方据此给「目标位置已有同名文件」专门反馈而非笼统失败。
 */
class MoveConflictException : Exception("move target conflict")
