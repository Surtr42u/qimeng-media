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
    fun `作者行前置全部胶囊 payload 为空`() {
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
        assertFalse(pills.first().selected)
        // 「其他」在作者行同样垫底
        assertEquals("其他", pills.last().text.substringBefore(" ("))
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
}
