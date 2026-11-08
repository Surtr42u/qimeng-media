package media.qimeng.app.core.data.device

import androidx.work.PeriodicWorkRequest

/**
 * 设备结果包 Worker 的工作契约（名称/约束/重试阈值收口，纯函数单测锁定）。
 *
 * 与上传/事件补传链使用不同 unique 名，互不抢链位；周期下限取官方常量
 * （PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS = 15 分钟），写更小值系统也会钳制。
 */
object DeviceApplyWorkSpec {

    /** logcat 证据标签（grep 'QimengDeviceApply'）。 */
    const val LOG_TAG = "QimengDeviceApply"

    /** 周期拉取 unique 名（KEEP/UPDATE 幂等注册）。 */
    const val UNIQUE_PERIODIC_NAME = "qm-device-apply-periodic"

    /** 手动「立即应用」unique 名（KEEP 合并连点）。 */
    const val UNIQUE_NOW_NAME = "qm-device-apply-now"

    const val TAG_DEVICE_APPLY = "qm-device-apply"

    /** 一次性 Worker 的输入键：true=用户手动触发（后台开关关闭也允许）。 */
    const val KEY_MANUAL = "deviceApplyManual"

    /** Worker 输出键：最近一次本机摘要（仅诊断/WorkInfo 观察，不是服务端结果账本）。 */
    const val KEY_MESSAGE = "deviceApplyMessage"

    /**
     * 可重试失败（网络/本机服务未就绪）的最大 WorkManager 重试次数（不含首次执行）。
     * 超过后返回 success，等待下一个周期；避免周期任务因持续断网反复退避堆积。
     */
    const val MAX_RETRIES = 2

    /** 周期 = WorkManager 系统下限 15 分钟。 */
    val PERIODIC_INTERVAL_MILLIS: Long = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS

    /**
     * 远端服务器需要 CONNECTED；本机内嵌模式是回环地址，不依赖系统网络状态
     * （飞行模式/Wi-Fi 关闭时内嵌服务仍可用），故放宽为 NOT_REQUIRED。
     */
    fun requiresConnectedNetwork(localMode: Boolean): Boolean = !localMode

    /** 是否还有 WorkManager 重试额度；耗尽后交给下一周期。 */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
}
