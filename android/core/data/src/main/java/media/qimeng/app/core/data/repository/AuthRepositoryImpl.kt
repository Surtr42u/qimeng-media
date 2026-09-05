package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import media.qimeng.app.core.network.AuthApiFactory
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.SessionEventBus
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AuthRepository] 实现：编排探活→登录→持久化三步；不持有任何服务端地址假设
 * （地址或来自用户输入、或来自 ServerConfigDataSource，绝无第二处常量）。
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val authApiFactory: AuthApiFactory,
    sessionEventBus: SessionEventBus,
) : AuthRepository {

    override val serverUrl: Flow<String> = serverConfig.serverUrl

    override val isLoggedIn: Flow<Boolean> = serverConfig.token
        .map { it != null }
        .distinctUntilChanged()

    override val unauthorizedEvents: Flow<Unit> = sessionEventBus.unauthorized

    override suspend fun login(rawAddress: String, password: String): LoginResult {
        val baseUrl = ServerAddress.normalize(rawAddress)
            ?: return LoginResult.Failure(LoginError.InvalidAddress)
        val api = authApiFactory.create(baseUrl)

        // 第一步：探活（协议面路径 /api/v1/healthz）。不通即「地址不通」，不把网络问题误报成密码错
        try {
            api.probe()
        } catch (e: IOException) {
            return LoginResult.Failure(LoginError.ServerUnreachable)
        } catch (e: ClientException) {
            // 探针端点免鉴权且恒 200，收到 4xx 说明对端不是绮梦服务端——对用户而言等同地址不通
            return LoginResult.Failure(LoginError.ServerUnreachable)
        }

        // 第二步：密码登录；成功后先写地址再写 token（壳层由 token 流驱动跳壳，两键都落盘才算完成）
        return try {
            val token = api.login(password)
            serverConfig.updateServerUrl(baseUrl)
            serverConfig.updateToken(token)
            LoginResult.Success
        } catch (e: IOException) {
            LoginResult.Failure(LoginError.ServerUnreachable)
        } catch (e: ClientException) {
            when (e.statusCode) {
                HTTP_UNAUTHORIZED -> LoginResult.Failure(LoginError.WrongPassword)
                else -> LoginResult.Failure(LoginError.Other("login client error ${e.statusCode}"))
            }
        } catch (e: ServerException) {
            LoginResult.Failure(LoginError.Other("login server error ${e.statusCode}"))
        }
    }

    override suspend fun logout() = serverConfig.clearToken()

    private companion object {
        /** 未认证状态码（openapi.yaml components.responses.Unauthorized；协议侧改动须同步）。 */
        const val HTTP_UNAUTHORIZED = 401
    }
}
