package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** individualSourceWords：来源建议只出单独词，组合（含空白）条目过滤 */
class SourceWordsTest {

    @Test
    fun `组合条目被过滤单独词保留`() {
        val vocab = listOf("kemono", "kemono  小红车", "小红车  kemono", "x", "hanime1  小红车", "小红车")
        assertEquals(listOf("kemono", "x", "小红车"), vocab.individualSourceWords())
    }

    @Test
    fun `单空格分隔同样视为组合`() {
        val vocab = listOf("老王论坛 合集", "老王论坛")
        assertEquals(listOf("老王论坛"), vocab.individualSourceWords())
    }

    @Test
    fun `空串与纯空白条目被过滤且原序保留`() {
        val vocab = listOf("", "  ", "kemono", "x")
        assertEquals(listOf("kemono", "x"), vocab.individualSourceWords())
    }

    @Test
    fun `空词表返回空列表`() {
        assertEquals(emptyList<String>(), emptyList<String>().individualSourceWords())
    }
}
