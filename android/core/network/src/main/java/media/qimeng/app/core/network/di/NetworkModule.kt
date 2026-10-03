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
import media.qimeng.app.core.network.AuthApi
import media.qimeng.app.core.network.AuthApiFactory
import media.qimeng.app.core.network.AuthInterceptor
import media.qimeng.app.core.network.DataStoreServerConfigDataSource
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.SdkAuthApi
import media.qimeng.app.core.network.SdkAuthApiFactory
import media.qimeng.sdk.apis.DefaultApi
import okhttp3.OkHttpClient
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
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

/** 备份通道专用 OkHttpClient 标记（SdkBackupRepository 注入；导出/导入跨端备份走此客户端）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class BackupClient

/** SSE 长流通道专用 OkHttpClient 标记（EventSource.Factory 生产绑定注入；ADR-0029 事件消费走此客户端）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SseClient

/**
 * 本机直连通道专用标记（ADR-0034 词表同步）：本标记下的 OkHttpClient / DefaultApi / AuthApi
 * 全部只指向内嵌服务端预设地址（[ServerAddress.LOCAL_MODE_PRESET] = 127.0.0.1:18430），
 * 供 core:data VocabularySyncRepository 注入。与全局客户端的本质区别见
 * [NetworkModule.provideLocalDirectOkHttpClient]——**不挂 AuthInterceptor**。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LocalDirectClient

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
            .callTimeout(CALL_TIMEOUT_DISABLED, TimeUnit.MILLISECONDS)
            .build()

    /**
     * 备份通道专用客户端：同样从全局单例派生（newBuilder 共享 AuthInterceptor 与连接池，
     * 鉴权不丢），只放大读超时、禁用总时限。为什么：备份导出/导入是同步长处理——导入
     * POST /import/qimeng-backup 在服务端逐条幂等合并（真库 6341 资产在手机端是分钟级），
     * 导出 GET /export/qimeng-backup 大库同为长响应，主客户端 10s 读超时必掐断（2026-09-19
     * 用户手机实测跨端导入失败的根因，与上传通道「上传完却报失败重传」同款、当时只修了
     * 上传）。callTimeout=0 是 OkHttp 语义「不设总时限」，时长只由读/写超时管——
     * UploadClient 60s 先例的加长版。
     */
    @Provides
    @Singleton
    @BackupClient
    fun provideBackupOkHttpClient(okHttpClient: OkHttpClient): OkHttpClient =
        okHttpClient.newBuilder()
            .readTimeout(BACKUP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_DISABLED, TimeUnit.MILLISECONDS)
            .build()

    /**
     * SSE 长流通道专用客户端（ADR-0029，2026-10-01）：同样从全局单例派生（newBuilder 共享
     * AuthInterceptor 与连接池——**token 注入零第二份逻辑**，SSE 请求鉴权与业务请求同一条
     * 拦截器链），只禁用读超时。为什么：SSE 是服务端持续推流的长响应，保活靠服务端心跳帧
     * （server/internal/events/sse.go DefaultHeartbeat=15s，注释改动须双向同步），主客户端
     * 10s 读超时会周期性把空闲等待掐成 SocketTimeout（永远活不过两个心跳）；读超时置 0 =
     * OkHttp 语义「不设超时」，断线检测交给 TCP 层错误（对端断开/网络切换即 onFailure）。
     * callTimeout 不另设：RealEventSource 连接成功后本就取消 call 超时（okhttp-sse 官方行为，
     * sources jar 实读确认），且总时限对长流语义同样错误。写超时继承不动：GET 无请求体。
     */
    @Provides
    @Singleton
    @SseClient
    fun provideSseOkHttpClient(okHttpClient: OkHttpClient): OkHttpClient =
        okHttpClient.newBuilder()
            .readTimeout(CALL_TIMEOUT_DISABLED, TimeUnit.MILLISECONDS)
            .build()

    /**
     * okhttp-sse 事件源工厂单例：绑定 [provideSseOkHttpClient] 的长流客户端（官方装配姿势，
     * EventSources.createFactory；工厂自动补 `Accept: text/event-stream` 头）。消费方
     * （core:data ServerEventConsumer）只注入本工厂，不持有 OkHttpClient——通道配置收口
     * 在网络层，与 UploadClient/BackupClient 同款装配边界。
     */
    @Provides
    @Singleton
    fun provideEventSourceFactory(@SseClient okHttpClient: OkHttpClient): EventSource.Factory =
        EventSources.createFactory(okHttpClient)

    /**
     * 本机直连通道专用客户端（ADR-0034 词表同步，2026-10-04）：**全新 builder、零拦截器**，
     * 只指向内嵌服务端预设地址。为什么不能像 UploadClient/BackupClient 那样 newBuilder 从
     * 全局单例派生：派生会共享 AuthInterceptor，而词表同步要对 127.0.0.1:18430 发
     * dev-login/GET/PUT 三类请求——本地 token 与全局 ServerConfig 里的远端 NAS token 是
     * 两套会话，若本地请求收到 401 会触发 AuthInterceptor 的 clearToken+登出广播，把用户
     * 正在用的 NAS 会话踢回登录页（任务书点名的最大陷阱）。独立无拦截器客户端从结构上
     * 杜绝该通道触碰全局会话；本地 Bearer 经 SDK 生成物的实例级 accessTokenProvider 注入
     * （ApiClient 每实例一个 provider，不共享全局静态字段），装配点在消费方
     * core:data VocabularySyncRepository（dev-login 换得 token 后随即接线）。
     * 超时与全局主客户端同档：回环毫秒级往返，10s 绰绰有余，不为专用通道另立档位。
     */
    @Provides
    @Singleton
    @LocalDirectClient
    fun provideLocalDirectOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * 本机直连 DefaultApi：固定 [ServerAddress.LOCAL_MODE_PRESET] 地址 + 无拦截器客户端。
     * 地址写死预设是安全的：内嵌服务端只监听回环 18430（EmbeddedServerConfig.LISTEN_ADDRESS
     * 端口单值互指），本 API 的唯一消费方（词表同步）也只对该端操作；自定义端口回环地址
     * （自带 Termux 形态 A）不在此通道语义内。单例的 token 状态经实例级
     * accessTokenProvider 承载（provider 函数捕获 dev-login 换得的本地 token），无并发写。
     */
    @Provides
    @Singleton
    @LocalDirectClient
    fun provideLocalDirectDefaultApi(@LocalDirectClient okHttpClient: OkHttpClient): DefaultApi =
        DefaultApi(ServerAddress.LOCAL_MODE_PRESET, okHttpClient)

    /**
     * 本机直连 dev-login 端口：SdkAuthApi（AuthApi 实现）包装本机 DefaultApi，密钥供给
     * 闭包读内存槽（[ServerConfigDataSource.currentEmbeddedDevSecret]，EmbeddedServerService
     * 每次拉起子进程时覆写）——与既有 SdkAuthApiFactory 同一装配姿势，密钥调用瞬间现取，
     * 永不过期引用。注意这是独立于全局登录态的第二条认证链：换得的本地 token 只进
     * 本机 DefaultApi 的 accessTokenProvider，绝不写 ServerConfigDataSource（NAS 会话无感）。
     */
    @Provides
    @Singleton
    @LocalDirectClient
    fun provideLocalDirectAuthApi(
        @LocalDirectClient api: DefaultApi,
        serverConfigDataSource: ServerConfigDataSource,
    ): AuthApi = SdkAuthApi(api) { serverConfigDataSource.currentEmbeddedDevSecret() }

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

    /** 备份读超时（秒）：导入是服务端同步长处理（逐条幂等合并，6341 资产手机端分钟级）、导出大库同为长响应，10s 必超时——300s 给足（见 provideBackupOkHttpClient 注释）。 */
    private const val BACKUP_READ_TIMEOUT_SECONDS = 300L

    /** 总时限禁用值（毫秒）：OkHttp 语义 callTimeout=0 =「不设总时限」，时长只由读/写超时管。上传/备份两条长通道共用（卫生约束 5：0 在此并非自明值，CoilModule NETWORK_CALL_TIMEOUT_DISABLED 同款口径）。 */
    private const val CALL_TIMEOUT_DISABLED = 0L
}
