package media.qimeng.app.core.data.upload

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 上传任务的用户取消标记（批C 任务Q C-2）。进程内存活即可：取消动作只发生在本进程
 * UI 会话里，worker 消费标记的窗口也在本进程内。
 *
 * 为什么不调 WorkManager cancelWorkById（任务书冻结实现路径按官方文档查证结果调整并记档）：
 * 官方《Managing work》明文「cancelWorkById 使该 work 与**所有依赖它的 work** 一并 CANCELLED」
 * ——本队列为 unique 串行链（后入队任务以前置任务为 prerequisite），对链上任一任务调用
 * cancelWorkById 会把其后排队中的任务连坐取消，直接违反本需求「单任务取消」冻结语义；
 * 官方没有「从链中摘除单个 work 且不级联」的 API。故改为协作式取消：
 * 标记置位 → UI 立即把该行翻成「已取消」→ worker 执行前/写流中检查标记自行终止。
 *
 * 终态通道同样是级联敏感点（342 笔返工，reviewer P1）：取消终态必须以
 * **Result.success + KEY_CANCELLED 标志**落盘，绝不能 Result.failure——failure 与
 * cancel 一样触发引擎级联（iterativelyFailWorkAndDependents 会把链上全部后续任务
 * 标 FAILED、永不执行）；success 不级联，下游正常解锁执行，取消语义由输出键承载
 * （映射见 UploadWorkSpec.outcomeToResult，两侧识别见 SdkUploadRepository.toEntry）。
 *
 * 残留边界（记档）：标记置位后进程被杀的极小窗口内，WorkManager 会把 RUNNING 任务
 * 重新入队、重启后 registry 为空 → 该任务继续上传（用户可再次取消）；localId 为 UUID
 * 且任务终局即从队列消失，残留条目只是可忽略的内存量级，不做清理钩子。
 */
@Singleton
class UploadCancelRegistry @Inject constructor() {

    private val cancelled = ConcurrentHashMap<String, Unit>()

    /**
     * 取消标记版本号（queueUpdates 合并观察用）：标记/消费都会 bump，驱动队列流重算——
     * 取消后 UI 无需等 WorkManager 状态轮转即可立即看到「已取消」。
     */
    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> = _updates.asStateFlow()

    /** 标记取消（幂等）。 */
    fun cancel(localId: String) {
        cancelled[localId] = Unit
        _updates.value += 1
    }

    /** 该任务是否已被用户取消（worker 执行前与写流 chunk 循环里高频调用）。 */
    fun isCancelled(localId: String): Boolean = cancelled.containsKey(localId)

    /** 任务终局后清标记（worker 消费侧调用，防极小概率同 localId 复用时误判）。 */
    fun consume(localId: String) {
        if (cancelled.remove(localId) != null) _updates.value += 1
    }
}
