package media.qimeng.app.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [ServerConfigDataSource] 的内存替身（单测专用）：语义与 DataStore 实现对齐——
 * 写穿内存缓存（与实现类的 @Volatile 同步语义一致），token 流与地址流基于同一可变状态。
 */
class FakeServerConfigDataSource(
    initialServerUrl: String = "",
    initialToken: String? = null,
) : ServerConfigDataSource {

    private val serverUrlState = MutableStateFlow(initialServerUrl)
    private val tokenState = MutableStateFlow(initialToken)
    private val rememberedNasState = MutableStateFlow("")
    private val rememberedLocalState = MutableStateFlow("")
    private val defaultEndpointState = MutableStateFlow<DefaultEndpoint?>(null)

    /** 测试观察口：当前内存缓存（对应实现类的 currentToken 写穿缓存）。 */
    var cachedTokenForTest: String? = initialToken
        private set

    /** 批A：内嵌 dev 共享密钥内存槽（与 DataStore 实现同语义：纯内存、不持久化）。 */
    private var embeddedDevSecretState: String? = null

    override val serverUrl: Flow<String> = serverUrlState

    override val rememberedNasUrl: Flow<String> = rememberedNasState

    override val rememberedLocalUrl: Flow<String> = rememberedLocalState

    override val token: Flow<String?> = tokenState

    override val defaultEndpoint: Flow<DefaultEndpoint?> = defaultEndpointState

    override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) {
        defaultEndpointState.value = endpoint
    }

    override fun currentToken(): String? = cachedTokenForTest

    override fun currentServerUrl(): String? = serverUrlState.value.ifEmpty { null }

    override fun currentEmbeddedDevSecret(): String? = embeddedDevSecretState

    override fun updateEmbeddedDevSecret(value: String?) {
        embeddedDevSecretState = value
    }

    override suspend fun updateServerUrl(url: String) {
        serverUrlState.value = url
    }

    // 分流判定与 DataStore 实现同口径（同模块直用 ServerAddress，不跨模块复制逻辑）
    override suspend fun rememberLoginAddress(url: String) {
        if (url.isEmpty()) return
        if (ServerAddress.isLocalModePreset(url)) {
            rememberedLocalState.value = url
        } else {
            rememberedNasState.value = url
        }
    }

    override suspend fun updateToken(token: String) {
        tokenState.value = token
        cachedTokenForTest = token
    }

    override suspend fun clearToken() {
        tokenState.value = null
        cachedTokenForTest = null
    }
}

/** 测试便捷：token 流的非空布尔投影（与 AuthRepository.isLoggedIn 同口径，用于断言）。 */
val ServerConfigDataSource.isLoggedInForTest: Flow<Boolean>
    get() = token.map { it != null }
