package media.qimeng.app.core.network

import kotlinx.coroutines.flow.Flow

/**
 * 服务端定位单点（ADR-0015 预留）：全 App 唯一知晓「服务端在哪 + 用什么 token」的地方。
 *
 * 任何模块禁止另行假设服务端位置（HANDOVER_APP §4.5）——单机形态（M6 服务端内嵌手机）
 * 只改这里指向 localhost，UI/Repository/ViewModel 零改动。
 *
 * 接口化而非直接暴露 DataStore 实现：①AuthInterceptor/AuthRepository 单测需要内存替身；
 * ②持久化载体（当前 DataStore Preferences）是实现细节，不该泄漏到依赖方。
 */
interface ServerConfigDataSource {

    /** 当前服务端 base URL；空串 = 从未配置（首次安装）。地址在退出登录后保留（「记忆上次」）。 */
    val serverUrl: Flow<String>

    /** 当前登录 token；null = 未登录。此流驱动壳层登录态分支与 401 跳登录。 */
    val token: Flow<String?>

    /**
     * 拦截器同步读 token（内存缓存，非阻塞）。
     * 为什么不用 Flow：OkHttp 拦截器运行在请求线程，DataStore 首读是磁盘 IO，
     * 阻塞等待会拖慢每个请求；缓存由实现方在写入时即时刷新 + 启动时从 DataStore 预热。
     */
    fun currentToken(): String?

    /**
     * 同步读当前服务端 base URL（业务 Repository 构造 SDK API 用）；null = 从未配置。
     * 与 [currentToken] 同一缓存机制：登录成功写穿 + 启动预热，业务请求零阻塞等待。
     */
    fun currentServerUrl(): String?

    /** 记住服务端地址（登录成功时调用；退出登录不清除——下次登录自动带出）。 */
    suspend fun updateServerUrl(url: String)

    /** 保存登录 token（登录成功时调用）。 */
    suspend fun updateToken(token: String)

    /** 清除 token（退出登录 / 401 失效时调用）；地址保留。 */
    suspend fun clearToken()
}
