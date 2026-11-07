package media.qimeng.app.core.data.device

/**
 * 终态结果映射（纯逻辑，JVM 单测锁定）。
 *
 * 契约（openapi DeviceApplyResultStatus）：
 * - applied：全部声明段应用成功、manifest expectedReadback 全部匹配、应用后本机两代备份也写入成功；
 * - partial：至少一段应用成功，但仍有段失败 / 回读不符 / 本机回写失败；
 * - failed：没有任何段应用成功，或设备级授权/维护目录授权前置失败（不应用任何段）。
 *
 * 不把「服务端已应用但本机备份未刷新」报成 applied：手机侧仍是可恢复的中间态，
 * 需要用户到维护页重新导出；partial + message 让另一端能看见。
 */
object DeviceApplyResultPlanner {

    fun resolve(
        authorized: Boolean,
        segmentResults: List<SegmentApplyResult>,
        readbackMatched: Boolean,
        localWriteOk: Boolean,
    ): DeviceResultStatus {
        if (!authorized) return DeviceResultStatus.FAILED
        if (segmentResults.isEmpty()) return DeviceResultStatus.FAILED
        val applied = segmentResults.count { it.applied }
        if (applied == 0) return DeviceResultStatus.FAILED
        if (applied == segmentResults.size && readbackMatched && localWriteOk) {
            return DeviceResultStatus.APPLIED
        }
        return DeviceResultStatus.PARTIAL
    }

    /** 组装回传 message；不包含本机路径、令牌或段正文（≤2000 字符）。 */
    fun message(
        status: DeviceResultStatus,
        segmentResults: List<SegmentApplyResult>,
        readback: List<SegmentReadbackComparison>,
        localWriteOk: Boolean,
    ): String = DeviceApplyMessages.truncate(buildString {
        append(
            when (status) {
                DeviceResultStatus.APPLIED -> "结果包已全部应用"
                DeviceResultStatus.PARTIAL -> "结果包部分应用"
                DeviceResultStatus.FAILED -> "结果包应用失败"
            },
        )
        val applied = segmentResults.filter { it.applied }.map { it.kind.wireName }
        val failed = segmentResults.filterNot { it.applied }
        if (applied.isNotEmpty()) append("；成功段：").append(applied.joinToString(", "))
        if (failed.isNotEmpty()) {
            append("；失败段：")
            append(failed.joinToString("; ") { "${it.kind.wireName}（${it.message ?: "未知原因"}）" })
        }
        val mismatch = readback.filterNot { it.comparison.matched }
        if (mismatch.isNotEmpty()) {
            append("；回读不符：")
            append(
                mismatch.joinToString("; ") { item ->
                    val details = buildList {
                        if (item.comparison.missingKeys.isNotEmpty()) add("缺少 ${item.comparison.missingKeys.joinToString(",")}")
                        if (item.comparison.mismatchedKeys.isNotEmpty()) add("值不符 ${item.comparison.mismatchedKeys.joinToString(",")}")
                    }.joinToString("，")
                    "${item.kind.wireName}（$details）"
                },
            )
        }
        if (!localWriteOk) append("；本机两代备份刷新失败，请到资料备份与归档重新导出")
    })
}

/** 单段应用结果；[message] 进入回传 message 的用户可读原因。 */
data class SegmentApplyResult(
    val kind: DeviceSegmentKind,
    val applied: Boolean,
    val message: String? = null,
)

/** 单段回读比较（kind 保留，便于 message 定位哪一段不符）。 */
data class SegmentReadbackComparison(
    val kind: DeviceSegmentKind,
    val comparison: ReadbackComparison,
)

/** 回传 message 的统一上限与截断（服务端 maxLength 2000；按 code point 截断不劈开代理对）。 */
object DeviceApplyMessages {

    const val MAX_MESSAGE_CHARS = 2000

    fun truncate(message: String): String {
        if (message.isEmpty()) return message
        val codePoints = message.codePointCount(0, message.length)
        if (codePoints <= MAX_MESSAGE_CHARS) return message
        val end = message.offsetByCodePoints(0, MAX_MESSAGE_CHARS - 1)
        return message.substring(0, end) + "…"
    }
}
