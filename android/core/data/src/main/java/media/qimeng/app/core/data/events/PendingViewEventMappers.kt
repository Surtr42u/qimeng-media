package media.qimeng.app.core.data.events

import media.qimeng.app.core.data.repository.SdkDetailMappers
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.models.ViewEventReport

/**
 * 队列行 → SDK 上报体（drain 出网前的唯一换算点）。
 *
 * kind/startedAt(UTC)/sessionId/clientEventId 复用 [SdkDetailMappers.toViewEventReport]
 * 单源映射（同一映射逻辑禁止两处手抄——代码卫生约束 2）；dwell 秒数按 M4-4 冻结口径
 * `durationMs / 1000.0` 毫秒→秒换算（不用 mapper 的 Long 秒入参：那会把毫秒
 * 截断成整秒）。open/play durationMs 恒 0，不带 seconds。
 * 协议批 2026-09-09（#24 根修）：seconds 由 BigDecimal 改 Double（openapi
 * format:double 后 SDK 走原生类型，moshi 序列化为 JSON 数字不再带引号）。
 * 任务L L5：clientEventId 幂等键透传——调用方（drain）已保证非空（迁移存量行
 * 懒回填），SDK 类型为必填 UUID，空值会在此抛 IllegalArgumentException 并被
 * 发送层折进 IoError 保守重试，不会炸 drain。
 */
internal fun PendingViewEventEntity.toSdkReport(): ViewEventReport {
    val base = SdkDetailMappers.toViewEventReport(
        assetId = assetId,
        kind = ViewEventKind.valueOf(kind),
        startedAtMs = startedAt,
        sessionId = sessionId,
        clientEventId = clientEventId,
        dwellSeconds = null,
    )
    if (kind != ViewEventKind.DWELL.name) return base
    return base.copy(seconds = durationMs / 1000.0)
}
