package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.ApiV1AssetsAssetIdFavoritePutRequest
import media.qimeng.sdk.models.ApiV1AssetsAssetIdTagsPutRequest
import media.qimeng.sdk.models.ApiV1TagsPostRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DetailRepository] 的生成 SDK 实现（照 SdkMediaRepository 范式）：
 * BusinessApiFactory 按当前地址构造、全部调用挪 IO 线程（SDK 同步 execute）、
 * 每个出网请求打一行 logcat（验收证据协议 = 文本证据，HANDOVER_APP §4.7）。
 */
@Singleton
class SdkDetailRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : DetailRepository {

    override suspend fun assetDetail(assetId: String): AssetDetail {
        val api = apiFactory.create()
        logRequest("GET /assets/$assetId", "detail")
        val detail = withContext(Dispatchers.IO) {
            // 协议路径参数为 UUID；列表 DTO 给出的 id 字符串在此回解析（非法 id 走调用方 catch 兜底）
            api.apiV1AssetsAssetIdGet(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toAssetDetail(detail, apiFactory.currentBaseUrl())
    }

    override suspend fun toggleLike(assetId: String): LikeToggleResult {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/like", "toggle")
        val state = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdLikePut(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toLikeToggleResult(state)
    }

    override suspend fun setFavorite(assetId: String, favorite: Boolean) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/favorite", "favorite=$favorite")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdFavoritePut(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdFavoritePutRequest = ApiV1AssetsAssetIdFavoritePutRequest(favorite = favorite),
            )
        }
    }

    override suspend fun allTags(): List<TagChip> {
        val api = apiFactory.create()
        logRequest("GET /tags", "pool")
        val tags = withContext(Dispatchers.IO) {
            api.apiV1TagsGet()
        }
        return tags.map(SdkDetailMappers::toTagChip)
    }

    override suspend fun createTag(name: String): TagChip {
        val api = apiFactory.create()
        logRequest("POST /tags", "name=$name")
        val tag = withContext(Dispatchers.IO) {
            api.apiV1TagsPost(ApiV1TagsPostRequest(name = name))
        }
        return SdkDetailMappers.toTagChip(tag)
    }

    override suspend fun replaceAssetTags(assetId: String, tagIds: List<String>) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/tags", "tagIds=${tagIds.size}")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTagsPut(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdTagsPutRequest = ApiV1AssetsAssetIdTagsPutRequest(tagIds = tagIds),
            )
        }
    }

    override suspend fun upNext(seed: Long, limit: Int, mediaType: MediaKind?, cosOnly: Boolean): List<MediaAsset> {
        val api = apiFactory.create()
        logRequest("GET /recommendations", "upnext seed=$seed limit=$limit mediaType=$mediaType cosOnly=$cosOnly offset=0")
        val items = withContext(Dispatchers.IO) {
            // offset 恒 0：推荐栏一次取一页，翻页=换 seed（Web useUpNextList 同参数语义）
            api.apiV1RecommendationsGet(
                seed = seed,
                limit = limit,
                offset = UP_NEXT_OFFSET,
                mediaType = mediaType?.toSdk(),
                cosOnly = cosOnly,
            )
        }
        // 列表 DTO 映射复用 SdkMappers（同模块 internal 可见，零改动零复制）
        val baseUrl = apiFactory.currentBaseUrl()
        return items.map { SdkMappers.toMediaAsset(it, baseUrl) }
    }

    override suspend fun reportProgress(assetId: String, positionSeconds: Double) {
        val api = apiFactory.create()
        logRequest("PUT /assets/$assetId/progress", "position=$positionSeconds")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdProgressPut(
                assetId = UUID.fromString(assetId),
                progressUpdate = SdkDetailMappers.toProgressUpdate(positionSeconds),
            )
        }
    }

    /** 行为打点直连上报（每事件一次出网）。TODO(M4-4): 改走离线队列（批量+重试） */
    override suspend fun reportViewEvent(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        sessionId: String,
        dwellSeconds: Long?,
    ) {
        val api = apiFactory.create()
        logRequest("POST /events/view", "kind=$kind seconds=$dwellSeconds session=$sessionId")
        withContext(Dispatchers.IO) {
            api.apiV1EventsViewPost(
                SdkDetailMappers.toViewEventReport(assetId, kind, startedAtMs, sessionId, dwellSeconds),
            )
        }
    }

    override suspend fun timelineTags(assetId: String): List<TimelineTag> {
        val api = apiFactory.create()
        logRequest("GET /assets/$assetId/timeline-tags", "list")
        val tags = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsGet(UUID.fromString(assetId))
        }
        return SdkDetailMappers.toTimelineTags(tags)
    }

    override suspend fun addTimelineTag(assetId: String, timeMillis: Long, name: String): TimelineTag {
        val api = apiFactory.create()
        logRequest("POST /assets/$assetId/timeline-tags", "timeMillis=$timeMillis")
        val tag = withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsPost(
                assetId = UUID.fromString(assetId),
                apiV1AssetsAssetIdTimelineTagsPostRequest =
                    SdkDetailMappers.toAddTimelineTagRequest(timeMillis, name),
            )
        }
        return SdkDetailMappers.toTimelineTag(tag)
    }

    override suspend fun deleteTimelineTag(assetId: String, tagId: String) {
        val api = apiFactory.create()
        logRequest("DELETE /assets/$assetId/timeline-tags/$tagId", "delete")
        withContext(Dispatchers.IO) {
            api.apiV1AssetsAssetIdTimelineTagsTagIdDelete(
                assetId = UUID.fromString(assetId),
                tagId = tagId,
            )
        }
    }

    private fun logRequest(endpoint: String, params: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "$endpoint $params")
    }

    companion object {
        /** 推荐栏取数偏移恒 0（换一批=换 seed 重取，同 seed 可复现——DOMAIN_RULES §1.1 禁纯随机） */
        private const val UP_NEXT_OFFSET = 0
    }
}
