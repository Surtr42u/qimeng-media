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
    fun `名字搜索大小写不敏感`() {
        val mixed = listOf(
            author("1", "BubbleB"),
            author("2", "Regina3D"),
            author("3", "skrev / SKRevenant"),
        )
        // 小写输入命中大写名（2026-10-12 用户实测：作者名大写、输小写搜不出来）
        assertEquals(
            listOf("BubbleB"),
            mixed.applyAuthorRows(Zone.ALL, "bubbleb", AuthorSortOption.DEFAULT).map { it.displayName },
        )
        assertEquals(
            listOf("Regina3D"),
            mixed.applyAuthorRows(Zone.ALL, "regina3d", AuthorSortOption.DEFAULT).map { it.displayName },
        )
        // 大写输入命中（原名子串大小写各异）
        assertEquals(
            listOf("skrev / SKRevenant"),
            mixed.applyAuthorRows(Zone.ALL, "SKREV", AuthorSortOption.DEFAULT).map { it.displayName },
        )
        // 混排输入
        assertEquals(
            listOf("BubbleB"),
            mixed.applyAuthorRows(Zone.ALL, "bUbBlEb", AuthorSortOption.DEFAULT).map { it.displayName },
        )
        // 别名段整体小写输入命中（「 / 」连接的显示名按段子串匹配，大小写不敏感）
        assertEquals(
            listOf("skrev / SKRevenant"),
            mixed.applyAuthorRows(Zone.ALL, "skrevenant", AuthorSortOption.DEFAULT).map { it.displayName },
        )
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
