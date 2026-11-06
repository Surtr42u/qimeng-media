package media.qimeng.app.feature.author

import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorUpdatesPresentationTest {
    private val profile = CreatorProfile(
        "example", 1, "", "", "", "https://example.com", emptyList(), "verified", false,
        "https://example.com/feed", true, "", "", "",
        CreatorProfileCandidate("", "", "", "", emptyList()), emptyList(),
    )

    @Test fun `未配置与未启用不冒称已完成检查`() {
        assertEquals("尚未设置公开订阅源，可先前往作者主页查看。", creatorUpdatesEmptyText(profile.copy(feedUrl = "")))
        assertEquals("动态追踪未开启，可在设置中按需启用。", creatorUpdatesEmptyText(profile.copy(trackUpdates = false)))
    }

    @Test fun `检查失败与未检查区别于已检查空记录`() {
        assertEquals("检查未完成，暂时没有可展示的动态。", creatorUpdatesEmptyText(profile.copy(lastError = "订阅不可用", lastCheckedAt = "2026-11-05T00:00:00Z")))
        assertEquals("已开启追踪，尚未检查公开动态。", creatorUpdatesEmptyText(profile))
        assertEquals("暂无已获取的公开动态，不代表作者没有发布新作品。", creatorUpdatesEmptyText(profile.copy(lastCheckedAt = "2026-11-05T00:00:00Z")))
    }
}
