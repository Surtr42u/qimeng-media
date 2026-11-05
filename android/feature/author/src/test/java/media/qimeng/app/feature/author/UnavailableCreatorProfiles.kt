package media.qimeng.app.feature.author

import media.qimeng.app.core.data.repository.CreatorProfileRepository
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileDraft

internal object UnavailableCreatorProfiles : CreatorProfileRepository {
    override suspend fun profile(authorId: String): CreatorProfile = error("not requested")
    override suspend fun save(authorId: String, draft: CreatorProfileDraft): CreatorProfile = error("not requested")
    override suspend fun refresh(authorId: String): CreatorProfile = error("not requested")
    override suspend fun avatar(authorId: String): ByteArray = error("avatar unavailable")
}
