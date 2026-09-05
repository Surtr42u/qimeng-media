package media.qimeng.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import media.qimeng.app.core.network.di.ApplicationScope
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 鉴权拦截器：为全部请求注入 `Authorization: Bearer <token>`；收到 401 清 token 并广播事件。
 *
 * 冻结口径（HANDOVER_APP M4-1）：
 * - 401 处理 = 清 token + 发事件，**禁止直接导航**——拦截器在 OkHttp 请求线程，导航属 UI 关注点，
 *   由 :app 壳层收集 [SessionEventBus.unauthorized] 与 token 状态流切换登录页；
 * - token 注入只看 [ServerConfigDataSource.currentToken]（内存缓存），不在请求线程做磁盘 IO。
 *
 * 探活/登录两个免鉴权端点经过本拦截器时 token 可能为 null 或仍在——服务端对 security: [] 端点
 * 忽略该头，无副作用，无需按 URL 白名单跳过注入。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val sessionEvents: SessionEventBus,
    @ApplicationScope private val appScope: CoroutineScope,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = serverConfig.currentToken()
            ?.let { original.newBuilder().header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$it").build() }
            ?: original
        val response = chain.proceed(request)
        if (response.code == HTTP_UNAUTHORIZED) {
            onTokenRejected()
        }
        return response
    }

    /** 401 处理：清 token（挂起操作放到应用级协程）+ 广播事件；重复 401 幂等（清空/发事件都无害）。 */
    private fun onTokenRejected() {
        appScope.launch { serverConfig.clearToken() }
        sessionEvents.notifyUnauthorized()
    }

    private companion object {
        /** 协议全局鉴权头（openapi.yaml components.securitySchemes bearerAuth, scheme: http bearer）。 */
        const val HEADER_AUTHORIZATION = "Authorization"

        /** Bearer 方案前缀（协议侧 bearerFormat "QM token"；改动须与 openapi.yaml 同步，反之亦然）。 */
        const val BEARER_PREFIX = "Bearer "

        /** 未认证状态码（openapi.yaml components.responses.Unauthorized）。 */
        const val HTTP_UNAUTHORIZED = 401
    }
}
