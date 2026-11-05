package media.qimeng.app.core.model

/** 作者附属公开档案，与普通/COS身份及TXT来源词分开保存。 */
data class CreatorProfile(
    val authorId: String,
    val revision: Long,
    val nickname: String,
    val biography: String,
    val avatarUrl: String,
    val homepageUrl: String,
    val evidenceUrls: List<String>,
    val status: String,
    val autoRefresh: Boolean,
    val feedUrl: String,
    val trackUpdates: Boolean,
    val lastCheckedAt: String,
    val lastSuccessAt: String,
    val lastError: String,
    val candidate: CreatorProfileCandidate,
    val updates: List<CreatorUpdate>,
    val xProfile: CreatorXProfile? = null,
)

data class CreatorProfileCandidate(
    val nickname: String,
    val biography: String,
    val avatarUrl: String,
    val homepageUrl: String,
    val evidenceUrls: List<String>,
) {
    val hasContent: Boolean get() = listOf(nickname, biography, avatarUrl, homepageUrl).any(String::isNotBlank)
}

data class CreatorXProfile(val url: String, val biography: String)

data class CreatorUpdate(val id: String, val title: String, val url: String, val publishedAt: String)

/** revision来自读取快照，不能在提交时替换成较新的版本来绕过冲突。 */
data class CreatorProfileDraft(
    val revision: Long,
    val nickname: String,
    val biography: String,
    val avatarUrl: String,
    val homepageUrl: String,
    val evidenceUrls: List<String>,
    val status: String,
    val autoRefresh: Boolean,
    val feedUrl: String,
    val trackUpdates: Boolean,
    val xProfile: CreatorXProfile? = null,
)

fun CreatorProfile.toDraft() = CreatorProfileDraft(
    revision, nickname, biography, avatarUrl, homepageUrl, evidenceUrls, status, autoRefresh, feedUrl, trackUpdates, xProfile,
)
