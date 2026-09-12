package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 作者页行筛选/排序（Web AuthorsPage A_SORTERS 同口径）与搜索建议排序 */
class AuthorRowsTest {

    private fun author(
        id: String,
        name: String,
        type: AuthorType = AuthorType.REGULAR,
        fileCount: Int? = null,
        viewCount: Int? = null,
        followed: Boolean = false,
    ) = AuthorSummary(id = id, displayName = name, type = type, fileCount = fileCount, followed = followed, viewCount = viewCount)

    private val authors = listOf(
        author("1", "Gifdoozer", AuthorType.COS, fileCount = 500, viewCount = 30),
        author("2", "尼尔", fileCount = 100, viewCount = 200),
        author("3", "海伦", AuthorType.COS, fileCount = 300, viewCount = 100),
        author("4", "原神", fileCount = 10, viewCount = 50),
    )

    @Test
    fun `体系胶囊按类型过滤`() {
        val cosOnly = authors.applyAuthorRows(Zone.COS, "", AuthorSortOption.DEFAULT)
        assertEquals(listOf("Gifdoozer", "海伦"), cosOnly.map { it.displayName })

        val regularOnly = authors.applyAuthorRows(Zone.REGULAR, "", AuthorSortOption.DEFAULT)
        assertEquals(listOf("尼尔", "原神"), regularOnly.map { it.displayName })
    }

    @Test
    fun `名字搜索包含匹配`() {
        val rows = authors.applyAuthorRows(Zone.ALL, "尼", AuthorSortOption.DEFAULT)
        assertEquals(listOf("尼尔"), rows.map { it.displayName })
    }

    @Test
    fun `排序三项 默认原序 浏览数降序 文件数降序`() {
        assertEquals(listOf("1", "2", "3", "4"), authors.applyAuthorRows(Zone.ALL, "", AuthorSortOption.DEFAULT).map { it.id })
        assertEquals(listOf("2", "3", "4", "1"), authors.applyAuthorRows(Zone.ALL, "", AuthorSortOption.BROWSE).map { it.id })
        assertEquals(listOf("1", "3", "2", "4"), authors.applyAuthorRows(Zone.ALL, "", AuthorSortOption.WORKS).map { it.id })
    }

    @Test
    fun `显示名 COS 作者追加点COS标识`() {
        assertEquals("Gifdoozer ·COS", authors[0].displayLabel)
        assertEquals("尼尔", authors[1].displayLabel)
    }

    @Test
    fun `建议从短到长 稳定保持同长原相对序`() {
        val suggestions = listOf(
            NameSuggestion("尼尔机械纪元", SuggestionKind.SOURCE),
            NameSuggestion("尼尔", SuggestionKind.SOURCE),
            NameSuggestion("尼酱", SuggestionKind.CHARACTER),
        ).sortedShortestFirst()
        assertEquals(listOf("尼尔", "尼酱", "尼尔机械纪元"), suggestions.map { it.name })
        assertTrue(suggestions.first().name.length <= suggestions.last().name.length)
    }
}
