package media.qimeng.app.core.data.diagnostics

import android.util.Log
import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.sdk.infrastructure.ResponseType

/**
 * 客户端异常上报通道（POST /api/v1/client-logs；协议 openapi ClientLogBatch，
 * GUIDE_API「客户端异常」行；服务端 kv_settings 环形缓冲最新 200 条）。
 *
 * 与 Web 端 lib/client-logs.ts 同款策略（两端口径互相锚定，改动须同步）：
 * - 队列满 [FLUSH_BATCH_THRESHOLD] 条立即 flush，否则 [FLUSH_INTERVAL_MS] 定时 flush；
 * - flush 失败静默丢弃本批（上报是旁路设施，绝不重试放大故障），仅 logcat 留痕；
 * - message 截 [MESSAGE_MAX_RUNES] 字（协议上限，超限服务端整批 400）、
 *   stack 截 [STACK_MAX_CHARS] 字符（Web 同款防御，防单条巨堆栈撑爆环形缓冲）。
 *
 * Android 增量口径（Web 没有的场景）：
 * - 崩溃路径（[flushBlockingOnCrash]）：未捕获异常处理起后台线程同步补发并限时等待——
 *   崩溃线程可能是主线程（不能直接出网），限时 join 防止拖死崩溃流程；
 * - 断网期间的崩溃/错误会随批丢弃（与 Web 的「失败即弃」一致）。取舍（诚实口径）：
 *   不做磁盘持久化离线队列——异常上报是低频排障辅助，离线丢批可接受，不值得为它
 *   引入第二套持久化队列（行为上报 ViewEventQueue 已有一套，语义不同不宜混用）。
 */
class ClientLogRecorder @Inject constructor(
    private val sender: ClientLogSender,
) {

    constructor(sender: ClientLogSender, scopeCoroutineContext: CoroutineContext) : this(sender) {
        scope = CoroutineScope(SupervisorJob() + scopeCoroutineContext)
    }

    /** 异步触发专用（flush 定时/回前台补传）；崩潰路径不经它（见 flushBlockingOnCrash） */
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val queue = ArrayDeque<PendingClientLog>()
    private var timerArmed = false

    /**
     * 记一条异常（入口全 try-catch：上报器自身异常绝不外抛——崩潰处理器里抛错会
     * 吞掉真正的崩溃栈）。
     */
    fun record(
        level: ClientLogLevel,
        message: String,
        stack: String? = null,
        page: String? = null,
    ) {
        try {
            when (submit(level, message, stack, page)) {
                FlushHint.NOW -> scope.launch { runCatching { flushNow() } }
                FlushHint.LATER -> armTimer()
                FlushHint.IDLE -> Unit
            }
        } catch (ignored: Throwable) {
            Log.w(LOG_TAG, "record failed (swallowed): ${ignored.message}")
        }
    }

    /** 回前台/启动补传入口（DiagnosticsBootstrapper ON_START 调用）；失败静默 */
    fun flushAsync() {
        scope.launch { runCatching { flushNow() } }
    }

    /**
     * 崩溃路径专用：起后台线程同步 flush（崩溃线程可能是主线程，不能直接出网）
     * 并限时等待；超时/失败即放弃，把崩溃交还给系统。必须在崩溃处理器内调用。
     */
    fun flushBlockingOnCrash() {
        try {
            val worker = Thread { runBlocking { runCatching { flushNow() } } }
            worker.start()
            worker.join(CRASH_FLUSH_JOIN_MS)
        } catch (ignored: Throwable) {
            Log.w(LOG_TAG, "crash flush failed (swallowed): ${ignored.message}")
        }
    }

    /** 同步入队 + 截断 + 容量裁剪；返回是否需要触发 flush（测试锚点：纯同步语义） */
    @VisibleForTesting
    internal fun submit(
        level: ClientLogLevel,
        message: String,
        stack: String?,
        page: String?,
    ): FlushHint {
        synchronized(lock) {
            queue.addLast(
                PendingClientLog(
                    ts = System.currentTimeMillis(),
                    level = level,
                    message = message.take(MESSAGE_MAX_RUNES),
                    stack = stack?.take(STACK_MAX_CHARS),
                    page = page,
                ),
            )
            while (queue.size > QUEUE_CAPACITY) {
                queue.removeFirst() // 丢最旧（与服务端环形缓冲同口径）
            }
            return when {
                queue.size >= FLUSH_BATCH_THRESHOLD -> FlushHint.NOW
                !timerArmed -> {
                    timerArmed = true
                    FlushHint.LATER
                }
                else -> FlushHint.IDLE
            }
        }
    }

    /** 排干当前队列：按协议单批上限切块发送；任一块失败整批丢弃（Web 同口径） */
    @VisibleForTesting
    internal suspend fun flushNow() {
        val batch = synchronized(lock) {
            val snapshot = queue.toList()
            queue.clear()
            timerArmed = false
            snapshot
        }
        if (batch.isEmpty()) return
        var sent = 0
        for (chunk in batch.chunked(CLIENT_LOGS_BATCH_MAX)) {
            if (!sender.send(chunk)) {
                Log.w(
                    LOG_TAG,
                    "POST /client-logs dropped batch=${batch.size} sent=$sent " +
                        "(失败即弃，不重试)",
                )
                return
            }
            sent += chunk.size
        }
        Log.d(LOG_TAG, "POST /client-logs ok n=$sent")
    }

    private fun armTimer() {
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            synchronized(lock) { timerArmed = false }
            runCatching { flushNow() }
        }
    }

    /** record() 的触发分档（internal：submit 的返回类型可见性须一致） */
    internal enum class FlushHint { NOW, LATER, IDLE }

    companion object {
        private const val LOG_TAG = "ClientLogRecorder"

        /** 队列容量：对齐服务端环形缓冲 200 条（clientlogs.go clientLogsCapacity），超出丢最旧 */
        const val QUEUE_CAPACITY = 200

        /** 单次 POST 批量上限（协议 ClientLogBatch maxItems=50；超限服务端 400） */
        const val CLIENT_LOGS_BATCH_MAX = 50

        /** 满即 flush 的批量阈值（Web MAX_QUEUE 同款：协议单批上限 50，10 条一批留足余量） */
        const val FLUSH_BATCH_THRESHOLD = 10

        /** 定时 flush 间隔（毫秒）：低频错误不至于攒到进程死亡都没发出去（Web 同款） */
        const val FLUSH_INTERVAL_MS = 30_000L

        /** message 截断长度（= 协议 maxLength 2000 字；超限服务端整批 400 不截断，客户端必须自己截） */
        const val MESSAGE_MAX_RUNES = 2000

        /** stack 截断长度（协议未约束的客户端防御，Web STACK_MAX 同款） */
        const val STACK_MAX_CHARS = 8000

        /** 崩溃补发等待上限（毫秒）：崩溃流程不能被上报拖死 */
        const val CRASH_FLUSH_JOIN_MS = 3_000L
    }
}

/** 待上报条目（协议 ClientLogEntry 的客户端侧形态，wire 值映射收在发送器） */
data class PendingClientLog(
    val ts: Long,
    val level: ClientLogLevel,
    val message: String,
    val stack: String?,
    val page: String?,
)

/** 协议 level 三枚举的客户端侧形态（非法 wire 值服务端 400，映射单源在此） */
enum class ClientLogLevel(val wire: String) {
    ERROR("error"),
    WARN("warn"),
    INFO("info"),
}

/**
 * 队列出网端口（立接口理由同 ViewEventSender：flush 的切块/失败即弃语义要在
 * JVM 单测锁定，SDK/IO 细节可替换）。
 */
interface ClientLogSender {
    /** 发送一批（1~50 条）；不抛出网异常，成败折叠进返回值（取消除外） */
    suspend fun send(events: List<PendingClientLog>): Boolean
}

/**
 * 生成 SDK 实现。协议只认 204，用 WithHttpInfo 变体拿真实状态码；每个出网请求
 * 打一行 logcat（文本证据协议）。未登录时 BusinessApiFactory.create() 抛
 * IllegalStateException——折叠为失败丢弃（未登录期间的错误本就无处可报）。
 */
@Singleton
class SdkClientLogSender @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : ClientLogSender {

    override suspend fun send(events: List<PendingClientLog>): Boolean {
        Log.d(LOG_TAG, "POST /client-logs n=${events.size}")
        return withContext(Dispatchers.IO) {
            try {
                val response = apiFactory.create()
                    .apiV1ClientLogsPostWithHttpInfo(
                        events.toSdkBatch(),
                    )
                // 协议只认 204；4xx（校验拒绝）/5xx 一律按失败丢弃
                response.responseType == ResponseType.Success && response.statusCode == 204
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "send io-failed n=${events.size}: ${e.message}")
                false
            }
        }
    }

    private fun List<PendingClientLog>.toSdkBatch() = media.qimeng.sdk.models.ClientLogBatch(
        events = map {
            media.qimeng.sdk.models.ClientLogEntry(
                ts = it.ts,
                level = media.qimeng.sdk.models.ClientLogEntry.Level.entries
                    .first { level -> level.value == it.level.wire },
                message = it.message,
                stack = it.stack,
                page = it.page,
            )
        },
    )

    companion object {
        private const val LOG_TAG = "ClientLogSender"
    }
}
