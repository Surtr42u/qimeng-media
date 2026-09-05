package media.qimeng.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.models.AuthSetupRequest
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证流程所需的最小服务端操作端口（探活 + 密码登录）。
 *
 * 为什么包一层接口而不让 Repository 直接用 [DefaultApi]：AuthRepository 需要在「地址尚未持久化」
 * 的登录瞬间对用户输入的地址发起请求，且其单测需要对协议错误分类做替身——接口化后两件事都干净。
 * 后续批次的业务 Repository 直接经 SDK 工厂取 DefaultApi，不受本端口限制。
 */
interface AuthApi {

    /**
     * 连通性探测 `GET /api/v1/healthz`（协议面路径；根路径 /healthz 是运维别名不入协议面）。
     * @throws IOException 网络不通（地址不可达/连接拒绝）
     * @throws ClientException 探针返回 4xx（该地址不是绮梦服务端）
     */
    suspend fun probe()

    /**
     * 密码登录 `POST /auth/login`。
     * @return 新签发的 token（服务端单用户单 token：签发即重铸，旧 token 随之失效）
     * @throws ClientException statusCode=401 表示密码错误；其他 4xx 为请求侧问题
     * @throws IOException 网络不通
     */
    suspend fun login(password: String): String
}

/** [AuthApi] 的生成 SDK 实现（阻塞调用挪到 IO 线程——OkHttp 同步 execute 不许占主线程）。 */
class SdkAuthApi(private val api: DefaultApi) : AuthApi {

    override suspend fun probe() = withContext(Dispatchers.IO) { api.apiV1HealthzGet() }

    override suspend fun login(password: String): String = withContext(Dispatchers.IO) {
        // 生成物把 token 建模为可空（openapi 生成器对非 required 字段的默认）；协议 200 响应必带 token，
        // 缺失说明对端行为异常，按 IOException 抛出→上层归入「地址不通」而非空指针
        api.apiV1AuthLoginPost(AuthSetupRequest(password = password)).token
            ?: throw IOException("登录响应缺少 token")
    }
}

/** 按任意 base URL 构造 [AuthApi]（登录时地址来自用户输入、尚未持久化，故不能只依赖「当前地址」）。 */
interface AuthApiFactory {
    fun create(baseUrl: String): AuthApi
}

/** SDK 版工厂：OkHttp 客户端全 App 单例（AuthInterceptor 统一注入 Bearer——登录两端点免鉴权，注入无副作用）。 */
@Singleton
class SdkAuthApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : AuthApiFactory {
    override fun create(baseUrl: String): AuthApi = SdkAuthApi(DefaultApi(baseUrl, okHttpClient))
}
