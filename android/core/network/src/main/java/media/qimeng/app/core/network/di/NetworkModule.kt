package media.qimeng.app.core.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import media.qimeng.app.core.network.AuthApiFactory
import media.qimeng.app.core.network.AuthInterceptor
import media.qimeng.app.core.network.DataStoreServerConfigDataSource
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.SdkAuthApiFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** 应用级协程作用域：活得比任何界面久、子协程互不连坐（SupervisorJob）——401 清 token 等后台动作挂它。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

/** 上传通道专用 OkHttpClient 标记（AssetUploader 注入；其余注入点拿默认 10s 读超时客户端）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class UploadClient

/** 接口绑定（@Binds 必须在 abstract/interface 模块）。 */
@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindingModule {

    @Binds
    fun bindServerConfigDataSource(impl: DataStoreServerConfigDataSource): ServerConfigDataSource

    @Binds
    fun bindAuthApiFactory(impl: SdkAuthApiFactory): AuthApiFactory
}

/**
 * 网络层装配：OkHttp 单例（带 AuthInterceptor）+ ServerConfig 的 DataStore + 应用级协程作用域。
 * Now in Android 范式（ADR-0014）：装配只在本模块，业务模块只见接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * 上传通道专用客户端：从全局单例派生（共享 AuthInterceptor 与连接池等其余配置），只放大
     * 读超时、禁用总时限。为什么：大文件请求体发完后，服务端还要落盘 + 建库行（含媒体探测），
     * 全程可远超默认 10s 读超时——一旦读超时抛 SocketTimeout，WorkManager 会对整个文件重传
     * （此前大文件「上传完却报失败重传」的根因）。callTimeout=0 是 OkHttp 语义「不设总时限」：
     * 传输时长只由读/写超时管（对齐 CoilModule BUG-A 的 60s 先例口径）。
     */
    @Provides
    @Singleton
    @UploadClient
    fun provideUploadOkHttpClient(okHttpClient: OkHttpClient): OkHttpClient =
        okHttpClient.newBuilder()
            .readTimeout(UPLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    /** DataStore 单例：IO 专用作用域（DataStore 内部磁盘读写全挂它，NIA 同款装配）。 */
    @Provides
    @Singleton
    fun provideServerConfigDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        context.preferencesDataStoreFile(DATASTORE_FILE_NAME)
    }

    /** ServerConfig 持久化文件名（`server_config.preferences_pb` 落在 App 数据目录）。 */
    private const val DATASTORE_FILE_NAME = "server_config"

    /** 连接超时（秒）：NAS/局域网场景，10s（OkHttp 默认值）足够覆盖最慢的握手，不再放大。 */
    private const val CONNECT_TIMEOUT_SECONDS = 10L

    /** 读超时（秒）：覆盖登录/探活这类小响应足够；大流量通道单独设置（上传走 UploadClient 客户端、缩略图走 CoilModule）。 */
    private const val READ_TIMEOUT_SECONDS = 10L

    /** 写超时（秒）：与读对称。 */
    private const val WRITE_TIMEOUT_SECONDS = 10L

    /** 上传读超时（秒）：等的是「请求体发完→服务端落盘+建库行」这段无数据回传的窗口，60s 给足（见 provideUploadOkHttpClient 注释）。 */
    private const val UPLOAD_READ_TIMEOUT_SECONDS = 60L
}
