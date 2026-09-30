package media.qimeng.app.core.data.upload

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分片上传会话 id 的进程内记忆表（ADR-0028 Android 接入批，localId -> sessionId）。
 *
 * 为什么必须记会话 id：断点以服务端 offset 为唯一真相源（本地不存 offset，任务书
 * 口径），但每次 WorkManager 重试「先 GET 探测再续传」需要上一轮创建的会话 id——
 * 不记 id 就只能每轮重新 create（新会话从 0 起），续传能力退化为单次尝试内有效。
 * 本表只记「会话把手」不记断点：offset 仍以每轮 GET 探测的服务端权威值为准。
 *
 * 进程内存活口径与 [UploadCancelRegistry] 同款：进程死亡丢表 → 下一轮重新创建会话
 * （从 0 重传的最后手段），服务端遗留孤儿会话与临时文件由 24h TTL 清扫兜底
 * （ADR-0028 决策 5）；localId 为 UUID 且任务终局即从队列消失，残留条目只是可忽略
 * 的内存量级，不做清理钩子。
 */
@Singleton
class UploadSessionRegistry @Inject constructor() {

    private val sessions = ConcurrentHashMap<String, String>()

    /** 上一轮遗留的会话 id（无 = null，调用方创建新会话）。 */
    fun lookup(localId: String): String? = sessions[localId]

    /** 记录新建/重建的会话 id（同 localId 重建覆盖旧值）。 */
    fun remember(localId: String, sessionId: String) {
        sessions[localId] = sessionId
    }

    /** 终局清除（Success/Permanent/Cancelled；Retryable 保留供下轮探测续传）。 */
    fun forget(localId: String) {
        sessions.remove(localId)
    }
}
