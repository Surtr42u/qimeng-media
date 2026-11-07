package media.qimeng.app.core.data.device

/**
 * 设备结果包协议面的纯策略（JVM 单测锁定，无 Android/SDK 依赖）。
 *
 * 只实现 Worker 必须自保的检查：目标设备匹配、协议版本、指纹格式、段清单形状与摘要格式。
 * 服务端已在登记/领取时校验这些字段；客户端复核用于「不把错误包应用进本机」以及
 * 防御旧服务端/损坏响应，不重复实现业务规则。
 */
object DeviceApplyPolicy {

    /** 协议版本（openapi DeviceApplyManifest.protocolVersion 仅支持 1）。 */
    const val PROTOCOL_VERSION = 1

    /** targetDeviceId 长度上限（openapi pattern）。 */
    const val MAX_TARGET_DEVICE_LENGTH = 100

    /** manifest 段数上限（openapi maxItems: 2）。 */
    const val MAX_SEGMENTS = 2

    /** targetDeviceId 不透明标签字符集：A-Za-z0-9._:-（1–100）。 */
    private val TARGET_DEVICE_PATTERN = Regex("^[A-Za-z0-9._:-]{1,$MAX_TARGET_DEVICE_LENGTH}$")

    /** 段摘要/规则指纹：64 位小写十六进制（服务端恒复算后返回小写）。 */
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    /**
     * 设置页/Worker 共用的设备标签校验。首尾空白不接受：标签是服务端精确匹配的不透明值，
     * 空白会与 `device_ids` 绑定不一致；UI 保存前自行 trim。
     */
    fun isValidTargetDeviceId(value: String): Boolean = TARGET_DEVICE_PATTERN.matches(value)

    fun isValidSha256(value: String): Boolean = SHA256_PATTERN.matches(value)

    fun isValidRulesFingerprint(value: String): Boolean = SHA256_PATTERN.matches(value)

    /**
     * 目标筛选（二次防御）：服务端列表已按 targetDevice/status 过滤，Worker 仍只处理
     * 「目标=本机且 status=pending」的摘要，避免旧服务端忽略参数或响应被替换时误领。
     */
    fun pendingForDevice(
        items: List<DeviceApplySummary>,
        targetDeviceId: String,
    ): List<DeviceApplySummary> = items.filter {
        it.targetDeviceId == targetDeviceId && it.status == DeviceTaskStatus.PENDING
    }

    /**
     * 领取后、下载段前的整包校验。失败即不应用任何段，回传 failed。
     * 返回 [DeviceTaskValidation.ok] + 中文原因（服务端 ledger/旧客户端可读）。
     */
    fun validateTask(task: DeviceApplyDetail, expectedTargetDeviceId: String): DeviceTaskValidation {
        if (task.targetDeviceId != expectedTargetDeviceId) {
            return DeviceTaskValidation(false, "结果包目标设备与本机配置不一致")
        }
        if (task.status != DeviceTaskStatus.CLAIMED) {
            return DeviceTaskValidation(false, "结果包不是已领取状态（status=${task.status}）")
        }
        if (task.protocolVersion != PROTOCOL_VERSION) {
            return DeviceTaskValidation(false, "结果包协议版本 ${task.protocolVersion} 不受支持（仅支持 $PROTOCOL_VERSION）")
        }
        if (!isValidRulesFingerprint(task.rulesFingerprint)) {
            return DeviceTaskValidation(false, "结果包规则指纹格式非法")
        }
        if (task.segments.isEmpty() || task.segments.size > MAX_SEGMENTS) {
            return DeviceTaskValidation(false, "结果包段数为 ${task.segments.size}，应为 1–$MAX_SEGMENTS 段")
        }
        if (task.segments.map { it.kind }.distinct().size != task.segments.size) {
            return DeviceTaskValidation(false, "结果包段类型重复")
        }
        for (segment in task.segments) {
            if (segment.bytes <= 0) {
                return DeviceTaskValidation(false, "段 ${segment.kind.wireName} 声明字节数非法")
            }
            if (!isValidSha256(segment.sha256)) {
                return DeviceTaskValidation(false, "段 ${segment.kind.wireName} 摘要格式非法")
            }
        }
        return DeviceTaskValidation(true, null)
    }
}

/** 整包校验结果；[message] 在失败时直接进入回传 message（不泄露本机路径/令牌）。 */
data class DeviceTaskValidation(val ok: Boolean, val message: String?)
