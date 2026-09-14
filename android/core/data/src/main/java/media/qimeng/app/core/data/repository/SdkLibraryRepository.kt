package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.LibraryKind
import media.qimeng.app.core.model.LibraryScanState
import media.qimeng.app.core.model.LibrarySummary
import media.qimeng.sdk.models.ApiV1LibrariesLibraryIdEnabledPutRequest
import media.qimeng.sdk.models.LibraryCreate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryRepository] 的生成 SDK 实现（U10-6）：/libraries 族五个端点逐一收口。
 * 出网请求挪 IO 线程（SDK 同步 execute 不许占主线程）、逐请求打 logcat
 * （文本证据协议 HANDOVER_APP §4.7），口径仿 [SdkUploadRepository]。
 */
@Singleton
class SdkLibraryRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : LibraryRepository {

    override suspend fun libraries(): List<LibrarySummary> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /libraries")
        val libs = withContext(Dispatchers.IO) { apiFactory.create().apiV1LibrariesGet() }
        return libs.mapNotNull(::toSummary)
    }

    override suspend fun register(name: String, rootPath: String, kind: LibraryKind): LibrarySummary? {
        Log.d(SdkMediaRepository.LOG_TAG, "POST /libraries name=$name rootPath=$rootPath kind=$kind")
        val created = withContext(Dispatchers.IO) {
            apiFactory.create().apiV1LibrariesPost(
                LibraryCreate(name = name, rootPath = rootPath, kind = kind.toSdk()),
            )
        }
        // 响应缺 id 不算失败：返回 null 由调用方跳过续接扫描（接口注释；Web 同守卫）
        return toSummary(created)
    }

    override suspend fun delete(libraryId: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "DELETE /libraries/$libraryId")
        withContext(Dispatchers.IO) { apiFactory.create().apiV1LibrariesLibraryIdDelete(libraryId) }
    }

    override suspend fun scan(libraryId: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "POST /libraries/$libraryId/scan")
        withContext(Dispatchers.IO) { apiFactory.create().apiV1LibrariesLibraryIdScanPost(libraryId) }
    }

    override suspend fun setEnabled(libraryId: String, enabled: Boolean) {
        Log.d(SdkMediaRepository.LOG_TAG, "PUT /libraries/$libraryId/enabled enabled=$enabled")
        withContext(Dispatchers.IO) {
            apiFactory.create().apiV1LibrariesLibraryIdEnabledPut(
                libraryId,
                ApiV1LibrariesLibraryIdEnabledPutRequest(enabled = enabled),
            )
        }
    }

    /** SDK Library -> 领域摘要（id 协议必填，缺 id 条目丢弃，对齐 SdkUploadRepository 口径） */
    private fun toSummary(lib: media.qimeng.sdk.models.Library?): LibrarySummary? {
        val id = lib?.id ?: return null
        return LibrarySummary(
            id = id,
            name = lib.name ?: id,
            rootPath = lib.rootPath.orEmpty(),
            fileCount = lib.fileCount ?: 0,
            videoCount = lib.videoCount ?: 0,
            imageCount = lib.imageCount ?: 0,
            scanState = lib.scanState.toDomain(),
            enabled = lib.enabled ?: true,
            kind = lib.kind.toDomain(),
        )
    }

    private fun media.qimeng.sdk.models.Library.ScanState?.toDomain(): LibraryScanState =
        when (this) {
            media.qimeng.sdk.models.Library.ScanState.scanning -> LibraryScanState.SCANNING
            media.qimeng.sdk.models.Library.ScanState.error -> LibraryScanState.ERROR
            null, media.qimeng.sdk.models.Library.ScanState.idle -> LibraryScanState.IDLE
        }

    private fun media.qimeng.sdk.models.Library.Kind?.toDomain(): LibraryKind = when (this) {
        media.qimeng.sdk.models.Library.Kind.cos -> LibraryKind.COS
        null, media.qimeng.sdk.models.Library.Kind.normal -> LibraryKind.NORMAL
    }

    private fun LibraryKind.toSdk(): LibraryCreate.Kind = when (this) {
        LibraryKind.NORMAL -> LibraryCreate.Kind.normal
        LibraryKind.COS -> LibraryCreate.Kind.cos
    }
}
