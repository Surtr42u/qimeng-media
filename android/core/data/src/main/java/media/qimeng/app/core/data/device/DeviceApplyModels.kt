package media.qimeng.app.core.data.device

/**
 * 设备结果包 Worker 的域模型（2026-11-07 task-17，ADR-0055）。
 *
 * 生成 SDK 的 `media.qimeng.sdk.models.DeviceApply*` 只在 [DeviceApplyRemote] 边缘映射，
 * 本包其余逻辑（目标筛选、段校验、回读、结果映射、设置持久化）不依赖 SDK；
 * feature 侧只消费这些域类型，保持 `feature → core` 单向（ADR-0014）。
 */

/** 结果包段类型；字符串值与 `api/openapi.yaml` 的 DeviceApplySegmentKind 逐字一致。 */
enum class DeviceSegmentKind(val wireName: String) {
    /** 作者主表 TXT（≤1MiB），应用走 POST /api/v1/authors/import-txt。 */
    MASTER_TXT("masterTxt"),

    /** v2 便携备份（≤64MiB），应用走 POST /api/v1/import/qimeng-backup。 */
    V2_BACKUP("v2Backup"),
    ;

    companion object {
        fun fromWire(value: String): DeviceSegmentKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** 服务端账本状态机（openapi DeviceApplyTaskStatus）；[isTerminal] 与协议语义一致。 */
enum class DeviceTaskStatus {
    PENDING,
    CLAIMED,
    APPLIED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    ;

    val isTerminal: Boolean get() = this != PENDING && this != CLAIMED
}

/** 回传终态（openapi DeviceApplyResultStatus）。 */
enum class DeviceResultStatus { APPLIED, PARTIAL, FAILED }

/** 列表摘要（不含段清单/结果正文）。 */
data class DeviceApplySummary(
    val id: String,
    val targetDeviceId: String,
    val status: DeviceTaskStatus,
    val protocolVersion: Int,
    val rulesFingerprint: String,
)

/** manifest 声明的段（bytes/sha256 来自服务端登记账本，expectedReadback 原样透传）。 */
data class DeviceApplySegment(
    val kind: DeviceSegmentKind,
    val bytes: Long,
    val sha256: String,
    val expectedReadback: Map<String, Any>? = null,
)

/** 任务详情（领取/详情共用）。 */
data class DeviceApplyDetail(
    val id: String,
    val targetDeviceId: String,
    val status: DeviceTaskStatus,
    val protocolVersion: Int,
    val rulesFingerprint: String,
    val segments: List<DeviceApplySegment>,
)

/** 手机本地复算的段摘要（回传 deviceLocalHashes）。 */
data class DeviceLocalHash(val kind: DeviceSegmentKind, val sha256: String)

/** 待回传结果（readback 为任意 JSON 对象；message 由服务端限制 2000 字符）。 */
data class DeviceApplyReport(
    val status: DeviceResultStatus,
    val readback: Map<String, Any> = emptyMap(),
    val deviceLocalHashes: List<DeviceLocalHash> = emptyList(),
    val message: String? = null,
)
