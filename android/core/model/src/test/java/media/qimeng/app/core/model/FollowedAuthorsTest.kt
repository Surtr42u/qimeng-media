package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 关注列表客户端过滤（C4：GET /authors 无 follow 参数，列表 = followed==true 子集） */
class FollowedAuthorsTest {

    private fun author(id: String, followed: Boolean) =
        AuthorSummary(id = id, displayName = "作者$id", type = AuthorType.REGULAR, fileCount = null, followed = followed, viewCount = null)

    @Test
    fun `只保留已关注作者且保持原序`() {
        val rows = listOf(author("1", true), author("2", false), author("3", true))
        assertEquals(listOf("1", "3"), rows.filterFollowed().map { it.id })
    }

    @Test
    fun `全部未关注为空列表`() {
        val rows = listOf(author("1", false), author("2", false))
        assertEquals(emptyList<String>(), rows.filterFollowed().map { it.id })
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<AuthorSummary>(), emptyList<AuthorSummary>().filterFollowed())
    }
}
