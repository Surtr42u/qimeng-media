package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「其他」桶恒置底 + 四维药丸装配（Android 新增逻辑，openapi 只承诺 fileCount 降序） */
class FourDimPillsTest {

    private fun option(name: String, count: Int, kind: FacetParamKind = FacetParamKind.SOURCE) =
        FacetOption(key = name, name = name, fileCount = count, kind = kind)

    @Test
    fun `其他桶恒排末尾 不参与计数排序`() {
        val ordered = listOf(
            option("尼尔", 30),
            option("其他", 25), // 计数比前面的候选还大，也必须垫底
            option("原神", 20),
        ).withOtherBucketLast()
        assertEquals("尼尔", ordered[0].name)
        assertEquals("原神", ordered[1].name)
        assertEquals("其他", ordered.last().name)
    }

    @Test
    fun `无其他桶保持服务端原序`() {
        val ordered = listOf(option("B", 30), option("A", 20)).withOtherBucketLast()
        assertEquals(listOf("B", "A"), ordered.map { it.name })
    }

    @Test
    fun `作者行前置全部胶囊 行空选中 payload 为空`() {
        // W6 #2 对标旧实录（C8 §3.3 S2/S3 翻案）：行内无候选选中时「全部」实底高亮（selected=true）
        val model = FourDimPillModel(
            filter = AlbumFilterState(),
            activeDim = AlbumDim.AUTHOR,
            partitionOptions = listOf(option("全部", 100), option("常规", 60), option("COS", 40)),
            authorOptions = listOf(option("尼尔", 30), option("其他", 25)),
            characterOptions = emptyList(),
            typeOptions = emptyList(),
            totalForAllPill = 100,
        )
        val pills = FourDimPills.pillsFor(model, AlbumDim.AUTHOR)
        assertEquals("全部 (100)", pills.first().text)
        assertNull(pills.first().payload)
        assertTrue(pills.first().selected)
        // 「其他」在作者行同样垫底
        assertEquals("其他", pills.last().text.substringBefore(" ("))
    }

    @Test
    fun `作者行有选中时全部胶囊不选中`() {
        // W6 #2 对标旧实录：行内点选候选后「全部」让位为未选中，候选丸转选中（点击清行行为不变）
        val picked = option("尼尔", 30)
        val model = FourDimPillModel(
            filter = AlbumFilterState(authors = setOf(picked)),
            activeDim = AlbumDim.AUTHOR,
            partitionOptions = emptyList(),
            authorOptions = listOf(picked, option("其他", 25)),
            characterOptions = emptyList(),
            typeOptions = emptyList(),
            totalForAllPill = 100,
        )
        val pills = FourDimPills.pillsFor(model, AlbumDim.AUTHOR)
        assertFalse(pills.first().selected)
        assertTrue(pills[1].selected)
        // 角色行同口径：行空=「全部」选中
        val characterModel = model.copy(filter = model.filter.copy(characters = emptySet()), activeDim = AlbumDim.CHARACTER)
        assertTrue(FourDimPills.pillsFor(characterModel, AlbumDim.CHARACTER).first().selected)
    }

    @Test
    fun `维度芯片数量不含全部`() {
        val model = FourDimPillModel(
            filter = AlbumFilterState(),
            activeDim = AlbumDim.PARTITION,
            partitionOptions = listOf(option("全部", 100), option("常规", 60), option("COS", 40)),
            authorOptions = listOf(option("尼尔", 30)),
            characterOptions = listOf(option("2B", 10), option("9S", 8)),
            typeOptions = listOf(option("全部", 100), option("图片", 50), option("视频", 30), option("动图", 20)),
            totalForAllPill = 100,
        )
        val chips = FourDimPills.dimChips(model)
        assertTrue(chips[0].text.endsWith("(2)")) // 分区：常规+COS
        assertTrue(chips[1].text.endsWith("(1)")) // 作者行 1 个候选
        assertTrue(chips[2].text.endsWith("(2)")) // 角色 2 个候选
        assertTrue(chips[3].text.endsWith("(3)")) // 类型 3 档
    }

    @Test
    fun `分区与类型 key 双向映射`() {
        assertEquals(Zone.COS, FourDimPills.zoneFromKey("cos"))
        assertEquals(Zone.REGULAR, FourDimPills.zoneFromKey("regular"))
        assertEquals(Zone.ALL, FourDimPills.zoneFromKey("all"))
        assertEquals("cos", FourDimPills.zoneToKey(Zone.COS))

        assertNull(FourDimPills.mediaKindFromKey("all"))
        assertEquals(MediaKind.ANIMATED_IMAGE, FourDimPills.mediaKindFromKey("animated_image"))
        assertEquals("video", FourDimPills.mediaKindToKey(MediaKind.VIDEO))
    }

    @Test
    fun `维度字样为旧版芯片栏逐字口径`() {
        // 旧版实录 all_partition.txt：「分区 (2)/作品 (61)/角色 (294)/类型 (3)」（P9-3 裁决）
        assertEquals("分区", AlbumDim.PARTITION.label)
        assertEquals("作品", AlbumDim.AUTHOR.label)
        assertEquals("角色", AlbumDim.CHARACTER.label)
        assertEquals("类型", AlbumDim.TYPE.label)
    }

    @Test
    fun `MediaKind 类型名与服务端 facet 标签同源`() {
        assertEquals("图片", FourDimPills.mediaKindLabel(MediaKind.IMAGE))
        assertEquals("动图", FourDimPills.mediaKindLabel(MediaKind.ANIMATED_IMAGE))
        assertEquals("视频", FourDimPills.mediaKindLabel(MediaKind.VIDEO))
    }

    @Test
    fun `类型维折叠芯片显示当前类型名或全部`() {
        // P9-4：折叠态「图片 ▼」（有选中）/「全部 ▼」（无选中）；展开或非激活维恢复计数式
        fun model(mediaType: MediaKind?, expanded: Boolean, activeDim: AlbumDim = AlbumDim.TYPE) =
            FourDimPillModel(
                filter = AlbumFilterState(mediaType = mediaType, expanded = expanded),
                activeDim = activeDim,
                partitionOptions = emptyList(),
                authorOptions = emptyList(),
                characterOptions = emptyList(),
                typeOptions = listOf(option("全部", 100), option("图片", 50)),
                totalForAllPill = null,
            )
        assertEquals("图片 ▼", FourDimPills.dimChips(model(MediaKind.IMAGE, expanded = false)).last().text)
        assertEquals("全部 ▼", FourDimPills.dimChips(model(null, expanded = false)).last().text)
        // 展开态：恢复「类型 (N)」计数式
        assertTrue(FourDimPills.dimChips(model(MediaKind.IMAGE, expanded = true)).last().text.endsWith("(1)"))
        // 非激活维（即使容器折叠）：保持计数式
        val otherDim = FourDimPills.dimChips(
            model(MediaKind.IMAGE, expanded = false, activeDim = AlbumDim.PARTITION),
        )
        assertEquals("类型 (1)", otherDim.last().text)
    }

    @Test
    fun `零计数药丸照常显示 全部胶囊恒保留`() {
        // 旧版实录 album_tab.txt：零计数候选「角色 (0)」照常在列；GUIDE_UI 类型药丸列固定四项——
        // 可见性只由数据行决定，不做计数过滤；「全部」胶囊=清行动作恒保留
        val model = FourDimPillModel(
            filter = AlbumFilterState(),
            activeDim = AlbumDim.AUTHOR,
            partitionOptions = emptyList(),
            authorOptions = listOf(option("尼尔", 30), option("零号", 0)),
            characterOptions = emptyList(),
            typeOptions = emptyList(),
            totalForAllPill = 22,
        )
        val pills = FourDimPills.pillsFor(model, AlbumDim.AUTHOR)
        assertEquals(listOf("全部 (22)", "尼尔 (30)", "零号 (0)"), pills.map { it.text })
        assertTrue(pills.last().text.endsWith("(0)"))
    }

    @Test
    fun `类型行按计数降序 全部恒首位`() {
        // 服务端类型候选恒固定枚举序（all/image/animated_image/video），旧版按计数降序展示——客户端重排
        // （kind 仅作者/角色行分派用，类型行不消费，SOURCE 为占位）
        val kind = FacetParamKind.SOURCE
        val model = FourDimPillModel(
            filter = AlbumFilterState(),
            activeDim = AlbumDim.TYPE,
            partitionOptions = emptyList(),
            authorOptions = emptyList(),
            characterOptions = emptyList(),
            typeOptions = listOf(
                FacetOption(key = "all", name = "全部", fileCount = 100, kind = kind),
                FacetOption(key = "image", name = "图片", fileCount = 50, kind = kind),
                FacetOption(key = "animated_image", name = "动图", fileCount = 20, kind = kind),
                FacetOption(key = "video", name = "视频", fileCount = 30, kind = kind),
            ),
            totalForAllPill = 100,
        )
        val pills = FourDimPills.pillsFor(model, AlbumDim.TYPE)
        assertEquals(listOf("全部 (100)", "图片 (50)", "视频 (30)", "动图 (20)"), pills.map { it.text })
        assertNull(pills.first().payload)
        assertEquals(MediaKind.IMAGE, pills[1].payload)
    }
}
