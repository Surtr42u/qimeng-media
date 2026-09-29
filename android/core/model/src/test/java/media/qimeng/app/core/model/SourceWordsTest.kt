package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** individualSourceWords：来源建议拆词提取——组合条目按空白拆成平台词，去重保序 */
class SourceWordsTest {

    @Test
    fun `组合条目拆词去重单独词保留首现序`() {
        val vocab = listOf("kemono", "kemono  小红车", "小红车  kemono", "x", "hanime1  小红车", "小红车")
        // hanime1 只存在于组合里，拆词后同样成为建议（2026-09-29 用户实测反馈漏词）
        assertEquals(listOf("kemono", "小红车", "x", "hanime1"), vocab.individualSourceWords())
    }

    @Test
    fun `单空格与多空白分隔同样拆词`() {
        val vocab = listOf("r34  kemono", "小红车\ti站")
        assertEquals(listOf("r34", "kemono", "小红车", "i站"), vocab.individualSourceWords())
    }

    @Test
    fun `空串与纯空白条目不产出词`() {
        val vocab = listOf("", "  ", "kemono")
        assertEquals(listOf("kemono"), vocab.individualSourceWords())
    }

    @Test
    fun `空词表返回空列表`() {
        assertEquals(emptyList<String>(), emptyList<String>().individualSourceWords())
    }

    @Test
    fun `真实出厂词表全形态拆出七个平台词`() {
        // 2026-09-29 真库 GET /authors/source-vocabulary 原样返回
        val vocab = listOf(
            "kemono", "小红车", "kemono  小红车", "kemono  小红车  老王论坛", "x",
            "小红车  kemono", "hanime1  小红车", "r34  kemono", "小红车  i站",
        )
        assertEquals(
            listOf("kemono", "小红车", "老王论坛", "x", "hanime1", "r34", "i站"),
            vocab.individualSourceWords(),
        )
    }
}
