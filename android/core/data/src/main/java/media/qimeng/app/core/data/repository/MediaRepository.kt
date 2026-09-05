package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.HistoryPageResult
import media.qimeng.app.core.model.HistoryQuery
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod

/**
 * 列表族数据端口（M4-2）：资产/候选/推荐/排行榜/搜索建议。
 * 实现经生成 SDK 访问服务端（UI 禁直调 SDK/网络，ADR-0008 铁律 7）。
 * COS 流独立入口（拍板 A3）= [assets] 带 cosOnly=true，不走推荐算法。
 */
interface MediaRepository {

    /** 资产列表（cursor 分页）——相册/收藏/搜索/首页 COS 流共用 */
    suspend fun assets(query: AssetQuery): AssetPageResult

    /** 四维候选（排自身计数；四请求各缺自身参数由调用方状态机构造） */
    suspend fun facets(query: FacetsQuery): FacetsResult

    /** 推荐流（服务端已完成混合/打散/惩罚后处理；客户端一次拉满本地分批） */
    suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset>

    /** 排行榜（周期四档；协议缺省 week，客户端缺省必须显式传 day——拍板口径） */
    suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset>

    /** 搜索补全/推荐搜索（q 空 + recommend=true = 推荐搜索词） */
    suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion>

    /** 资产详情原件直链（动图网格动画数据源，拍板条目 9；仅 AssetDetail 携带 origUrl） */
    suspend fun assetOrigUrl(assetId: String): String?
}

/** 历史端口：每资产一条、lastViewedAt 倒序；无清空端点（拍板 2B 砍交互） */
interface HistoryRepository {
    suspend fun history(query: HistoryQuery): HistoryPageResult
}

/** 作者端口：全量数组（作者量有界，排序/搜索客户端做）+ 关注 toggle */
interface AuthorRepository {
    suspend fun authors(): List<AuthorSummary>

    suspend fun setFollowed(authorId: String, followed: Boolean)
}

/**
 * 动图原件直链解析端口（拍板条目 9：animated_image 网格项用原件签名直链走动画解码）。
 * 实现带内存缓存（同一资产只解析一次）；失败返回 null（回退服务端静态缩略图）。
 */
interface AssetOrigUrlResolver {
    suspend fun origUrl(assetId: String): String?
}

/** 搜索历史端口（客户端本地存储，≤20 条去重最新在前——旧版 §搜索页语义服务端化前段） */
interface SearchHistoryRepository {
    val history: kotlinx.coroutines.flow.Flow<List<String>>

    suspend fun record(query: String)

    suspend fun clear()
}

/** 网格列数端口（各页记忆 2~5 列、首页 1~2 列，重启保留——LEGACY §F 列数持久化） */
interface GridPrefsRepository {
    val homeColumns: kotlinx.coroutines.flow.Flow<Int>

    val albumColumns: kotlinx.coroutines.flow.Flow<Int>

    suspend fun setHomeColumns(columns: Int)

    suspend fun setAlbumColumns(columns: Int)
}
