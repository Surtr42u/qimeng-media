package media.qimeng.app.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** dateLabel（DOMAIN_RULES §8 逐字口径：今天/昨天/周X/yyyy-MM-dd/未知日期）与分组纯函数 */
class DateGroupingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 固定「现在」= 2026-09-09（周三），避免测试跨日漂移 */
    private val now = at(2026, 9, 9)

    @Test
    fun `今天`() {
        assertEquals("今天", dateLabel(at(2026, 9, 9, 3), now))
    }

    @Test
    fun `昨天`() {
        assertEquals("昨天", dateLabel(at(2026, 9, 8), now))
    }

    @Test
    fun `距今 2 到 6 天显示周X`() {
        // 2026-09-07=周一、09-04=周五、09-03=周四
        assertEquals("周一", dateLabel(at(2026, 9, 7), now))
        assertEquals("周五", dateLabel(at(2026, 9, 4), now))
        assertEquals("周四", dateLabel(at(2026, 9, 3), now))
    }

    @Test
    fun `更早显示 yyyy-MM-dd`() {
        assertEquals("2026-08-31", dateLabel(at(2026, 8, 31), now))
        assertEquals("2025-01-02", dateLabel(at(2025, 1, 2), now))
    }

    @Test
    fun `无时间归未知日期`() {
        assertEquals("未知日期", dateLabel(null, now))
        assertEquals("未知日期", dateLabel(-1L, now))
    }

    @Test
    fun `组头标签与计数间两个空格 N 项后缀`() {
        // 旧版实录逐字：`2026-09-05  2 项` / `尼尔 机械纪元  48 项`（两个空格）
        assertEquals("2026-09-05  2 项", groupHeaderLabel("2026-09-05", 2))
        assertEquals("尼尔 机械纪元  48 项", groupHeaderLabel("尼尔 机械纪元", 48))
    }

    @Test
    fun `分组同标签归并 组间按组首时间降序 未知日期恒最后`() {
        val assets = listOf(
            asset("a-old", at(2026, 8, 1)),
            asset("b-today", at(2026, 9, 9, 9)),
            asset("c-old", at(2026, 8, 15)),
            asset("d-today", at(2026, 9, 9, 8)),
            asset("e-unknown"),
            asset("f-yesterday", at(2026, 9, 8)),
        )
        val sections = assets.groupByDateLabel(now) { it.modifiedAtMs }
        // 组头带「N 项」后缀（M4-2A-B2 旧版实录口径）
        assertEquals(
            listOf("今天  2 项", "昨天  1 项", "2026-08-15  1 项", "2026-08-01  1 项", "未知日期  1 项"),
            sections.map { it.label },
        )
        assertEquals(listOf("b-today", "d-today"), sections.first().items.map { it.id })
        // 组内保持列表原序
        assertEquals(listOf("c-old"), sections[2].items.map { it.id })
    }

    // ---------- groupByAlbumDim（M4-2A-B2 四模式分组，P9-5 口径） ----------

    @Test
    fun `分区与类型模式沿用日期分组`() {
        val assets = listOf(
            asset("a", at(2026, 9, 9, 10)),
            asset("b", at(2026, 8, 1)),
            asset("c"),
        )
        for (dim in listOf(AlbumDim.PARTITION, AlbumDim.TYPE)) {
            val sections = assets.groupByAlbumDim(dim, now)
            assertEquals(
                listOf("今天  1 项", "2026-08-01  1 项", "未知日期  1 项"),
                sections.map { it.label },
            )
        }
    }

    @Test
    fun `作品模式常规按出处 COS 按作者名分组 authorNames 优先`() {
        // 真实载荷口径（服务端实测 /assets?includeCos=true）：source 恒非空——常规未匹配
        // 出处与 COS 资产都被服务端填字面「其他」；authorNames 仅 COS 资产非空（DOMAIN_RULES
        // §4 作者行 = 常规出处分组 ∪ COS 作者）。判别序必须 authorNames 优先，
        // 否则 COS 资产全被 source 分支吞进「其他」组（P1 修复回归）。
        val assets = listOf(
            asset("a", source = "尼尔"), // 常规匹配出处 → 尼尔组
            asset("b", source = "其他", authorNames = listOf("作者一")), // COS：authorNames 优先 → 作者组
            asset("b2", source = "其他", authorNames = listOf("作者一")),
            asset("e", source = "其他", authorNames = listOf("作者二")),
            asset("d", source = "其他"), // 常规未匹配出处（字面「其他」）→ 其他组
        )
        val sections = assets.groupByAlbumDim(AlbumDim.AUTHOR, now)
        // 组间按组首元素位置序；「其他」不按位置、恒排末位（P10）
        assertEquals(listOf("尼尔  1 项", "作者一  2 项", "作者二  1 项", "其他  1 项"), sections.map { it.label })
        assertEquals(listOf("b", "b2"), sections.first { it.label.startsWith("作者一") }.items.map { it.id })
        assertEquals(listOf("a"), sections.first { it.label.startsWith("尼尔") }.items.map { it.id })
    }

    @Test
    fun `作品模式真实载荷混排 COS 归作者组不落其他 常规字面其他归其他`() {
        // P1 回归（真机 + 服务端 18461 隔离实例实测）：GET /assets?includeCos=true 共 22 件，
        // source 恒非空（常规未匹配出处与 COS 资产都填字面「其他」）——旧「source 优先」判别
        // 吞成一组「其他  22 项」，与服务端 facets（测试作者一 3 + 测试作者二 2 + 其他 17）劈叉。
        // 组序 = 组首位置序（服务端原序），输入交错混排且作者组先出现，作者组呈计数序、「其他」沉底。
        val assets = buildList {
            repeat(2) { i ->
                add(asset("cos-a$i", source = "其他", authorNames = listOf("测试作者一"), cosWork = "测试作品M"))
                add(asset("cos-b$i", source = "其他", authorNames = listOf("测试作者二"), cosWork = "测试作品N"))
                add(asset("reg-$i", source = "其他")) // 常规未匹配出处
            }
            add(asset("cos-a2", source = "其他", authorNames = listOf("测试作者一"), cosWork = "测试作品M"))
            repeat(15) { i -> add(asset("reg-${i + 2}", source = "其他")) }
        }
        val sections = assets.groupByAlbumDim(AlbumDim.AUTHOR, now)
        assertEquals(
            listOf("测试作者一  3 项", "测试作者二  2 项", "其他  17 项"),
            sections.map { it.label },
        )
        // COS 资产一件不落「其他」组
        assertEquals(emptyList<String>(), sections.last().items.map { it.id }.filter { it.startsWith("cos-") })
    }

    @Test
    fun `角色模式常规按角色名 COS 按作品名分组`() {
        val assets = listOf(
            asset("a", source = "尼尔", characters = listOf("2B")),
            asset("b", cosWork = "作品M"), // COS 文件按 cosWork 归组
            asset("c", source = "尼尔", characters = emptyList()), // 常规无角色 → 其他
            asset("d", cosWork = "作品M"),
            asset("e", source = "尼尔", characters = listOf("9S")),
            asset("f"), // 无任何字段 → 其他
        )
        val sections = assets.groupByAlbumDim(AlbumDim.CHARACTER, now)
        assertEquals(listOf("2B  1 项", "作品M  2 项", "9S  1 项", "其他  2 项"), sections.map { it.label })
        assertEquals(listOf("b", "d"), sections.first { it.label.startsWith("作品M") }.items.map { it.id })
    }

    @Test
    fun `作品与角色模式优先使用 facetCounts 真实总数展示 避免分页局部数量跳变`() {
        val assets = listOf(
            asset("a", authorNames = listOf("碧蓝航线")),
            asset("b", authorNames = listOf("碧蓝航线")),
        )
        // 第一页局部只加载了 2 个，但服务端 facet 统计实际共有 88 项
        val facetCounts = mapOf("碧蓝航线" to 88)
        val sections = assets.groupByAlbumDim(AlbumDim.AUTHOR, now, facetCounts)
        assertEquals(listOf("碧蓝航线  88 项"), sections.map { it.label })
        assertEquals(2, sections.first().items.size)
    }

    @Test
    fun `角色模式 source 为空但带角色的常规资产归角色组 不落其他`() {
        // P2-1 边界：组键只看 characters∪cosWork（P9-5），不以 source 有无判 COS——
        // source 缺失的常规资产（characters 非空）归其角色组；COS 资产无角色行，characters 优先安全
        val assets = listOf(
            asset("a", characters = listOf("2B")), // source 为空 + 有角色 → 角色组
            asset("b", source = "尼尔", characters = listOf("2B")), // 常规带出处，同角色归并同组
            asset("c"), // 全空 → 其他
        )
        val sections = assets.groupByAlbumDim(AlbumDim.CHARACTER, now)
        assertEquals(listOf("2B  2 项", "其他  1 项"), sections.map { it.label })
        assertEquals(listOf("a", "b"), sections.first().items.map { it.id })
    }

    @Test
    fun `混排数据四模式分组 各归各组 其他恒末位`() {
        val assets = listOf(
            asset("a", at(2026, 9, 9, 10), source = "尼尔", characters = listOf("2B")),
            // b/c 镜像真实载荷：COS 资产 source 恒为字面「其他」，authorNames 仅 COS 非空
            asset("b", source = "其他", authorNames = listOf("作者一"), cosWork = "作品M"),
            asset("c", at(2026, 8, 1), source = "其他", cosWork = "作品N"),
        )
        assertEquals(
            listOf("今天  1 项", "2026-08-01  1 项", "未知日期  1 项"),
            assets.groupByAlbumDim(AlbumDim.PARTITION, now).map { it.label },
        )
        assertEquals(
            listOf("尼尔  1 项", "作者一  1 项", "其他  1 项"),
            assets.groupByAlbumDim(AlbumDim.AUTHOR, now).map { it.label },
        )
        assertEquals(
            listOf("2B  1 项", "作品M  1 项", "作品N  1 项"),
            assets.groupByAlbumDim(AlbumDim.CHARACTER, now).map { it.label },
        )
        assertEquals(
            listOf("今天  1 项", "2026-08-01  1 项", "未知日期  1 项"),
            assets.groupByAlbumDim(AlbumDim.TYPE, now).map { it.label },
        )
    }

    @Test
    fun `空列表分组返回空`() {
        for (dim in AlbumDim.entries) {
            assertEquals(emptyList<GridSection>(), emptyList<MediaAsset>().groupByAlbumDim(dim, now))
        }
    }

    private fun asset(
        id: String,
        modifiedAtMs: Long? = null,
        source: String? = null,
        characters: List<String> = emptyList(),
        authorNames: List<String> = emptyList(),
        cosWork: String? = null,
    ): MediaAsset = MediaAsset(
        id = id,
        fileName = "$id.jpg",
        title = id,
        mediaType = MediaKind.IMAGE,
        thumbUrl = null,
        source = source,
        characters = characters,
        isFavorite = false,
        authorNames = authorNames,
        modifiedAtMs = modifiedAtMs,
        durationMs = null,
        viewCount = null,
        playCount = null,
        lastViewedAtMs = null,
        cosWork = cosWork,
    )
}
