package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip

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
}
