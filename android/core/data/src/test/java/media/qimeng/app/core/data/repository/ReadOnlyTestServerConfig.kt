package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.ServerConfigDataSource

internal class ReadOnlyTestServerConfig : ServerConfigDataSource {
        override val serverUrl = MutableStateFlow("http://qimeng.test")
        override val rememberedNasUrl = MutableStateFlow("")
        override val rememberedLocalUrl = MutableStateFlow("")
        override val token = MutableStateFlow<String?>(null)
        override val defaultEndpoint = MutableStateFlow<DefaultEndpoint?>(null)
        override fun currentServerUrl(): String = serverUrl.value
        override fun currentToken(): String? = token.value
        override fun currentEmbeddedDevSecret(): String? = null
        override fun updateEmbeddedDevSecret(value: String?) = Unit
        override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) = Unit
        override suspend fun updateServerUrl(url: String) = Unit
        override suspend fun rememberLoginAddress(url: String) = Unit
        override suspend fun updateToken(token: String) = Unit
        override suspend fun clearToken() = Unit
    }
