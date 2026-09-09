package media.qimeng.app.core.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.RecommendPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统计/偏好/系统信息三端口的生成 SDK 实现（M4-6）。
 * 出网请求打 logcat 的约定与 [SdkMediaRepository] 一致（验收证据协议 = 文本证据）。
 */
@Singleton
class SdkStatsRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : StatsRepository {

    override suspend fun overview(): StatsOverviewValues {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /stats/overview")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val overview = withContext(Dispatchers.IO) { api.apiV1StatsOverviewGet() }
        return StatsOverviewValues(
            totalFiles = overview.totalFiles ?: 0,
            imageCount = overview.imageCount ?: 0,
            videoCount = overview.videoCount ?: 0,
            totalSizeBytes = overview.totalSizeBytes ?: 0L,
            todayViews = overview.todayViews ?: 0,
            totalViews = overview.totalViews ?: 0L,
        )
    }

    override suspend fun trends(range: String): List<TrendPoint> = trends(range, null)

    override suspend fun trends(range: String, mediaType: String?): List<TrendPoint> {
        // range 只认 StatsRangeOption.apiRange 的产出（7d/day/all）；mediaType 只认协议三值
        // （image/video/animated_image），null=不过滤；此处打日志即验收证据
        Log.d(SdkMediaRepository.LOG_TAG, "GET /stats/trends range=$range mediaType=$mediaType")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val sdkRange = when (range) {
            "7d" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet._7d
            "day" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.day
            "90d" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet._90d
            "all" -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.all
            else -> media.qimeng.sdk.apis.DefaultApi.RangeApiV1StatsTrendsGet.month
        }
        val sdkMediaType = when (mediaType) {
            "image" -> media.qimeng.sdk.models.MediaType.image
            "video" -> media.qimeng.sdk.models.MediaType.video
            "animated_image" -> media.qimeng.sdk.models.MediaType.animated_image
            else -> null
        }
        val buckets = withContext(Dispatchers.IO) {
            api.apiV1StatsTrendsGet(range = sdkRange, mediaType = sdkMediaType)
        }
        return buckets.map { bucket ->
            TrendPoint(
                label = bucket.label.orEmpty(),
                viewCount = bucket.viewCount ?: 0,
                playCount = bucket.playCount ?: 0,
                seconds = bucket.seconds ?: 0,
            )
        }
    }
}

/** [RecommendPrefsRepository] 实现（GET/PUT 均走生成 SDK；协议批 2026-09-09 起
 *  prefs 九字段为 format:double 原生 Double，旧「okhttp 直发绕 BigDecimal」绕行
 *  已随根修撤除——#7 待拍板条目闭环） */
@Singleton
class SdkRecommendPrefsRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : RecommendPrefsRepository {

    override suspend fun prefs(): RecommendPrefsValues {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /recommendations/prefs")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val prefs = withContext(Dispatchers.IO) { api.apiV1RecommendationsPrefsGet() }
        return prefs.toValues()
    }

    override suspend fun putPrefs(values: RecommendPrefsValues) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /recommendations/prefs ${values.toLogString()}")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        withContext(Dispatchers.IO) {
            api.apiV1RecommendationsPrefsPut(
                recommendPrefs = RecommendPrefs(
                    tagRelevance = values.tagRelevance,
                    tagCollection = values.tagCollection,
                    engagement = values.engagement,
                    recency = values.recency,
                    likeScore = values.likeScore,
                    discovery = values.discovery,
                    freshness = values.freshness,
                    browseDepth = values.browseDepth,
                    maxRandom = values.maxRandom,
                ),
            )
        }
    }

    private fun RecommendPrefs.toValues(): RecommendPrefsValues = RecommendPrefsValues(
        tagRelevance = tagRelevance ?: 0.0,
        tagCollection = tagCollection ?: 0.0,
        engagement = engagement ?: 0.0,
        recency = recency ?: 0.0,
        likeScore = likeScore ?: 0.0,
        discovery = discovery ?: 0.0,
        freshness = freshness ?: 0.0,
        browseDepth = browseDepth ?: 0.0,
        maxRandom = maxRandom ?: 0.0,
    )

    private fun RecommendPrefsValues.toLogString(): String =
        "tagRel=$tagRelevance tagColl=$tagCollection engage=$engagement recency=$recency " +
            "like=$likeScore discov=$discovery fresh=$freshness depth=$browseDepth random=$maxRandom"
}

/** [SystemInfoRepository] SDK 实现（C6：version 字段单值端口） */
@Singleton
class SdkSystemInfoRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : SystemInfoRepository {

    override suspend fun serverVersion(): String? {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /system/status")
        val api = withContext(Dispatchers.IO) { apiFactory.create() }
        val status = withContext(Dispatchers.IO) { api.apiV1SystemStatusGet() }
        return status.version
    }
}

/** [DiskCachePrefsRepository] DataStore 实现（与搜索历史同一个 client_prefs 文件，不同键） */
@Singleton
class DataStoreDiskCachePrefsRepository @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : DiskCachePrefsRepository {

    override val quota: Flow<DiskCacheQuota> = dataStore.data.map { prefs ->
        DiskCacheQuota.fromMb(prefs[KEY_QUOTA_MB] ?: DEFAULT_MB_UNSET)
    }

    override suspend fun setQuota(quota: DiskCacheQuota) {
        dataStore.edit { prefs ->
            prefs[KEY_QUOTA_MB] = (quota.bytes / DiskCacheQuota.BYTES_PER_MB).toInt()
        }
    }

    private companion object {
        /** 未持久化标记值（null 走 fromMb 的默认回落，这里显式用 -1 与任何合法 MB 值区分） */
        const val DEFAULT_MB_UNSET = -1

        val KEY_QUOTA_MB = intPreferencesKey("disk_cache_quota_mb")
    }
}
