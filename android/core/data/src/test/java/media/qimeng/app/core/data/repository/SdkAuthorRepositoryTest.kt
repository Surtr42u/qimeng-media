package media.qimeng.app.core.data.repository

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.ServerConfigDataSource
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SdkAuthorRepository 全链路单测（AuthRepositoryImplTest 同范式：真生成 SDK + fake 传输层
 * 拦截器——只替换 socket 层，DefaultApi/moshi 的真实序列化与错误分类全走）：
 * 锁定「服务端 4xx 原因文案从 HTTP 响应体到领域异常 [AssetAuthorsRejectedException]」
 * 这段链路，即编辑页「保存失败：<服务端原因>」透出的源头（对抗审查 P2 修复验证）。
 *
 * 机制事实（本测试依据；生成物禁手改）：ApiClient 对 4xx 构造 ClientError(message=HTTP
 * 状态行, body=响应体原串)，DefaultApi 把 ClientError 对象塞进 ClientException.response；
 * 仓库层从 response 取 body，交 UploadApiBodies.serverErrorMessage（上传路径同一实现）
 * 解析 Error{code,message}。
 */
class SdkAuthorRepositoryTest {

    /** 最近一次 PUT /assets/{id}/authors 的请求体原串（协议报文断言用） */
    private var lastAuthorsPutBody: String? = null

    /** 下一次响应状态码（默认 200=成功路径） */
    private var status = 200

    /** 下一次响应体（非 2xx 时即服务端 Error{code,message} 体） */
    private var body = SUCCESS_DETAIL_JSON

    /** 置非空则拦截器抛该异常（模拟断网/读超时） */
    private var transportError: IOException? = null

    private val repository = SdkAuthorRepository(
        BusinessApiFactory(InMemoryServerConfig(), transportClient()),
    )

    private fun transportClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                transportError?.let { throw it }
                val request = chain.request()
                if (request.method == "PUT" && request.url.encodedPath.endsWith("/authors")) {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    lastAuthorsPutBody = buffer.readUtf8()
                }
                testResponse(chain, status, body)
            },
        )
        .build()

    @Test
    fun `4xx响应体原因解析进领域异常且请求携带待新建名`() = runTest {
        status = 400
        // 服务端 400 体（协议 Error{code,message}，httpapi/author_edit.go 逐名校验文案形态）
        body = """{"code":"INVALID_PARAM","message":"newAuthorNames 第 1 项 \"[3D]\" 非法：规范化后无有效别名"}"""

        var thrown: AssetAuthorsRejectedException? = null
        try {
            repository.replaceAssetAuthors(ASSET_ID, emptyList(), listOf("[3D]"))
        } catch (e: AssetAuthorsRejectedException) {
            thrown = e
        }

        assertNotNull("4xx 必须领域化上抛（不得吞成通用异常）", thrown)
        // 原因文案 = 服务端 code+message（UploadApiBodies.serverErrorMessage 口径）
        assertEquals(
            "INVALID_PARAM newAuthorNames 第 1 项 \"[3D]\" 非法：规范化后无有效别名",
            thrown?.serverMessage,
        )
        assertEquals(400, thrown?.statusCode)
        // 请求侧同时锁定：待新建名确实进了 wire 报文（空 authorIds 显式为 []）
        assertTrue(
            "请求体应含 newAuthorNames",
            lastAuthorsPutBody.orEmpty().contains("\"newAuthorNames\":[\"[3D]\"]"),
        )
    }

    @Test
    fun `4xx非JSON响应体回落带状态码文案与网络失败可区分`() = runTest {
        status = 400
        body = "<html>bad request</html>"

        var thrown: AssetAuthorsRejectedException? = null
        try {
            repository.replaceAssetAuthors(ASSET_ID, emptyList(), listOf("全新作者"))
        } catch (e: AssetAuthorsRejectedException) {
            thrown = e
        }

        // 拿不到 body 也带状态码（绝不回落成"网络类"通用文案）
        assertEquals("服务端拒绝（HTTP 400）", thrown?.serverMessage)
        assertEquals(400, thrown?.statusCode)
    }

    @Test
    fun `成功路径原样返回且请求携带待新建名`() = runTest {
        status = 200
        body = SUCCESS_DETAIL_JSON

        repository.replaceAssetAuthors(ASSET_ID, listOf("a-1"), listOf("全新作者"))

        val sent = lastAuthorsPutBody.orEmpty()
        assertTrue("请求体应含既有作者 id 全集", sent.contains("\"authorIds\":[\"a-1\"]"))
        assertTrue("请求体应含待新建名", sent.contains("\"newAuthorNames\":[\"全新作者\"]"))
    }

    @Test
    fun `网络失败原样上抛不落4xx领域异常`() = runTest {
        transportError = IOException("connection reset")

        var thrown: Exception? = null
        try {
            repository.replaceAssetAuthors(ASSET_ID, emptyList(), listOf("全新作者"))
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("网络失败必须上抛（编辑页据此落通用文案可重试）", thrown)
        assertFalse("网络失败不得误报为名字非法类拒绝", thrown is AssetAuthorsRejectedException)
        assertTrue("保持 IOException 语义", thrown is IOException)
    }

    private companion object {
        /** fake 传输层不出网，URL 仅须合法（SDK 按此拼协议面路径；拦截器不关心端口） */
        const val FAKE_BASE_URL = "http://127.0.0.1:1"

        /** 协议路径参数为 UUID：用固定合成 UUID */
        const val ASSET_ID = "6f1e0b1c-0000-4000-8000-000000000000"

        /** 2xx 最小合法 AssetDetail（字段全可省；id 是 UUID 形态） */
        const val SUCCESS_DETAIL_JSON = """{"id":"6f1e0b1c-0000-4000-8000-000000000000","fileName":"a.jpg"}"""

        /** 合成 OkHttp 响应的最小构造（AuthRepositoryImplTest 同款） */
        fun testResponse(chain: Interceptor.Chain, status: Int, body: String): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    /** 测试侧内存替身（测试代码不跨模块共享源码——AuthRepositoryImplTest 同款约定），
     *  仅 currentServerUrl 被生产代码消费，其余为接口完整性空实现。 */
    private class InMemoryServerConfig : ServerConfigDataSource {
        private val url = MutableStateFlow(FAKE_BASE_URL)
        private val tokenState = MutableStateFlow<String?>(null)
        private val rememberedNas = MutableStateFlow("")
        private val rememberedLocal = MutableStateFlow("")
        private val defaultEndpointState = MutableStateFlow<DefaultEndpoint?>(null)
        override val serverUrl: Flow<String> = url
        override val rememberedNasUrl: Flow<String> = rememberedNas
        override val rememberedLocalUrl: Flow<String> = rememberedLocal
        override val token: Flow<String?> = tokenState
        override val defaultEndpoint: Flow<DefaultEndpoint?> = defaultEndpointState
        override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) { defaultEndpointState.value = endpoint }
        override fun currentToken(): String? = tokenState.value
        override fun currentServerUrl(): String? = url.value.ifEmpty { null }
        override fun currentEmbeddedDevSecret(): String? = null
        override fun updateEmbeddedDevSecret(value: String?) = Unit
        override suspend fun updateServerUrl(url: String) { this.url.value = url }
        override suspend fun rememberLoginAddress(url: String) = Unit
        override suspend fun updateToken(token: String) { tokenState.value = token }
        override suspend fun clearToken() { tokenState.value = null }
    }
}
