package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test
import media.qimeng.app.core.model.TagChip

class DetailTagOrderTest {
    @Test
    fun chineseNamesUsePinyinOrder() {
        val tags = listOf("蓝色", "红色", "白色", "黑色").map { TagChip(it, it) }
        assertEquals(listOf("白色", "黑色", "红色", "蓝色"), orderDetailTags(tags).map { it.name })
        assertEquals(listOf("蓝色", "红色", "白色", "黑色"), tags.map { it.name })
    }

    @Test
    fun numberedNamesUseNumericOrderWithoutIntegerOverflow() {
        val names = listOf("系列10", "系列100000000000000000000", "系列2", "系列1")
        assertEquals(
            listOf("系列1", "系列2", "系列10", "系列100000000000000000000"),
            orderDetailTags(names.map { TagChip(it, it) }).map { it.name },
        )
    }

    @Test
    fun equalNamesHaveStableIdentityOrder() {
        val tags = listOf(TagChip("b", "同名"), TagChip("a", "同名"))
        assertEquals(listOf("a", "b"), orderDetailTags(tags).map { it.id })
        assertEquals(orderDetailTags(tags), orderDetailTags(tags.reversed()))
    }

    @Test
    fun prefixesAndZeroPaddedNumbersRemainDeterministic() {
        val names = listOf("系列10", "系列02", "系列", "系列2", "系列0")
        val ordered = orderDetailTags(names.map { TagChip(it, it) })
        assertEquals("系列", ordered.first().name)
        assertEquals("系列0", ordered[1].name)
        assertEquals(setOf("系列02", "系列2"), ordered.subList(2, 4).map { it.name }.toSet())
        assertEquals("系列10", ordered.last().name)
    }
}
