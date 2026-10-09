package media.qimeng.app.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---------- dateCounts（服务端按本地日精确计数，协议 2026-10-10 加） ----------

    @Test
    fun `本地日键与服务端 dateCounts 的 date 字段同口径`() {
        // 服务端按 tzOffsetMinutes 把 UTC 的 mtime 折算成本地日，客户端按设备时区
        // 折算——两侧同键（yyyy-MM-dd，补零）才能对上
        assertEquals("2026-09-09", localDayKey(at(2026, 9, 9, 0)))
        assertEquals("2026-09-09", localDayKey(at(2026, 9, 9, 23)))
        assertEquals("2026-01-05", localDayKey(at(2026, 1, 5, 12)))
    }

    @Test
    fun `设备时区偏移按分钟返回 东八区为 480`() {
        val ts = at(2026, 9, 9)
        assertEquals(
            java.util.TimeZone.getDefault().getOffset(ts) / 60_000,
            deviceTzOffsetMinutes(ts),
        )
        // 分钟量级（不是毫秒），且在协议允许域内（UTC−14~+14）
        val v = deviceTzOffsetMinutes(ts)
        assertTrue(v >= -840 && v <= 840)
    }

    @Test
    fun `dateCounts 命中时组头用服务端精确总数 不再显示已加载条数`() {
        // 复刻真机缺陷场景：今天实际 125 条，分页只加载了 2 条——
        // 修复前组头显示「今天  2 项」并随滚动跳增，修复后恒为真实总数
        val assets = listOf(
            asset("a", at(2026, 9, 9, 9)),
            asset("b", at(2026, 9, 9, 8)),
        )
        val counts = mapOf(localDayKey(at(2026, 9, 9)) to 125)
        val sections = assets.groupByDateLabel(now, counts) { it.modifiedAtMs }
        assertEquals(listOf("今天  125 项"), sections.map { it.label })
        assertEquals(2, sections.first().items.size)
    }

    @Test
    fun `dateCounts 未命中的日与未知日期组回退已加载条数`() {
        val assets = listOf(
            asset("a", at(2026, 8, 1)),
            asset("b", at(2026, 8, 1)),
            asset("c-unknown"), // 无时间 → 未知日期组无日键，恒回退
        )
        // 只给了「今天」的精确数：8-01 与未知日期两组都回退已加载条数
        val counts = mapOf(localDayKey(at(2026, 9, 9)) to 999)
        val sections = assets.groupByDateLabel(now, counts) { it.modifiedAtMs }
        assertEquals(listOf("2026-08-01  2 项", "未知日期  1 项"), sections.map { it.label })
    }

    @Test
    fun `dateCounts 不改变分组归属与组内原序 只改组头数字`() {
        val assets = listOf(
            asset("x1", at(2026, 9, 9, 9)),
            asset("y1", at(2026, 9, 8)),
            asset("x2", at(2026, 9, 9, 7)),
        )
        val counts = mapOf(
            localDayKey(at(2026, 9, 9)) to 40,
            localDayKey(at(2026, 9, 8)) to 7,
        )
        val sections = assets.groupByDateLabel(now, counts) { it.modifiedAtMs }
        assertEquals(listOf("今天  40 项", "昨天  7 项"), sections.map { it.label })
        assertEquals(listOf("x1", "x2"), sections[0].items.map { it.id })
        assertEquals(listOf("y1"), sections[1].items.map { it.id })
    }

    @Test
    fun `日期维透传 dateCounts 而作品与角色维仍走 facetCounts`() {
        val assets = listOf(
            asset("a", at(2026, 9, 9, 9), source = "碧蓝航线"),
            asset("b", at(2026, 9, 9, 8), source = "碧蓝航线"),
        )
        val dateCounts = mapOf(localDayKey(at(2026, 9, 9)) to 125)
        val facetCounts = mapOf("碧蓝航线" to 88)
        // 分区/类型维（日期分组）用服务端按日精确数
        assertEquals(
            listOf("今天  125 项"),
            assets.groupByAlbumDim(AlbumDim.PARTITION, now, facetCounts, dateCounts).map { it.label },
        )
        assertEquals(
            listOf("今天  125 项"),
            assets.groupByAlbumDim(AlbumDim.TYPE, now, facetCounts, dateCounts).map { it.label },
        )
        // 作品/角色维不受 dateCounts 影响（各自 facetCounts 口径不变）
        assertEquals(
            listOf("碧蓝航线  88 项"),
            assets.groupByAlbumDim(AlbumDim.AUTHOR, now, facetCounts, dateCounts).map { it.label },
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
