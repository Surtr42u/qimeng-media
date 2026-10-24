package media.qimeng.app.core.data.repository

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** 真生成 SDK 与 Repository 映射，socket 由内存拦截器替代。 */
class SdkMediaRecommendationTest {
    @Test
    fun `COS与常规请求只切换候选分区且seed原样发送`() = runTest {
        val urls = mutableListOf<HttpUrl>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("[]".toResponseBody()).build()
        }.build()
        val repository = SdkMediaRepository(BusinessApiFactory(ReadOnlyTestServerConfig(), client))
        repository.recommendations(1, 200, null)
        repository.recommendations(2, 200, null, cosOnly = true)
        assertEquals(listOf("false", "true"), urls.map { it.queryParameter("cosOnly") })
        assertEquals(listOf("1", "2"), urls.map { it.queryParameter("seed") })
        assertEquals(listOf("/api/v1/recommendations", "/api/v1/recommendations"), urls.map { it.encodedPath })
    }

}
