package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import media.qimeng.app.core.model.PanelCountRange
import media.qimeng.app.core.model.PanelSizeRange
import media.qimeng.app.core.model.PanelTagMode
import media.qimeng.app.core.model.TagSummary
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.models.ApiV1AuthorsAuthorIdFollowPutRequest
import media.qimeng.sdk.models.ApiV1AuthorsImportTxtPostRequest
import media.qimeng.sdk.models.ApiV1TagsPostRequest
import media.qimeng.sdk.models.AssetAuthorsReplaceRequest
import media.qimeng.sdk.models.SourceVocabulary
import media.qimeng.sdk.models.TxtImportedFile
import media.qimeng.sdk.models.TxtImportResult
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 业务 API 工厂：按当前持久化地址构造生成 SDK 的 [DefaultApi]（阻塞实现，调用方自行挪 IO 线程）。
 * 地址唯一来源 = ServerConfigDataSource（ADR-0015 单机形态预留：全 App 只此一处定位点）。
 */
@Singleton
class BusinessApiFactory @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val okHttpClient: OkHttpClient,
) {
    fun create(): DefaultApi = createWith(okHttpClient)

    /**
     * 指定客户端构造 API（备份通道用 @BackupClient 长超时客户端；UploadClient 先例同源）。
     * 地址来源与 [create] 同一单点（ServerConfigDataSource），鉴权随客户端自带
     * （派生 client 经 newBuilder 共享 AuthInterceptor，备份请求不会丢 token）。
     */
    fun createWith(client: OkHttpClient): DefaultApi = DefaultApi(currentBaseUrl(), client)

    /** 当前服务端根地址（缩略图相对路径拼绝对直链用） */
    fun currentBaseUrl(): String = requireNotNull(serverConfig.currentServerUrl()) {
        "业务请求必须发生在登录之后（currentServerUrl 未就绪）"
    }
}

/**
 * [MediaRepository] 的生成 SDK 实现。所有调用挪 IO 线程（SDK 同步 execute 不许占主线程）。
 * 每个出网请求打一行 logcat（验收证据协议 = 文本证据，HANDOVER_APP §4.7）。
 */
@Singleton
class SdkMediaRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : MediaRepository {

    override suspend fun assets(query: AssetQuery): AssetPageResult {
        val api = apiFactory.create()
        logRequest("GET /assets", query.toLogString())
        val page = withContext(Dispatchers.IO) {
            api.apiV1AssetsGet(
                cursor = query.cursor,
                limit = query.limit,
                sort = query.sort.toSdk(),
                order = query.order.toSdk(),
                mediaType = query.mediaType?.toSdk(),
                // 协议批 2026-09-09（#29）：source/character/work 多值化（数组，
                // 同维 OR）；N4 消费批起多选集直传（单选=单元素列表向后兼容）。
                source = query.source?.takeIf { it.isNotEmpty() },
                character = query.character?.takeIf { it.isNotEmpty() },
                authorId = query.authorId,
                includeCos = query.includeCos,
                cosOnly = query.cosOnly,
                work = query.work?.takeIf { it.isNotEmpty() },
                favorite = query.favorite,
                q = query.q,
                // 万能筛选面板参数族（M4-2A-B3）：null=不传（默认档已在状态机归 null）
                tagIds = query.tagIds?.takeIf { it.isNotEmpty() },
                tagMode = query.tagMode?.toSdk(),
                viewRange = query.viewRange?.toSdk(),
                playRange = query.playRange?.toSdk(),
                sizeRange = query.sizeRange?.toSdk(),
                dateFrom = query.dateFrom,
                dateTo = query.dateTo,
                yearFrom = query.yearFrom,
                yearTo = query.yearTo,
            )
        }
        val baseUrl = apiFactory.currentBaseUrl()
        return AssetPageResult(
            items = page.items.orEmpty().map { SdkMappers.toMediaAsset(it, baseUrl) },
            nextCursor = page.nextCursor,
            totalMatched = page.totalMatched,
        )
    }

    override suspend fun allThumbUrls(): List<String> {
        // 复用 assets() 而非直调 SDK：thumbUrl 经 SdkMappers.absolutize 拼绝对直链，
        // 与首页网格完全同源的 URL 构造路径（签名相对路径禁改写，ADR-0002）。
        // includeCos 必须显式 true（reviewer P1 修正）：GET /assets 服务端缺省排除 COS
        //（openapi includeCos default=false，default=true 那处是 /history——Zone.kt
        // 「请求侧须显式传」口径）；不传则 COS 分区缩略图永远不会被预取。
        val urls = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = assets(
                AssetQuery(cursor = cursor, limit = ALL_THUMBS_PAGE_LIMIT, includeCos = true),
            )
            page.items.forEach { asset -> asset.thumbUrl?.let(urls::add) }
            cursor = page.nextCursor
        } while (cursor != null && ++pages < ALL_THUMBS_MAX_PAGES)
        // 服务端列表已去重，这里只滤翻页窗口内文件变动导致的跨页重复 URL（防同图重复预取；
        // 进度口径「本轮已处理/本轮总数」以 distinct 后列表为准，不做跨轮全局精确去重）
        return urls.distinct()
    }

    override suspend fun facets(query: FacetsQuery): FacetsResult {
        val api = apiFactory.create()
        logRequest("GET /assets/facets", query.toLogString())
        val facets = withContext(Dispatchers.IO) {
            api.apiV1AssetsFacetsGet(
                partition = query.partition.toSdk(),
                mediaType = query.mediaType?.toSdk(),
                source = query.source,
                character = query.character,
                authorId = query.authorId,
                work = query.work,
                favorite = query.favorite,
                history = query.history,
            )
        }
        return FacetsResult(
            partitions = facets.partitions.map(SdkMappers::toFacetOption),
            authors = facets.authors.map(SdkMappers::toFacetOption),
            characters = facets.characters.map(SdkMappers::toFacetOption),
            types = facets.types.map(SdkMappers::toFacetOption),
        )
    }

    override suspend fun recommendations(seed: Long, limit: Int, mediaType: MediaKind?): List<MediaAsset> {
        val api = apiFactory.create()
        logRequest("GET /recommendations", "seed=$seed limit=$limit${mediaType?.let { " mediaType=$it" } ?: ""}")
        val items = withContext(Dispatchers.IO) {
            api.apiV1RecommendationsGet(seed = seed, limit = limit, mediaType = mediaType?.toSdk())
        }
        val baseUrl = apiFactory.currentBaseUrl()
        return items.map { SdkMappers.toMediaAsset(it, baseUrl) }
    }

    override suspend fun rankings(period: RankingPeriod, limit: Int, offset: Int): List<MediaAsset> {
        val api = apiFactory.create()
        // 缺省日榜必须显式传 period=day（协议 default=week，拍板 B3）
        logRequest("GET /rankings", "period=${period.apiValue} limit=$limit offset=$offset")
        val items = withContext(Dispatchers.IO) {
            api.apiV1RankingsGet(period = period.toSdk(), limit = limit, offset = offset)
        }
        val baseUrl = apiFactory.currentBaseUrl()
        return items.map { SdkMappers.toMediaAsset(it, baseUrl) }
    }

    override suspend fun suggestions(q: String, limit: Int, recommend: Boolean): List<NameSuggestion> {
        val api = apiFactory.create()
        logRequest("GET /search/suggestions", "q=$q limit=$limit recommend=$recommend")
        val result = withContext(Dispatchers.IO) {
            api.apiV1SearchSuggestionsGet(q = q, limit = limit, recommend = recommend)
        }
        return result.items.map(SdkMappers::toNameSuggestion)
    }

    override suspend fun assetOrigUrl(assetId: String): String? {
        val api = apiFactory.create()
        logRequest("GET /assets/$assetId", "resolve origUrl")
        val detail = withContext(Dispatchers.IO) {
            // 协议路径参数为 UUID；列表 DTO 给出的 id 字符串在此回解析（非法 id 走 catch 兜底）
            api.apiV1AssetsAssetIdGet(java.util.UUID.fromString(assetId))
        }
        // origUrl 与 thumbUrl 同为签名相对路径，统一拼 base 成绝对直链（签名禁改写）
        return detail.origUrl?.let { apiFactory.currentBaseUrl().trimEnd('/') + it }
    }

    override suspend fun tags(): List<TagSummary> {
        val api = apiFactory.create()
        logRequest("GET /tags", "")
        val tags = withContext(Dispatchers.IO) { api.apiV1TagsGet() }
        return tags.map(SdkMappers::toTagSummary)
    }

    override suspend fun createTag(name: String): TagSummary {
        val api = apiFactory.create()
        logRequest("POST /tags", "name=$name")
        val tag = try {
            withContext(Dispatchers.IO) {
                api.apiV1TagsPost(ApiV1TagsPostRequest(name = name))
            }
        } catch (e: ClientException) {
            // 服务端对重名标签返回 409——领域化上抛，调用方给「已存在」专门文案而非笼统失败
            // （修复轮 P2-1：候选预查重之外的兜底通道）
            if (e.statusCode == HTTP_CONFLICT) throw TagNameConflictException(name)
            throw e
        }
        return SdkMappers.toTagSummary(tag)
    }

    override suspend fun deleteTag(tagId: String) {
        val api = apiFactory.create()
        logRequest("DELETE /tags/$tagId", "")
        withContext(Dispatchers.IO) { api.apiV1TagsTagIdDelete(tagId = tagId) }
    }

    private fun logRequest(endpoint: String, params: String) {
        Log.d(LOG_TAG, "$endpoint $params")
    }

    companion object {
        /** logcat 证据标签（grep 'QimengApi' 即得全部出网请求清单） */
        const val LOG_TAG = "QimengApi"

        /** HTTP 409：POST /tags 重名（服务端唯一语义化 4xx，领域化为 [TagNameConflictException]） */
        private const val HTTP_CONFLICT = 409

        /** 全库缩略图分页每页条数：openapi GET /assets limit 上限即 200（单页最大减少翻页往返）。 */
        private const val ALL_THUMBS_PAGE_LIMIT = 200

        /**
         * 全库缩略图分页安全阀（页数上限）：正常按 nextCursor 翻完即止，此值只防
         * 异常服务端回环返回相同 cursor 导致调用方永久挂起——200 页 × 200 条 = 4 万
         * 资产封顶，超限按已收到的部分继续（宁少不挂）。
         */
        private const val ALL_THUMBS_MAX_PAGES = 200
    }
}

/** [HistoryRepository] SDK 实现（GET /history cursor 分页；请求日志同证据协议） */
@Singleton
class SdkHistoryRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : HistoryRepository {

    override suspend fun history(query: HistoryQuery): HistoryPageResult {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /history ${query.toLogString()}")
        val page = withContext(Dispatchers.IO) {
            apiFactory.create().apiV1HistoryGet(
                cursor = query.cursor,
                limit = query.limit,
                includeCos = query.includeCos,
                cosOnly = query.cosOnly,
                mediaType = query.mediaType?.toSdk(),
                // 协议批 2026-09-09（#29/#30）：work/character/source 数组化（同维 OR），
                // N4 消费批起历史页「作品」维行（source 出处分组多选）直传。
                source = query.source?.takeIf { it.isNotEmpty() },
                authorId = query.authorId,
                work = query.work?.takeIf { it.isNotEmpty() },
                character = query.character?.takeIf { it.isNotEmpty() },
            )
        }
        val baseUrl = apiFactory.currentBaseUrl()
        return HistoryPageResult(
            items = page.items.orEmpty().map { SdkMappers.toHistoryEntry(it, baseUrl) },
            nextCursor = page.nextCursor,
        )
    }
}

/** [AuthorRepository] SDK 实现（GET /authors 全量 + 关注 toggle） */
@Singleton
class SdkAuthorRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : AuthorRepository {

    override suspend fun authors(): List<AuthorSummary> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /authors")
        val authors = withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsGet()
        }
        return authors.map(SdkMappers::toAuthorSummary)
    }

    override suspend fun setFollowed(authorId: String, followed: Boolean) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /authors/$authorId/follow followed=$followed")
        withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsAuthorIdFollowPut(
                authorId = authorId,
                apiV1AuthorsAuthorIdFollowPutRequest = ApiV1AuthorsAuthorIdFollowPutRequest(follow = followed),
            )
        }
    }

    // ── TXT 导入族（U10-6b，Web 文件管理页 TxtAuthorImportCard 对等物）──

    override suspend fun importedTxtFiles(): List<TxtImportedFile> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /authors/import-txt")
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsImportTxtGet()
        }
    }

    override suspend fun importTxt(filename: String, content: String): TxtImportResult {
        // 只记片段名与字符量，不打全文（内容可达 MB 级，logcat 单行溢出无意义）
        Log.d(SdkMediaRepository.LOG_TAG, "POST /authors/import-txt filename=$filename chars=${content.length}")
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsImportTxtPost(
                ApiV1AuthorsImportTxtPostRequest(filename = filename, content = content),
            )
        }
    }

    override suspend fun removeImportedTxt(filename: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "DELETE /authors/import-txt filename=$filename")
        withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsImportTxtDelete(filename = filename)
        }
    }

    override suspend fun rebuildTxt(): TxtImportResult {
        Log.d(SdkMediaRepository.LOG_TAG, "POST /authors/import-txt/rebuild")
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsImportTxtRebuildPost()
        }
    }

    // ── 资产编辑页族（2026-09-25：上传挂靠退役批——作者关联与来源维护收口本端口）──

    override suspend fun sourceVocabulary(): List<String> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /authors/source-vocabulary")
        return withContext(Dispatchers.IO) { apiFactory.create().apiV1AuthorsSourceVocabularyGet().sources }
    }

    override suspend fun authorSourcesById(authorId: String): List<String> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /authors/$authorId/sources")
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsAuthorIdSourcesGet(authorId).sources
        }
    }

    override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /authors/$authorId/sources count=${sources.size}")
        withContext(Dispatchers.IO) {
            apiFactory.create().apiV1AuthorsAuthorIdSourcesPut(
                authorId = authorId,
                sourceVocabulary = SourceVocabulary(sources = sources),
            )
        }
    }

    override suspend fun replaceAssetAuthors(assetId: String, authorIds: List<String>) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /assets/$assetId/authors count=${authorIds.size}")
        withContext(Dispatchers.IO) {
            // 协议路径参数为 UUID（GET /assets/{id} 同口径：列表 DTO 的 id 字符串在此回解析）
            apiFactory.create().apiV1AssetsAssetIdAuthorsPut(
                assetId = java.util.UUID.fromString(assetId),
                assetAuthorsReplaceRequest = AssetAuthorsReplaceRequest(authorIds = authorIds),
            )
        }
    }
}

private fun AssetQuery.toLogString(): String = buildString {
    cursor?.let { append("cursor=$it ") } ?: append("cursor=null ")
    limit?.let { append("limit=$it ") }
    includeCos?.let { append("includeCos=$it ") }
    cosOnly?.let { append("cosOnly=$it ") }
    mediaType?.let { append("mediaType=$it ") }
    source?.let { append("source=$it ") }
    authorId?.let { append("authorId=$it ") }
    character?.let { append("character=$it ") }
    work?.let { append("work=$it ") }
    favorite?.let { append("favorite=$it ") }
    q?.let { append("q=$it ") }
    append("sort=${sort.name} order=${order.name}")
    // 万能筛选面板参数（M4-2A-B3）：只记非缺省项，null=未传
    viewRange?.let { append(" viewRange=$it") }
    playRange?.let { append(" playRange=$it") }
    sizeRange?.let { append(" sizeRange=$it") }
    dateFrom?.let { append(" dateFrom=$it") }
    dateTo?.let { append(" dateTo=$it") }
    yearFrom?.let { append(" yearFrom=$it") }
    yearTo?.let { append(" yearTo=$it") }
    tagIds?.let { append(" tagIds=$it") }
    tagMode?.let { append(" tagMode=$it") }
}

private fun FacetsQuery.toLogString(): String = buildString {
    append("partition=${partition.name} ")
    mediaType?.let { append("mediaType=$it ") }
    source?.let { append("source=$it ") }
    authorId?.let { append("authorId=$it ") }
    character?.let { append("character=$it ") }
    work?.let { append("work=$it ") }
    favorite?.let { append("favorite=$it ") }
    history?.let { append("history=$it ") }
}

private fun HistoryQuery.toLogString(): String = buildString {
    includeCos?.let { append("includeCos=$it ") }
    cosOnly?.let { append("cosOnly=$it ") }
    mediaType?.let { append("mediaType=$it ") }
    work?.let { append("work=$it ") }
    character?.let { append("character=$it ") }
    append("cursor=${cursor != null}")
}
