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
 * 认证流程所需的最小服务端操作端口（探活 + 登录 + 登出）。
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
     * @return 新签发的 token（ADR-0021 多设备并发会话：每次登录签发一条独立会话，
     * 既有会话不失效——本设备登录不挤掉其他设备；吊销单条会话走 [logout]）
     * @throws ClientException statusCode=401 表示密码错误；其他 4xx 为请求侧问题
     * @throws IOException 网络不通
     */
    suspend fun login(password: String): String

    /**
     * 开发模式免密登录 `POST /auth/dev-login`（2026-09-06 用户拍板：测试环境免输密码，
     * 消除模拟器验证时人工敲密码的摩擦）。仅服务端开启 auth_dev_mode 时可用——
     * 未开启时服务端恒 404 且不泄露信息（server auth_dev_test 锁定），生产/远程部署不受影响。
     * 实现侧自动带头 `X-Qimeng-Dev-Secret`（2026-09-30 批A，防同机越权）：值来自
     * [AuthApiFactory] 装配时给的密钥供给函数（内嵌形态=EmbeddedServerService 拉起
     * 子进程时生成注入的共享密钥）；返回 null 时等效不带头——连 NAS dev 服务器无密钥
     * 配置，服务端不校验，行为与批A 之前完全一致。
     * @throws ClientException statusCode=404 表示服务端未开启免密模式；401 表示密钥不匹配
     * @throws IOException 网络不通
     */
    suspend fun devLogin(): String

    /**
     * 登出并吊销当前会话 `POST /auth/logout`（ADR-0021 多设备并发会话）：只吊销请求所携带
     * Bearer token 对应的单条 auth_sessions 会话，其他设备不受影响。幂等 204——token 已
     * 吊销/过期时请求会被鉴权中间件以 401 拦下（表现见 @throws），走到端点即恒 204。
     * @throws ClientException statusCode=401 表示本会话 token 已失效（无会话可吊销，调用方可忽略）
     * @throws ServerException 服务端 5xx
     * @throws IOException 网络不通
     */
    suspend fun logout()
}

/**
 * [AuthApi] 的生成 SDK 实现（阻塞调用挪到 IO 线程——OkHttp 同步 execute 不许占主线程）。
 *
 * @param devSecretProvider devLogin 时的共享密钥供给（2026-09-30 批A）：函数而非直值，
 *        因为密钥随子进程生命周期每次拉起都可能换——调用瞬间现取，永不过期引用。
 *        返回 null = 不带头（NAS dev 服务器无密钥配置场景）。
 */
class SdkAuthApi(
    private val api: DefaultApi,
    private val devSecretProvider: () -> String?,
) : AuthApi {

    override suspend fun probe() = withContext(Dispatchers.IO) { api.apiV1HealthzGet() }

    override suspend fun login(password: String): String = withContext(Dispatchers.IO) {
        // 生成物把 token 建模为可空（openapi 生成器对非 required 字段的默认）；协议 200 响应必带 token，
        // 缺失说明对端行为异常，按 IOException 抛出→上层归入「地址不通」而非空指针
        api.apiV1AuthLoginPost(AuthSetupRequest(password = password)).token
            ?: throw IOException("登录响应缺少 token")
    }

    override suspend fun devLogin(): String = withContext(Dispatchers.IO) {
        // 密钥在调用瞬间现取：内嵌形态带出与子进程环境变量同源的一次性值（批A）；
        // null 等价旧行为（服务端未配置密钥时该头本就不校验）
        api.apiV1AuthDevLoginPost(devSecretProvider()).token ?: throw IOException("登录响应缺少 token")
    }

    override suspend fun logout() = withContext(Dispatchers.IO) { api.apiV1AuthLogoutPost() }
}

/** 按任意 base URL 构造 [AuthApi]（登录时地址来自用户输入、尚未持久化，故不能只依赖「当前地址」）。 */
interface AuthApiFactory {
    fun create(baseUrl: String): AuthApi
}

/**
 * SDK 版工厂：OkHttp 客户端全 App 单例（AuthInterceptor 统一注入 Bearer——登录两端点免鉴权，注入无副作用）。
 * ServerConfigDataSource 同为单例（2026-09-30 批A）：create 时闭包住内存槽读取——devLogin
 * 的密钥随子进程生命周期现取，工厂不需要感知密钥本身。
 */
@Singleton
class SdkAuthApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val serverConfigDataSource: ServerConfigDataSource,
) : AuthApiFactory {
    override fun create(baseUrl: String): AuthApi =
        SdkAuthApi(DefaultApi(baseUrl, okHttpClient)) { serverConfigDataSource.currentEmbeddedDevSecret() }
}
