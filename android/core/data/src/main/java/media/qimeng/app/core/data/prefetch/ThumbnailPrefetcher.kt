package media.qimeng.app.core.data.prefetch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerReadinessProbe
import media.qimeng.app.core.network.di.ApplicationScope

/** 预取轮状态（缩略图缓存页「预取」区块渲染源；done/total 按「本轮已处理/本轮总数」计）。 */
sealed interface PrefetchUiState {

    /** 空闲（未开始，或上一轮被登出/手动停止复位） */
    data object Idle : PrefetchUiState

    /** 进行中：本轮已处理 done / 本轮总数 total */
    data class Running(val done: Int, val total: Int) : PrefetchUiState

    /** 自动触发遇计费网络（且非本机回环地址）：挂起等待网络变化后自动继续 */
    data object WaitingNetwork : PrefetchUiState

    /** 本轮完成（done=total=本轮处理的缩略图数；空库为 0/0） */
    data class Done(val done: Int, val total: Int) : PrefetchUiState

    /** 失败（[reason] 为中文前缀 + 简要原因，供界面直显） */
    data class Failed(val reason: String) : PrefetchUiState
}

/**
 * 预取状态观察端口（2026-09-19 批S4）：预取自本批起是**纯默认自动行为**（登录后
 * 自动一轮，无手动启停——用户拍板「不需要手动按钮」），外界只读状态、无法干预；
 * 缩略图缓存页经本端口透出进度。独立成接口：VM 只依赖只读面（铁律 7 + 可测性），
 * 不持有预取器的控制柄。
 */
interface ThumbnailPrefetchMonitor {

    /** 预取轮状态流（只读） */
    val state: StateFlow<PrefetchUiState>
}

/**
 * 全库缩略图预取器（2026-09-18 用户需求）：登录服务端后自动把全部 md 缩略图
 * 预取进本机 Coil 磁盘缓存，此后浏览网格直接读盘不再反复下载。
 * 2026-09-19 批S4 拍板：预取是默认自动行为，手动「开始预取/停止」按钮与 startManual/
 * stopRound 手动链路删除——自动触发（登录回放/登录事件）是唯一入口。
 *
 * **为什么磁盘键天然一致**：预取请求的 model 就是首页网格同一来源的绝对直链
 * （列表接口直出 → SdkMappers.absolutize），全局 ImageLoader 装配的
 * SignedMediaUriKeyer / SignedMediaDiskKeyInterceptor 会自动剥掉随响应轮换的
 * exp/sig 做稳定键——预取与浏览命中同一条磁盘缓存，无需本类自己拼键。
 *
 * **Coil 官方文档依据（铁律 8，3.6.x）**：
 * - API 文档 ImageLoader（coil-kt.github.io/coil/api/coil-core/coil3/-image-loader/）：
 *   `enqueue(request)` 把请求排入异步执行、返回 [coil3.request.Disposable]；
 *   `execute(request)` 是挂起函数、「Execute the request **in the current coroutine
 *   scope**」。两者对无 target 请求的语义一致：取回 → 解码 → 写内存/磁盘缓存、
 *   不渲染任何 UI（即 preload）。本类选 `execute`：请求运行在调用方协程上，
 *   登出/手动停止取消本协程时连在途下载一起取消；`enqueue` 跑在 ImageLoader
 *   内部作用域、脱离调用方 Job，逐个跟踪 Disposable 才能停干净，得不偿失。
 * - `ImageRequest.Builder.memoryCachePolicy(CachePolicy)`（同站 API 文档）：
 *   预取置 `CachePolicy.DISABLED`——预取产物只求落盘，解码位不进内存缓存，
 *   免得把用户正在浏览的内存位挤掉（LRU 互踩）。
 * - Recipes 页（coil-kt.github.io/coil/recipes/）「Then enqueue/execute the request
 *   like normal」口径：无 target 请求就是普通请求的执行，无专用 preload API。
 *
 * **有意简化（不做断点游标持久化）**：预取进度不落盘。Coil 磁盘缓存命中项重跑
 * 时不走网络（本地读盘），进程重启后再跑一轮只是重复读盘 + 补新资产增量，代价
 * 可忽略——为省这点读盘把游标持久化进 DataStore 复杂度不划算。
 */
@Singleton
class ThumbnailPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
    private val readinessProbe: ServerReadinessProbe,
    @ApplicationScope private val appScope: CoroutineScope,
) : ThumbnailPrefetchMonitor {

    private val _state = MutableStateFlow<PrefetchUiState>(PrefetchUiState.Idle)

    /** 预取轮状态流（缩略图缓存页收集渲染；[ThumbnailPrefetchMonitor] 只读面实现） */
    override val state: StateFlow<PrefetchUiState> = _state.asStateFlow()

    /** 启停转换互斥（防「登出取消」与「手动开始」竞态开出两轮） */
    private val transitionMutex = Mutex()

    /** 进行中的一轮；null = 空闲。单飞语义：同一时刻至多一轮。 */
    private var roundJob: Job? = null

    /**
     * App 启动接线（QimengApplication.onCreate 调一次，EventSyncBootstrapper 同款模式）：
     * 观察登录态——冷启动已登录由 StateFlow 回放立即触发；false→true 登录后自动跑一轮；
     * true→false 登出取消进行中的一轮并复位空闲。这是预取唯一的触发/终止入口
     * （2026-09-19 批S4：手动按钮删除，预取为纯默认自动行为）。
     */
    fun onAppCreate() {
        appScope.launch {
            authRepository.isLoggedIn.collect { loggedIn ->
                transitionMutex.withLock {
                    if (loggedIn) startRoundLocked() else cancelRoundLocked()
                }
            }
        }
    }

    /** 持锁调用。单飞：已有一轮在跑则复用不叠加（与 ServerReadinessProbe 同款裁量）。 */
    private fun startRoundLocked() {
        if (roundJob?.isActive == true) return
        roundJob = appScope.launch { runRound() }
    }

    /** 持锁调用。只取消不改状态——取消后的复位统一在 [runRound] 的取消收口处做，单点管理。 */
    private fun cancelRoundLocked() {
        roundJob?.cancel()
        roundJob = null
    }

    private suspend fun runRound() {
        try {
            _state.value = PrefetchUiState.Running(done = 0, total = 0)
            // 服务端就绪探针：返回 false 也继续——让首个列表请求自己失败报错，
            // 与首页「探针超时走既有失败路径」语义一致（ServerReadinessProbe KDoc 口径）
            readinessProbe.awaitReady()
            awaitUsableNetworkForAutoStart()
            // 与首页网格完全同源的 md 缩略图绝对 URL（全量分页在 repository 内做）
            val urls = mediaRepository.allThumbUrls()
            val total = urls.size
            if (total == 0) {
                _state.value = PrefetchUiState.Done(done = 0, total = 0)
                return
            }
            // Semaphore 限并发（官方并发原语）：全量 URL 各起一个子协程排队取许可，
            // 同时刻至多 PREFETCH_CONCURRENCY 条在途；不做逐条人为延时
            val throttle = Semaphore(PREFETCH_CONCURRENCY)
            val processed = AtomicInteger(0)
            coroutineScope {
                urls.forEach { url ->
                    launch {
                        throttle.withPermit {
                            prefetchOne(url)
                            // 进度口径：按「本轮已处理」计（成功失败都算处理过，不精确去重）
                            _state.value = PrefetchUiState.Running(
                                done = processed.incrementAndGet(),
                                total = total,
                            )
                        }
                    }
                }
            }
            _state.value = PrefetchUiState.Done(done = total, total = total)
        } catch (e: CancellationException) {
            // 登出取消：复位空闲（下轮由下次登录重新触发）
            _state.value = PrefetchUiState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = PrefetchUiState.Failed(reason = describeFailure(e))
        }
    }

    /**
     * 单条预取：无 target 请求走全局 ImageLoader（文档依据见类 KDoc）。
     * execute 失败返回 ErrorResult 而不抛异常——单图失败只计入「已处理」，
     * 不中断整轮（与浏览路径的失败降级口径一致）。
     */
    private suspend fun prefetchOne(url: String) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        imageLoader.execute(request)
    }

    /**
     * 计费网络门：计费网络 + 当前地址非本机回环预设（回环=免费流量）
     * → 状态置「等待非计费网络」并挂起，直到网络变化后复查通过才继续。
     * （2026-09-19 批S4 后预取只有自动触发，本门恒生效——原「手动触发可绕过」
     * 的 startManual 链路已删除。）
     * 每次复查重读 serverUrl——等待期间用户登出会直接取消整轮，不会死等。
     */
    private suspend fun awaitUsableNetworkForAutoStart() {
        var baseUrl = authRepository.serverUrl.first()
        while (isMeteredNonLocal(baseUrl)) {
            _state.value = PrefetchUiState.WaitingNetwork
            awaitNetworkChange()
            baseUrl = authRepository.serverUrl.first()
        }
    }

    /** 拦截条件：当前活跃网络计费 且 服务端地址不是本机回环预设（18430 回环不耗外部流量）。 */
    private fun isMeteredNonLocal(baseUrl: String): Boolean =
        connectivityManager()?.isActiveNetworkMetered == true &&
            !ServerAddress.isLocalModePreset(baseUrl)

    private fun connectivityManager(): ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * 挂起等待网络状态变化（事件驱动，不轮询刷系统服务）：注册默认网络回调，
     * 任一次能力变化即放行，由调用方 while 循环复查门槛。回调注册后会先收到
     * 当前网络的一次能力回报（API 26+ registerDefaultNetworkCallback 语义），
     * 不存在「注册前刚好切网导致永久错过」的窗口。
     */
    private suspend fun awaitNetworkChange() {
        val cm = connectivityManager()
        if (cm == null) {
            // ConnectivityManager 不可得（非标准环境）的兜底：低频轮询交回主循环复查，
            // 防止 while 循环零间隔忙转。真机 Android 上该服务恒存在，此分支仅防御。
            delay(NETWORK_POLL_FALLBACK_MS)
            return
        }
        suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            continuation.invokeOnCancellation {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }

    /** 失败原因（中文前缀 + 简要细节；细节可能含服务端报错原文，只进状态流供界面直显）。 */
    private fun describeFailure(e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "预取失败：$detail"
    }

    private companion object {
        /**
         * 预取并发上限：4 条在途下载对局域网 NAS 足够打满带宽又不至于挤占
         * 用户正在进行的浏览请求（Coil 网络层 OkHttp 各自建连，无全局队头阻塞）。
         */
        const val PREFETCH_CONCURRENCY = 4

        /** ConnectivityManager 不可得时的兜底轮询周期（毫秒）：低频复查防忙转，真机不会走到。 */
        const val NETWORK_POLL_FALLBACK_MS = 30_000L
    }
}
