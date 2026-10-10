package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetPageResult
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.AuthorMirrorConfig
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.FacetsResult
import media.qimeng.app.core.model.HistoryPageResult
import media.qimeng.app.core.model.HistoryQuery
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.model.TxtImportSummary

/**
 * 列表族数据端口（M4-2）：资产/候选/推荐/排行榜/搜索建议。
 * 实现经生成 SDK 访问服务端（UI 禁直调 SDK/网络，ADR-0008 铁律 7）。
 * COS 流独立入口（拍板 A3）= [assets] 带 cosOnly=true，不走推荐算法。
 */
interface MediaRepository {

    /** 资产列表（cursor 分页）——相册/收藏/搜索/首页 COS 流共用 */
    suspend fun assets(query: AssetQuery): AssetPageResult

    /**
     * 全库缩略图直链（缩略图预取器专用，2026-09-18）：按 /assets cursor 分页
     * 翻完全量（实现必须显式传 includeCos=true——/assets 服务端缺省排除 COS，
     * openapi 口径；Zone.kt「请求侧须显式传」同源），
     * 返回全部非空 md 缩略图绝对 URL（与首页网格同源构造路径）。
     * 带默认实现的原因同本文件 tags 族注释：feature 各页测试替身只实现抽象方法，
     * 新增抽象方法会破坏其编译；默认值 =「无预取数据」，唯一生产实现 SdkMediaRepository 全覆盖。
     */
    suspend fun allThumbUrls(): List<String> = emptyList()

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

    /**
     * 标签族（M4-2A-B3 万能筛选面板「标签」流）。带默认实现的原因：
     * M4-2A 并行期 feature/home 的测试替身禁碰（任务B 文件集硬边界），新增抽象方法会破坏
     * 既有替身编译；默认值=「无标签能力」，仅测试替身继承（唯一生产实现 SdkMediaRepository 全覆盖）。
     */

    /** 标签全量数组（筛选面板候选流；协议无分页） */
    suspend fun tags(): List<TagSummary> = emptyList()

    /** 新建标签（POST /tags；返回新建结果含服务端生成 id） */
    suspend fun createTag(name: String): TagSummary = throw UnsupportedOperationException("createTag 未实现")

    /** 删除标签（DELETE /tags/{id}；服务端级联解除文件关联——DOMAIN_RULES 口径） */
    suspend fun deleteTag(tagId: String) = Unit
}

/**
 * 标签重名（服务端 POST /tags 返回 409）的领域化异常（修复轮 P2-1）：
 * SdkMediaRepository 捕获生成 SDK 的 ClientException(statusCode=409) 后转抛本类型，
 * 调用方据此给「标签已存在」专门文案，避免把可预期的重名输入当笼统失败上报。
 */
class TagNameConflictException(name: String) : Exception("tag name conflict: $name")

/** 历史端口：每资产一条、lastViewedAt 倒序；无清空端点（拍板 2B 砍交互） */
interface HistoryRepository {
    suspend fun history(query: HistoryQuery): HistoryPageResult
}

/**
 * 作者端口：全量数组（作者量有界，排序/搜索客户端做）+ 关注 toggle。
 * U10-6b 追加 TXT 导入族（旧版数据管理「TXT导入作者」，Web 文件管理页
 * TxtAuthorImportCard 对等物；DOMAIN_RULES §6 三格式自动识别 + 统一重建）。
 * 2026-10-03 feature:manage 撤 :sdk 直依赖批：TXT 族签名收口域类型（列表直出文件名、
 * 导入/重放出 [TxtImportSummary] 消费投影），SDK 传输模型的映射下沉 SdkAuthorRepository；
 * 四方法带默认实现的原因同本文件 tags 族注释：并行批 feature:detail 的测试替身
 * FakeAuthorRepository 只实现前两方法，抽象化会破坏其编译（任务外文件禁碰）；
 * 默认值=「无 TXT 导入能力」，唯一生产实现 SdkAuthorRepository 全覆盖。
 */
interface AuthorRepository {
    suspend fun authors(): List<AuthorSummary>

    suspend fun setFollowed(authorId: String, followed: Boolean)

    /** 已导入 TXT 片段文件名列表（GET /authors/import-txt；filename 升序，空串=匿名导入） */
    suspend fun importedTxtFileNames(): List<String> = emptyList()

    /** 导入作者 TXT（POST /authors/import-txt；同名片段覆盖 + 从全部片段统一重建） */
    suspend fun importTxt(filename: String, content: String): TxtImportSummary =
        throw UnsupportedOperationException("importTxt 未实现")

    /** 移除一个片段并从剩余片段重建（DELETE /authors/import-txt；404=片段不存在） */
    suspend fun removeImportedTxt(filename: String) = Unit

    /** 幂等重放已存片段重建作者-文件关联（POST /authors/import-txt/rebuild；无片段返回零值） */
    suspend fun rebuildTxt(): TxtImportSummary = throw UnsupportedOperationException("rebuildTxt 未实现")

    // ------------------------------------------------------------------
    // 资产编辑页族（2026-09-25：上传挂靠退役批——作者关联与来源维护收口本端口）。
    // 带默认实现的原因同本文件 tags 族注释：feature:detail 既有测试替身
    // （DetailViewModelTest.FakeAuthorRepository）只实现前两方法，抽象化会破坏其编译；
    // 默认值=「无编辑能力」，唯一生产实现 SdkAuthorRepository 全覆盖。
    // ------------------------------------------------------------------

    /** 全站来源词表（GET /authors/source-vocabulary；服务端单一来源，客户端禁硬编码词表） */
    suspend fun sourceVocabulary(): List<String> = emptyList()

    /** 某作者来源集（GET /authors/{authorId}/sources；服务端已排序，客户端不重排） */
    suspend fun authorSourcesById(authorId: String): List<String> = emptyList()

    /** 整体替换某作者来源集（PUT /authors/{authorId}/sources，body {sources, mode=replace}） */
    suspend fun replaceAuthorSources(authorId: String, sources: List<String>) = Unit

    /**
     * 并入来源（PUT /authors/{authorId}/sources，body {sources, mode=append}；挂靠批）：
     * append = 服务端并入去重、永不覆盖该作者既有来源（上传自动挂靠专用语义，
     * ADR-0023 原上传来源口径）；与 [replaceAuthorSources]（编辑页整体替换）分立方法，
     * 调用方语义一目了然、不靠 mode 参数自辨。
     */
    suspend fun appendAuthorSources(authorId: String, sources: List<String>) = Unit

    /**
     * 整体替换资产作者关联（PUT /assets/{assetId}/authors，body {authorIds} 全集）；
     * 200 回 AssetDetail（关联即落库，调用方按需重查详情）。
     */
    suspend fun replaceAssetAuthors(assetId: String, authorIds: List<String>) = Unit

    /** 作者总表镜像配置（GET /authors/mirror） */
    suspend fun authorMirror(): AuthorMirrorConfig = AuthorMirrorConfig()

    /** 保存作者总表镜像配置（PUT /authors/mirror） */
    suspend fun saveAuthorMirror(config: AuthorMirrorConfig): AuthorMirrorConfig = config
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

/**
 * 外观偏好端口（2026-10-03 悬浮玻璃坞批）：外观模式（跟随系统/浅色/深色）与底栏材质
 * （液态玻璃/磨砂玻璃/纯色坞/经典）两项手动开关的持久化，重启保留。
 * 枚举单源在 :core:model（[AppearanceMode]/[TabBarMaterial]）；壳层 Theme 解析与玻璃坞
 * 渲染、设置页选择器都从本端口读——DataStore 流是唯一事实源。
 */
interface AppearancePrefsRepository {
    val appearanceMode: kotlinx.coroutines.flow.Flow<AppearanceMode>

    val tabBarMaterial: kotlinx.coroutines.flow.Flow<TabBarMaterial>

    suspend fun setAppearanceMode(mode: AppearanceMode)

    suspend fun setTabBarMaterial(material: TabBarMaterial)
}
