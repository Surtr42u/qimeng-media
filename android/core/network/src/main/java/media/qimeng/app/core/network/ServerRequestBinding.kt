package media.qimeng.app.core.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** 请求携带入队服务器身份，不持久化 token；AuthInterceptor 在发出前核实地址。 */
data class ServerRequestBinding(val baseUrl: String) {
    fun matches(current: String?): Boolean =
        baseUrl.toHttpUrlOrNull()?.let { it == current?.toHttpUrlOrNull() } == true

    fun contains(url: HttpUrl): Boolean {
        val base = baseUrl.toHttpUrlOrNull() ?: return false
        return base.scheme == url.scheme && base.host == url.host && base.port == url.port &&
            url.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/")
    }

    fun requireCurrent(current: String?, url: HttpUrl) {
        if (!matches(current) || !contains(url)) throw IOException(TARGET_CHANGED_MESSAGE)
    }

    companion object {
        const val TARGET_CHANGED_MESSAGE = "上传目标服务器已切换，请回到原服务器或重新选择目标后入队"
    }
}

/** 标记必须在 AuthInterceptor 之前加入；禁止重定向把请求体带向其他服务器。 */
fun OkHttpClient.boundToServer(baseUrl: String): OkHttpClient = newBuilder().apply {
    interceptors().add(0, Interceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .tag(ServerRequestBinding::class.java, ServerRequestBinding(baseUrl)).build())
    })
    followRedirects(false)
    followSslRedirects(false)
}.build()
