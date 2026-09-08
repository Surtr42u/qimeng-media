package media.qimeng.app.core.data.events

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.sdk.infrastructure.ResponseType

/**
 * 队列出网端口（M4-4）：drain 逐条发送一枚队列行。抽接口的为什么——ViewEventQueue 的
 * drain 串行/毒丸/先删后发语义要在 JVM 单测锁定，出网细节（SDK/IO）必须可替换。
 */
interface ViewEventSender {

    /** 发送一枚事件；不抛出网异常（全部折叠进 [ViewEventSendResult]，取消除外） */
    suspend fun send(event: PendingViewEventEntity): ViewEventSendResult
}

/**
 * 生成 SDK 实现（drain 的内部出网路径——M4-3 直连上报的 apiV1EventsViewPost 调用收编至此，
 * 冻结口径「直连实现改为 drain 内部出网路径」）。用 WithHttpInfo 变体拿真实状态码喂分类表
 * （协议只认 202，不能把「没抛异常」笼统当成功）。每个出网请求打一行 logcat（文本证据协议）。
 */
@Singleton
class SdkViewEventSender @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : ViewEventSender {

    override suspend fun send(event: PendingViewEventEntity): ViewEventSendResult {
        Log.d(
            EventSyncWorkSpec.LOG_TAG,
            "POST /events/view kind=${event.kind} startedAt=${event.startedAt} " +
                "durationMs=${event.durationMs} session=${event.sessionId} rowId=${event.id}",
        )
        return withContext(Dispatchers.IO) {
            try {
                val response = apiFactory.create()
                    .apiV1EventsViewPostWithHttpInfo(event.toSdkReport())
                when (response.responseType) {
                    // Success/ClientError/ServerError 都带真实 statusCode 进分类表；
                    // Informational/Redirection 客户端不支持（SDK 契约），折进 IO 口径保守重试
                    ResponseType.Success,
                    ResponseType.ClientError,
                    ResponseType.ServerError,
                    -> ViewEventSendResult.Http(response.statusCode)

                    ResponseType.Informational, ResponseType.Redirection ->
                        ViewEventSendResult.IoError
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(EventSyncWorkSpec.LOG_TAG, "send io-failed rowId=${event.id}: ${e.message}")
                ViewEventSendResult.IoError
            } catch (e: Exception) {
                // 非 IO 运行时异常也必须折进结果（审查清偿）：事件体映射（如 UUID.fromString
                // 脏 assetId 抛 IllegalArgumentException）若冒过本层，会击穿 drain 的
                // 「不抛出网异常」契约 → 整轮已删行全丢。归 RETRY 口径保守重试，
                // 确定性失败由毒丸连败阈值（≥3）最终丢弃，方向仍是宁少计不虚增。
                // throwable 全量入栈（复审清偿）：编程错误（NPE 等）不能只留 message 丢栈。
                Log.w(EventSyncWorkSpec.LOG_TAG, "send crashed rowId=${event.id}: ${e.message}", e)
                ViewEventSendResult.IoError
            }
        }
    }
}
