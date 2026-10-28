package media.qimeng.app.core.data.repository

import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.model.STATS_PROTOCOL_UPDATE_MESSAGE
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SdkStatsRepositoryTest {
    @Test fun `发现SDK保留理由并以请求来源补全签名缩略图地址`() = runTest {
        val path = "/media/thumb/synthetic?size=md&v=v4&exp=9&sig=synthetic"
        val body = """[{"asset":{"id":"10000000-0000-4000-8000-000000000001","fileName":"合成作品","thumbUrl":"$path"},"reason":"rediscover"}]"""
        val entry = repository(body) { url ->
            assertTrue(url.contains("/discoveries"))
            assertTrue(url.contains("seed=42"))
        }.discoveries(42, 6).single()
        assertEquals("rediscover", entry.reason)
        assertEquals("合成作品", entry.title)
        assertEquals(ReadOnlyTestServerConfig().currentServerUrl()!!.trimEnd('/') + path, entry.thumbUrl)
    }
    private val total = """{"viewCount":8,"playCount":2,"seconds":90}"""
    private val types = """{"image":$total,"video":$total,"animated_image":$total}"""
    private val sources = """{"normal":$total,"cos":$total}"""
    private fun repository(body: String, inspect: (String) -> Unit = {}): SdkStatsRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return SdkStatsRepository(BusinessApiFactory(ReadOnlyTestServerConfig(), client))
    }

    @Test fun `真实生成SDK透传精确分类和未截断全量数`() = runTest {
        val body = """{"totalFiles":10,"imageCount":7,"stillImageCount":5,"animatedImageCount":2,"videoCount":3,
            "viewedFilesCount":64,"viewedAuthorsCount":35,"viewedTagsCount":27,
            "windowTotals":$total,"mediaTypeWindowTotals":$types,"sourceWindowTotals":$sources}"""
        val value = repository(body) { assertTrue(it.contains("range=7d")) }.overview("7d")
        assertEquals(5, value.stillImageCount)
        assertEquals(2, value.animatedImageCount)
        assertEquals(64, value.viewedFilesCount)
        assertEquals(35, value.viewedAuthorsCount)
        assertEquals(27, value.viewedTagsCount)
        assertEquals(90, value.windowTotals.seconds)
        assertEquals(8, value.mediaTypeWindowTotals.getValue("animated_image").viewCount)
    }

    @Test fun `真实SDK保留日期桶身份和同轴维度计数`() = runTest {
        val body = """[{"label":"10/22","start":"2026-10-22","end":"2026-10-22",
            "viewCount":8,"playCount":2,"seconds":90,"mediaTypes":$types,"sources":$sources}]"""
        val point = repository(body).trends("all").single()
        assertEquals("2026-10-22", point.start)
        assertEquals("2026-10-22", point.end)
        assertEquals(8, point.mediaTypes.getValue("video").viewCount)
        assertEquals(8, point.sources.getValue("cos").viewCount)
    }

    @Test fun `旧服务端缺字段明确报兼容错误而非伪造零值`() = runTest {
        val result = runCatching { repository("""{"totalFiles":10,"imageCount":7}""").overview("7d") }
        assertEquals(STATS_PROTOCOL_UPDATE_MESSAGE, result.exceptionOrNull()?.message)
    }
}
