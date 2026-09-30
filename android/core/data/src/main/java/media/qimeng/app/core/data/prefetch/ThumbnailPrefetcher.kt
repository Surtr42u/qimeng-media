package media.qimeng.app.core.data.prefetch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
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
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.data.coil.LOG_TAG
import media.qimeng.app.core.data.coil.SplitDiskCache
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.LibraryRevisionRepository
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

    /**
     * 库修订号未变：整轮跳过（2026-09-30 revision 跳过批）——未拉全量列表也未下载，
     * 缓存已是最新。独立终态不复用 Done(0,0)：那是「空库轮」的既有语义，混叠会让
     * 收集者无法区分「没东西可预取」与「无需预取」。
     */
    data object Skipped : PrefetchUiState

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
 * 预取避让端口（问题B 下拉刷新响应慢修复，2026-09-28）：列表页下拉刷新开始时调用
 * [pauseForForegroundRefresh]，预取循环在避让窗口内暂停下载、把带宽让给刷新请求
 * （登录后全库预取 4 并发与用户正在等的刷新首屏抢同一 NAS 带宽，是刷新慢的叠加因素）。
 *
 * 为什么是「截止时间」语义而非 pause/resume 配对：刷新可能在代际乱序中被丢弃、VM 也可能在
 * 在途时被销毁，「开始置 true / 结束置 false」的配对在这些路径上无法可靠闭环——一旦漏掉
 * 一次置 false，预取就永久饿死。改为每次调用续一个有界窗口（见实现 KDoc），窗口自愈过期，
 * 无泄漏风险；刷新仍未完成时预取恢复抢带宽也只是回到修复前的行为，可接受。
 */
interface ThumbnailPrefetchThrottle {

    /** 前台刷新开始时调用：预取在窗口内让路（幂等，可重复调用续期） */
    fun pauseForForegroundRefresh()
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
 * **磁盘探测短路（第四百一十一笔）**：单条预取前先按稳定键直查磁盘缓存
 * （[PrefetchDiskProbe]），已缓存条目不发请求不解码、只计入进度——中断重跑/缓存
 * 齐全轮次从「全库重扫」退化为「只补缺口」（进度条不再反复从头跑全库），轮终在
 * logcat QimengCache 记档命中/下载汇总。键推导与写入侧同源（SignedMediaCacheKeys），
 * 探测失败按未缓存退化原 execute 路径，正确性不受影响。
 *
 * **revision 跳过（2026-09-30）**：轮首先拉一次 GET /library/revision，与 DataStore
 * 记录的「上一轮完成值」（[PrefetchRevisionStore]）经 [PrefetchRevisionGate] 比对，
 * 一致 → 整轮跳过进 [PrefetchUiState.Skipped]——库没变时连全量分页拉列表都省掉
 * （大库下原流程唯一的固定大头；磁盘探测短路只省下载，列表分页拉取仍在）。端点
 * 失败/首次降级全量，缺省永远偏多拉（服务端「多拉永远安全」同口径）。
 * 修订（2026-09-30 撞号与缓存漂移双修，ADR-0026 修订节）：① 记录捆绑服务器标识
 * （[PrefetchRevisionRecord.serverKey]）——两端修订号是独立计数器且播种基线相同，
 * 不比服务器键会被撞号错误 SKIP、换端后永不预取，现换服务器必走全量；② SKIP 判定
 * 通过后先用记录端同事务落盘的随机样本（[PrefetchSampleGate]）逐条本地磁盘探测，
 * 缺失达阈值降级全量——兜住 LRU 驱逐/系统清缓存目录/备份恢复回滚修订号的
 * 「记录与实际脱节」。
 *
 * **有意简化（不做断点游标持久化）**：预取进度不落盘。Coil 磁盘缓存命中项重跑
 * 时不走网络（本地读盘），进程重启后再跑一轮只是重复读盘 + 补新资产增量，代价
 * 可忽略——为省这点读盘把游标持久化进 DataStore 复杂度不划算（第四百一十一笔
 * 探测短路后连重复解码也省掉，该取舍进一步加固）。
 */
@Singleton
class ThumbnailPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
    private val revisionRepository: LibraryRevisionRepository,
    private val revisionStore: PrefetchRevisionStore,
    private val readinessProbe: ServerReadinessProbe,
    @ApplicationScope private val appScope: CoroutineScope,
    /** 磁盘缓存惰性句柄（探测短路用；dagger.Lazy 保持首次用到才构建的装配拍板） */
    private val splitDiskCache: dagger.Lazy<SplitDiskCache>,
) : ThumbnailPrefetchMonitor, ThumbnailPrefetchThrottle {

    private val _state = MutableStateFlow<PrefetchUiState>(PrefetchUiState.Idle)

    /** 预取轮状态流（缩略图缓存页收集渲染；[ThumbnailPrefetchMonitor] 只读面实现） */
    override val state: StateFlow<PrefetchUiState> = _state.asStateFlow()

    /**
     * 预取避让窗口截止时间（EpochMs；0=无避让）。[ThumbnailPrefetchThrottle] 实现，
     * 语义见该接口 KDoc。多线程读写（调用方=Main、读方=预取 worker 协程），用原子字段。
     */
    private val refreshPausedUntilMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** 前台刷新避让（问题B，2026-09-28）：续一个有界避让窗口，预取循环到期自愈恢复 */
    override fun pauseForForegroundRefresh() {
        refreshPausedUntilMs.set(System.currentTimeMillis() + PREFETCH_REFRESH_YIELD_WINDOW_MS)
    }

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
            // —— revision 跳过判定（2026-09-30）：库未变 → 整轮跳过，不再调 allThumbUrls ——
            // 放在就绪探针与计费网络门之后：此刻必有可用连接，revision 是单值轻请求。
            // 「先拉 revision 再拉列表」的次序是安全方向：记录值 ≤ 本轮列表实际新鲜度，
            // 拉取中途若有变更只会让记录值偏旧、下轮多拉一轮（修订号单调递增，反向
            // 「少拉」不可能发生）。端点失败降级 null → 门判 FULL 照旧全量。
            val serverRevision = revisionRepository.revision()
            // 记录时连接的服务器标识（原串精确比较）：与修订号成对记入，换服务器必走全量
            val serverKey = authRepository.serverUrl.first()
            val lastDoneRecord = readLastDoneRecord()
            if (PrefetchRevisionGate.decide(serverRevision, lastDoneRecord, serverKey) == PrefetchRoundDecision.SKIP) {
                // SKIP 前抽样核对（2026-09-30 缓存漂移修复）：修订号比对对本机磁盘缓存
                // 实况是盲的（池 LRU 驱逐/系统清缓存目录/备份恢复回滚服务端修订号，都会
                // 造成「记录说全量、实际缺片」且永不自愈），用记录端同事务落盘的随机
                // 样本逐条本地探测，缺失达阈值降级下方 FULL 路径补拉——失败方向落在多拉。
                val sample = readStoredSample()
                if (sample.isEmpty()) {
                    // 无样本可核对（空库轮/旧记录/样本键读失败）：维持 SKIP。样本读失败
                    // 而 revision 读取成功说明 DataStore 基本健康；且「读不到证据就多拉」
                    // 会把单键损坏放大成每轮全量，与磁盘探测短路「失败按未缓存退化」的
                    // 短路优化定位不符，故空样本按维持跳过收口。
                    Log.i(LOG_TAG, "库修订号未变 revision=$serverRevision（无抽样可核对），本轮跳过全量拉取")
                    _state.value = PrefetchUiState.Skipped
                    return
                }
                // probe 在 SKIP 分支内创建（下方 FULL 路径的原有创建点保持不动——本轮
                // 至多走其中一处，PrefetchDiskProbe 只是 SplitDiskCache 的无状态薄封装，
                // 降级轮次重复实例化零成本）
                val probe = PrefetchDiskProbe(splitDiskCache.get())
                val missing = sample.count { !probe.isDiskCached(it) }
                if (PrefetchSampleGate.shouldDowngradeToFull(sample.size, missing)) {
                    Log.w(LOG_TAG, "抽样核对 缺失 $missing/${sample.size} 达阈值，降级全量补拉")
                    // 不置 Skipped：落到下方 FULL 路径补缺，轮末 recordRound 写回新样本自愈
                } else {
                    Log.i(
                        LOG_TAG,
                        "库修订号未变 revision=$serverRevision 抽样 ${sample.size - missing}/${sample.size} 在缓存，本轮跳过全量拉取",
                    )
                    _state.value = PrefetchUiState.Skipped
                    return
                }
            }
            // 与首页网格完全同源的 md 缩略图绝对 URL（全量分页在 repository 内做）
            val urls = mediaRepository.allThumbUrls()
            val total = urls.size
            if (total == 0) {
                // 空库轮也是一次完成的「全量」：同样记录修订号+服务器标识（空库下轮才能
                // 被跳过；样本为空集合，与「空样本维持 SKIP」语义自洽）
                recordRound(serverRevision, serverKey, urls)
                _state.value = PrefetchUiState.Done(done = 0, total = 0)
                return
            }
            // 固定 worker 池限并发（2026-09-20 全库审查 P2 改法）：此前「全量 URL
            // 各起一个子协程排队抢 Semaphore」在全库 4 万上限时同时驻留 4 万个挂起
            // 协程（数十 MB 级纯调度开销）；改为 PREFETCH_CONCURRENCY 个 worker
            // 按步进分片取活（worker i 取第 i, i+C, i+2C…条），并发上限=worker 数、
            // 取消语义（登出取消 scope 即全部停）与进度口径（原子计数，成功失败
            // 都算处理过）与原实现逐字一致，不做逐条人为延时。
            val processed = AtomicInteger(0)
            val diskHits = AtomicInteger(0)
            val probe = PrefetchDiskProbe(splitDiskCache.get())
            coroutineScope {
                repeat(PREFETCH_CONCURRENCY) { worker ->
                    launch {
                        var i = worker
                        while (i < total) {
                            // 前台刷新避让（问题B，2026-09-28）：窗口内逐周期跳过下载让带宽——
                            // 登录后全库预取 4 并发会与用户正下拉等待的刷新首屏抢 NAS 带宽；
                            // delay 一个周期而非挂起整轮，窗口到期即恢复原节奏（自愈语义见接口 KDoc）
                            while (System.currentTimeMillis() < refreshPausedUntilMs.get()) {
                                delay(PREFETCH_REFRESH_YIELD_POLL_MS)
                            }
                            val url = urls[i]
                            // 磁盘探测短路（第四百一十一笔）：已缓存条目不发请求不解码，
                            // 只计入进度——中断重跑/缓存齐全轮次从「全库重扫」退化为「只补缺口」
                            if (probe.isDiskCached(url)) {
                                diskHits.incrementAndGet()
                            } else {
                                prefetchOne(url)
                            }
                            _state.value = PrefetchUiState.Running(
                                done = processed.incrementAndGet(),
                                total = total,
                            )
                            i += PREFETCH_CONCURRENCY
                        }
                    }
                }
            }
            Log.i(
                LOG_TAG,
                "预取轮完成 total=$total 磁盘命中=${diskHits.get()} 网络下载=${total - diskHits.get()}",
            )
            // 轮末（置 Done 前）记「上一轮完成值」：下轮 revision 未变即可整轮跳过
            recordRound(serverRevision, serverKey, urls)
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
     * 读「上一轮完成记录（服务器标识+修订号）」；读失败（DataStore IO 异常等）按
     * 无记录降级 null——门判 FULL 照旧全量，多拉永远安全；取消照常上抛交轮级取消
     * 收口（登出复位）。
     */
    private suspend fun readLastDoneRecord(): PrefetchRevisionRecord? = try {
        revisionStore.lastDone()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "预取修订号记录读取失败，按无记录降级全量", e)
        null
    }

    /**
     * 读「记录端抽样样本」（SKIP 前磁盘探测核对用）；读失败按空集合——空样本在
     * 调用方维持 SKIP（取舍理由见 SKIP 分支注释：revision 读取若也失败早已降级
     * FULL，样本读失败单独出现说明 DataStore 基本健康）；取消照常上抛。
     */
    private suspend fun readStoredSample(): Set<String> = try {
        revisionStore.storedSample()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "预取抽样样本读取失败，按空样本维持跳过", e)
        emptySet()
    }

    /**
     * 全量轮成功收口：把本轮开头读到的修订号、当轮连接的服务器标识与全库随机抽样
     * 记为「上一轮完成值」（两个 Done 出口共用）。拿到 null（revision 端点降级）不写、
     * 保持旧值；空库轮也照常记录（样本为空集合）；写失败只影响下一轮的跳过判定
     * （下轮降级全量重拉），不构成本轮失败——预取已真正完成，不能因偏好写失败把
     * 整轮报成错误。取消照常上抛。
     */
    private suspend fun recordRound(serverRevision: Long?, serverKey: String, urls: List<String>) {
        if (serverRevision == null) return
        try {
            revisionStore.setLastDone(
                PrefetchRevisionRecord(serverKey = serverKey, revision = serverRevision),
                PrefetchSampleGate.pickSample(urls),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "预取修订号写回失败（下轮降级全量重拉）", e)
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

        /**
         * 前台刷新避让窗口时长（毫秒，问题B 2026-09-28）：刷新触发一次续一窗，窗口内预取
         * 暂停下载。取 15s：覆盖列表首屏+facets 在弱网下的完成时长；超窗自愈恢复预取——
         * 即使刷新异常拖长，也只是回到修复前「共享带宽」状态，不永久饿死预取。
         */
        const val PREFETCH_REFRESH_YIELD_WINDOW_MS = 15_000L

        /** 避让窗口内的预取复查周期（毫秒）：秒级粒度足够（避让不追求毫秒精准），不做忙等 */
        const val PREFETCH_REFRESH_YIELD_POLL_MS = 1_000L
    }
}
