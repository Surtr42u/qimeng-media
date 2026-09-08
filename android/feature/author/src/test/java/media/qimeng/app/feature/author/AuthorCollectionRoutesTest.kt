package media.qimeng.app.feature.author

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作者集合页路由构建单测（任务G G1b）：锁定路径段编码（中文/空格/斜杠）与路由串拼接——
 * 斜杠不编码会劈裂 Navigation 路径段、中文必须百分号编码，两端由 Navigation 的
 * Uri.decode 闭环还原。
 */
class AuthorCollectionRoutesTest {

    @Test
    fun `路径段编码 - unreserved字符原样保留`() {
        assertEquals("abcXYZ019" + "-_.~", encodeRouteSegment("abcXYZ019" + "-_.~"))
    }

    @Test
    fun `路径段编码 - 中文按UTF-8字节百分号编码`() {
        // 「蠢」= E8 A0 A2（UTF-8），「沫」= E6 B2 AB——按字节序列断言
        assertEquals("%E8%A0%A2%E6%B2%AB", encodeRouteSegment("蠢沫"))
    }

    @Test
    fun `路径段编码 - 空格与斜杠必须编码（斜杠劈裂路径段 空格非法URL字符）`() {
        assertEquals("%20", encodeRouteSegment(" "))
        assertEquals("%2F", encodeRouteSegment("/"))
        assertEquals("a%2Fb%20c", encodeRouteSegment("a/b c"))
    }

    @Test
    fun `路径段编码 - 保留符号保守编码无损（括号等平台默认保留集内字符也编码）`() {
        assertEquals("%28%29", encodeRouteSegment("()"))
    }

    @Test
    fun `路由构建 - id原样 name编码拼接`() {
        assertEquals(
            "author_collection/11111111-1111-1111-1111-111111111111/%E4%BD%9C%E8%80%85",
            AuthorCollectionRoutes.authorCollectionRoute("11111111-1111-1111-1111-111111111111", "作者"),
        )
    }

    @Test
    fun `路由模式 - 参数键与占位符对齐（ViewModel SavedStateHandle 同键读取）`() {
        assertEquals("author_collection/{authorId}/{authorName}", AuthorCollectionRoutes.AUTHOR_COLLECTION_ROUTE)
        assertEquals("authorId", AuthorCollectionRoutes.KEY_AUTHOR_ID)
        assertEquals("authorName", AuthorCollectionRoutes.KEY_AUTHOR_NAME)
    }
}
