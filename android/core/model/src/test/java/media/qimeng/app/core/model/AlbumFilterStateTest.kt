package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定四维筛选状态机（M4-2 拍板口径）：分区参数映射、kind 分派、切分区清下级、
 * 点击已选取消、key+kind 激活判定、排序组映射。
 */
class AlbumFilterStateTest {

    private fun sourceOption(key: String = "尼尔", count: Int = 10) =
        FacetOption(key = key, name = key, fileCount = count, kind = FacetParamKind.SOURCE)

    private fun cosAuthorOption(key: String = "author-1", count: Int = 5) =
        FacetOption(key = key, name = "Gifdoozer", fileCount = count, kind = FacetParamKind.AUTHOR)

    @Test
    fun `分区 全部 映射为显式 includeCos=true`() {
        val query = AlbumFilter.toAssetQuery(AlbumFilterState(partition = Zone.ALL))
        assertEquals(true, query.includeCos)
        assertNull(query.cosOnly)
    }

    @Test
    fun `分区 常规 不传分区参数`() {
        val query = AlbumFilter.toAssetQuery(AlbumFilterState(partition = Zone.REGULAR))
        assertNull(query.includeCos)
        assertNull(query.cosOnly)
    }

    @Test
    fun `分区 COS 映射为 cosOnly=true`() {
        val query = AlbumFilter.toAssetQuery(AlbumFilterState(partition = Zone.COS))
        assertNull(query.includeCos)
        assertEquals(true, query.cosOnly)
    }

    @Test
    fun `作者行 kind 分派 source 与 authorId 二选一`() {
        val sourceQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(author = sourceOption()),
        )
        assertEquals("尼尔", sourceQuery.source)
        assertNull(sourceQuery.authorId)

        val authorQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(author = cosAuthorOption()),
        )
        assertEquals("author-1", authorQuery.authorId)
        assertNull(authorQuery.source)
    }

    @Test
    fun `角色行 kind 分派 character 与 work 二选一`() {
        val characterQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(
                character = FacetOption("2B", "2B", 3, FacetParamKind.CHARACTER),
            ),
        )
        assertEquals("2B", characterQuery.character)
        assertNull(characterQuery.work)

        val workQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(
                character = FacetOption("尼尔机械纪元", "尼尔机械纪元", 3, FacetParamKind.WORK),
            ),
        )
        assertEquals("尼尔机械纪元", workQuery.work)
        assertNull(workQuery.character)
    }

    @Test
    fun `切分区清空作者与角色选择`() {
        val state = AlbumFilterState(
            partition = Zone.ALL,
            author = sourceOption(),
            character = FacetOption("2B", "2B", 3, FacetParamKind.CHARACTER),
        )
        val next = AlbumFilter.selectPartition(state, Zone.COS)
        assertEquals(Zone.COS, next.partition)
        assertNull(next.author)
        assertNull(next.character)
    }

    @Test
    fun `点已选作者取消选中`() {
        val state = AlbumFilterState(author = sourceOption())
        val cancelled = AlbumFilter.selectAuthor(state, sourceOption())
        assertNull(cancelled.author)
    }

    @Test
    fun `点未选作者为选中`() {
        val state = AlbumFilterState()
        val selected = AlbumFilter.selectAuthor(state, sourceOption())
        assertEquals("尼尔", selected.author?.key)
    }

    @Test
    fun `激活判定必须 key 加 kind 双匹配`() {
        val state = AlbumFilterState(author = sourceOption(key = "同名"))
        // 同名不同 kind（COS 作者）不得误判激活
        assertFalse(AlbumFilter.isAuthorActive(state, cosAuthorOption(key = "同名")))
        assertTrue(AlbumFilter.isAuthorActive(state, sourceOption(key = "同名")))
    }

    @Test
    fun `类型点已选取消 点未选选中`() {
        val state = AlbumFilterState()
        val selected = AlbumFilter.selectMediaType(state, MediaKind.VIDEO)
        assertEquals(MediaKind.VIDEO, selected.mediaType)
        val cancelled = AlbumFilter.selectMediaType(selected, MediaKind.VIDEO)
        assertNull(cancelled.mediaType)
    }

    @Test
    fun `相册页排序固定协议缺省 default 降序（2026-09-06 拍板：不引入排序组）`() {
        val query = AlbumFilter.toAssetQuery(AlbumFilterState())
        assertEquals(AssetSort.DEFAULT, query.sort)
        assertEquals(SortOrder.DESC, query.order)
    }

    @Test
    fun `历史页分区映射方向与 assets 不同`() {
        // /history 缺省 includeCos=true：全部=不传、常规=显式 false、COS=cosOnly
        assertEquals(null to null, zoneToHistoryParams(Zone.ALL))
        assertEquals(false to null, zoneToHistoryParams(Zone.REGULAR))
        assertEquals(null to true, zoneToHistoryParams(Zone.COS))
    }
}
