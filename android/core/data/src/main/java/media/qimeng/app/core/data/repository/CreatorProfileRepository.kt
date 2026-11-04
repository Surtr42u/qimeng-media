package media.qimeng.app.core.data.repository

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileCandidate
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.CreatorUpdate
import media.qimeng.app.core.network.di.BackupClient
import okhttp3.OkHttpClient

interface CreatorProfileRepository {
    suspend fun profile(authorId: String): CreatorProfile
    suspend fun save(authorId: String, draft: CreatorProfileDraft): CreatorProfile
    suspend fun refresh(authorId: String): CreatorProfile
    suspend fun avatar(authorId: String): ByteArray
}

/** 资料与图片均经当前服务的认证SDK，不把凭据送到作者外站。 */
@Singleton
class SdkCreatorProfileRepository @Inject constructor(
    private val factory: BusinessApiFactory,
    @BackupClient private val client: OkHttpClient,
) : CreatorProfileRepository {
    override suspend fun profile(authorId: String) = withContext(Dispatchers.IO) {
        factory.create().apiV1AuthorsAuthorIdProfileGet(authorId).toDomain()
    }

    override suspend fun save(authorId: String, draft: CreatorProfileDraft) = withContext(Dispatchers.IO) {
        factory.create().apiV1AuthorsAuthorIdProfilePut(
            authorId,
            media.qimeng.sdk.models.CreatorProfileWrite(
                revision = draft.revision, nickname = draft.nickname, biography = draft.biography,
                avatarUrl = draft.avatarUrl, homepageUrl = draft.homepageUrl, evidenceUrls = draft.evidenceUrls,
                status = media.qimeng.sdk.models.CreatorProfileWrite.Status.valueOf(draft.status),
                autoRefresh = draft.autoRefresh, feedUrl = draft.feedUrl, trackUpdates = draft.trackUpdates,
            ),
        ).toDomain()
    }

    override suspend fun refresh(authorId: String) = withContext(Dispatchers.IO) {
        factory.createWith(client).apiV1AuthorsAuthorIdProfileRefreshPost(authorId).toDomain()
    }

    override suspend fun avatar(authorId: String): ByteArray = withContext(Dispatchers.IO) {
        val file = factory.createWith(client).apiV1AuthorsAuthorIdProfileAvatarGet(authorId)
        try {
            require(file.length() <= MAX_AVATAR_BYTES) { "作者头像超过大小限制" }
            file.readBytes()
        } finally {
            if (!file.delete()) Log.w("CreatorProfile", "头像临时文件清理失败")
        }
    }

    private companion object { const val MAX_AVATAR_BYTES = 2L * 1024 * 1024 }
}

private fun media.qimeng.sdk.models.CreatorProfile.toDomain() = CreatorProfile(
    authorId, revision, nickname, biography, avatarUrl, homepageUrl, evidenceUrls, status.value,
    autoRefresh, feedUrl, trackUpdates, lastCheckedAt, lastSuccessAt, lastError,
    CreatorProfileCandidate(candidate.nickname, candidate.biography, candidate.avatarUrl, candidate.homepageUrl, candidate.evidenceUrls),
    updates.map { CreatorUpdate(it.id, it.title, it.url, it.publishedAt) },
)

@Module
@InstallIn(SingletonComponent::class)
abstract class CreatorProfileModule {
    @Binds abstract fun bindCreatorProfileRepository(impl: SdkCreatorProfileRepository): CreatorProfileRepository
}
