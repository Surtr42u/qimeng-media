package media.qimeng.app.core.network

import kotlinx.coroutines.flow.Flow

/**
 * 默认登录端选项（2026-09-20 用户拍板）：用户在服务器设置页指定「下次登录预填哪个端」。
 * storageValue 是持久化字面量——枚举项改名不影响已落盘数据。
 */
enum class DefaultEndpoint(val storageValue: String) {
    /** 局域网 NAS（登录页预填 NAS 地址记忆槽）。 */
    NAS("nas"),

    /** 本机模式（登录页预填本机记忆地址，无记忆回退 [ServerAddress.LOCAL_MODE_PRESET]）。 */
    LOCAL("local"),

    ;

    companion object {
        /** 按持久化字面量还原；未知值（跨版本）按未设置处理。 */
        fun fromStorage(value: String): DefaultEndpoint? = entries.firstOrNull { it.storageValue == value }
    }
}

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

    /**
     * 最近一次成功登录的 NAS 地址记忆（任务S 批S3 服务器地址固化）。空串 = 从未有过。
     * 只记非本机模式地址；本机模式（127.0.0.1:18430）的切换/登录**不覆盖**本槽——
     * 从本机模式切回 NAS 时，设置页地址卡用它回填，用户免重输。
     */
    val rememberedNasUrl: Flow<String>

    /**
     * 最近一次成功登录的本机模式地址记忆（M6 单机形态口；可含自定义端口回环地址）。
     * 与 [rememberedNasUrl] 各归各槽：切换时互换回填（本机模式卡/登录页快捷填入取本值）。
     */
    val rememberedLocalUrl: Flow<String>

    /** 当前登录 token；null = 未登录。此流驱动壳层登录态分支与 401 跳登录。 */
    val token: Flow<String?>

    /**
     * 默认登录端选项（2026-09-20 用户拍板）：登录页地址预填跟随本选项；null = 未设置
     * （保持「记忆上次」行为）。语义仅「下次登录预填」——不改变当前已登录的连接。
     */
    val defaultEndpoint: Flow<DefaultEndpoint?>

    /** 写入默认登录端选项（服务器设置页单选项直写；null = 清除回到「记忆上次」）。 */
    suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?)

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

    /**
     * 成功登录时按地址类型分流记忆（批S3）：本机模式（[ServerAddress.isLocalModePreset]）
     * 进 [rememberedLocalUrl]，其余（局域网 NAS 等）进 [rememberedNasUrl]。
     * 只在登录成功路径（含 dev-login 免密链路）调用；[updateServerUrl] 的另一调用方
     * 「换址预置」（logoutWithStagedUrl）**不写记忆**——预置值未经登录确认，不算成功登录。
     */
    suspend fun rememberLoginAddress(url: String)

    /** 保存登录 token（登录成功时调用）。 */
    suspend fun updateToken(token: String)

    /** 清除 token（退出登录 / 401 失效时调用）；地址保留。 */
    suspend fun clearToken()
}
