package media.qimeng.app.core.network.di

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 备份通道客户端派生配置锁定（任务R 2026-09-19）：跨端导入在服务端是分钟级同步长处理，
 * 主客户端 10s 读超时必掐断（用户手机实测失败的根因）——锁 readTimeout=300s、callTimeout=0
 * （UploadClient 60s 先例的加长版），并锁派生客户端共享主客户端拦截器（AuthInterceptor
 * 经 newBuilder 保留，备份请求鉴权不能丢）。直接调 @Provides 工厂函数断言产物配置
 * （装配无状态，无需 Hilt 运行时）。
 */
class NetworkModuleBackupClientTest {

    /** 带标记拦截器的主客户端替身（模拟 NetworkModule.provideOkHttpClient 的产物形态） */
    private fun newBaseClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request()) }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun `备份客户端读超时300秒且不设总时限`() {
        val client = NetworkModule.provideBackupOkHttpClient(newBaseClient())
        // OkHttp 5：超时读取是 val 属性（毫秒）
        assertEquals(TimeUnit.SECONDS.toMillis(300).toInt(), client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        // 连接/写超时随主客户端派生不放大（长响应瓶颈在读等待段）
        assertEquals(TimeUnit.SECONDS.toMillis(10).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(10).toInt(), client.writeTimeoutMillis)
    }

    @Test
    fun `备份客户端共享主客户端拦截器`() {
        val base = newBaseClient()
        val client = NetworkModule.provideBackupOkHttpClient(base)
        // AuthInterceptor 由 newBuilder 保留：主客户端的每个拦截器都在派生客户端里
        assertTrue(base.interceptors.isNotEmpty())
        base.interceptors.forEach { interceptor ->
            assertTrue(client.interceptors.any { it === interceptor || it.javaClass == interceptor.javaClass })
        }
        assertSame(base.dispatcher.executorService, client.dispatcher.executorService)
    }
}
