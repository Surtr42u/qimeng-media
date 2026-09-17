package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定四维筛选状态机（M4-2 拍板口径 + N4 消费批多选语义）：分区参数映射、kind 分派、
 * 切分区清下级、点已选取消/未选入集（多选 OR）、key+kind 激活判定、多选集→协议数组
 * 参数投影、authorId 单值位多选降级（排序组已按用户拍板移除：相册=旧版全部页完全一致，
 * 排序默认 DEFAULT 协议缺省降序（2026-09-17 用户拍板：默认选中档=「默认」且面板只留三档，
 * 取代 2026-09-15 的 FILE_DATE 文件时间缺省拍板）。
 */
class AlbumFilterStateTest {

    private fun sourceOption(key: String = "尼尔", count: Int = 10) =
        FacetOption(key = key, name = key, fileCount = count, kind = FacetParamKind.SOURCE)

    private fun cosAuthorOption(key: String = "author-1", count: Int = 5) =
        FacetOption(key = key, name = "Gifdoozer", fileCount = count, kind = FacetParamKind.AUTHOR)

    private fun characterOption(key: String = "2B", count: Int = 3) =
        FacetOption(key = key, name = key, fileCount = count, kind = FacetParamKind.CHARACTER)

    private fun workOption(key: String = "尼尔机械纪元", count: Int = 3) =
        FacetOption(key = key, name = key, fileCount = count, kind = FacetParamKind.WORK)

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
            AlbumFilterState(authors = setOf(sourceOption())),
        )
        assertEquals(listOf("尼尔"), sourceQuery.source)
        assertNull(sourceQuery.authorId)

        val authorQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(authors = setOf(cosAuthorOption())),
        )
        assertEquals("author-1", authorQuery.authorId)
        assertNull(authorQuery.source)
    }

    @Test
    fun `作者行多选 source 投影为 key 列表`() {
        val state = AlbumFilterState(authors = setOf(sourceOption("出处A"), sourceOption("出处B")))
        val query = AlbumFilter.toAssetQuery(state)
        // Set 迭代序不稳定，按集合断言
        assertEquals(setOf("出处A", "出处B"), query.source?.toSet())
        assertNull(query.authorId)
    }

    @Test
    fun `作者行 source 与 COS 作者混选 source 数组照传 authorId 单值位降级`() {
        val state = AlbumFilterState(
            authors = setOf(
                sourceOption("出处A"),
                cosAuthorOption("cos-1"),
                cosAuthorOption("cos-2"),
            ),
        )
        val query = AlbumFilter.toAssetQuery(state)
        assertEquals(setOf("出处A"), query.source?.toSet())
        // authorId 协议单值、两个 COS 作者同选无法表达——降级不传（列表放宽非错误）
        assertNull(query.authorId)
    }

    @Test
    fun `作者行单 COS 作者 authorId 照常传`() {
        val state = AlbumFilterState(authors = setOf(cosAuthorOption("cos-1")))
        val query = AlbumFilter.toAssetQuery(state)
        assertNull(query.source)
        assertEquals("cos-1", query.authorId)
    }

    @Test
    fun `角色行 kind 分派 character 与 work 二选一`() {
        val characterQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(characters = setOf(characterOption())),
        )
        assertEquals(listOf("2B"), characterQuery.character)
        assertNull(characterQuery.work)

        val workQuery = AlbumFilter.toAssetQuery(
            AlbumFilterState(characters = setOf(workOption())),
        )
        assertEquals(listOf("尼尔机械纪元"), workQuery.work)
        assertNull(workQuery.character)
    }

    @Test
    fun `角色行多选 character 与 work 各自投影`() {
        val state = AlbumFilterState(
            characters = setOf(characterOption("2B"), characterOption("A2"), workOption("尼尔机械纪元")),
        )
        val query = AlbumFilter.toAssetQuery(state)
        assertEquals(setOf("2B", "A2"), query.character?.toSet())
        assertEquals(listOf("尼尔机械纪元"), query.work)
    }

    @Test
    fun `切分区清空作者与角色选择`() {
        val state = AlbumFilterState(
            partition = Zone.ALL,
            authors = setOf(sourceOption()),
            characters = setOf(characterOption()),
        )
        val next = AlbumFilter.selectPartition(state, Zone.COS)
        assertEquals(Zone.COS, next.partition)
        assertTrue(next.authors.isEmpty())
        assertTrue(next.characters.isEmpty())
    }

    @Test
    fun `点已选作者取消选中`() {
        val state = AlbumFilterState(authors = setOf(sourceOption()))
        val cancelled = AlbumFilter.selectAuthor(state, sourceOption())
        assertTrue(cancelled.authors.isEmpty())
    }

    @Test
    fun `点未选作者为选中`() {
        val state = AlbumFilterState()
        val selected = AlbumFilter.selectAuthor(state, sourceOption())
        assertEquals("尼尔", selected.authors.first().key)
    }

    @Test
    fun `多选两作者后点其中一个 只取消被点者`() {
        // 旧版语义（GUIDE_UI §浏览历史「支持多选作品筛选」）：多选不退出、再点取消单个
        val state = AlbumFilterState(authors = setOf(sourceOption("出处A"), sourceOption("出处B")))
        val next = AlbumFilter.selectAuthor(state, sourceOption("出处A"))
        assertEquals(setOf("出处B"), next.authors.map { it.key }.toSet())
    }

    @Test
    fun `全部胶囊 null 清本行`() {
        val state = AlbumFilterState(authors = setOf(sourceOption(), cosAuthorOption()))
        val cleared = AlbumFilter.selectAuthor(state, null)
        assertTrue(cleared.authors.isEmpty())
        val clearedChars = AlbumFilter.selectCharacter(
            cleared.copy(characters = setOf(characterOption())),
            null,
        )
        assertTrue(clearedChars.characters.isEmpty())
    }

    @Test
    fun `多选不退出 选择不改面板展开态`() {
        val state = AlbumFilterState(expanded = true)
        val next = AlbumFilter.selectAuthor(state, sourceOption())
        assertTrue(next.expanded)
    }

    @Test
    fun `fileCount 变化的同 key 候选再点仍算取消`() {
        // facets 刷新后 fileCount 变化：key+kind 判同不受影响
        val state = AlbumFilterState(authors = setOf(sourceOption(count = 10)))
        val cancelled = AlbumFilter.selectAuthor(state, sourceOption(count = 99))
        assertTrue(cancelled.authors.isEmpty())
    }

    @Test
    fun `激活判定必须 key 加 kind 双匹配`() {
        val state = AlbumFilterState(authors = setOf(sourceOption(key = "同名")))
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
    fun `facets 收窄参数 单选照传 多选省略`() {
        val single = AlbumFilterState(
            authors = setOf(sourceOption("出处A")),
            characters = setOf(characterOption("2B")),
        )
        val singleQuery = AlbumFilter.partitionFacetsQuery(single, favorite = true)
        assertEquals("出处A", singleQuery.source)
        assertEquals("2B", singleQuery.character)

        val multi = AlbumFilterState(
            authors = setOf(sourceOption("出处A"), sourceOption("出处B")),
            characters = setOf(characterOption("2B"), characterOption("A2")),
        )
        val multiQuery = AlbumFilter.partitionFacetsQuery(multi, history = true)
        // facets 单值位无法表达多选→该维收窄省略（候选放宽为未约束计数）
        assertNull(multiQuery.source)
        assertNull(multiQuery.authorId)
        assertNull(multiQuery.character)
        assertNull(multiQuery.work)
    }

    @Test
    fun `相册页排序默认档降序（2026-09-17 307 笔：「默认」发参=媒体文件时间 fileDate，非协议缺省入库序）`() {
        val query = AlbumFilter.toAssetQuery(AlbumFilterState())
        assertEquals(AssetSort.FILE_DATE, query.sort)
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
