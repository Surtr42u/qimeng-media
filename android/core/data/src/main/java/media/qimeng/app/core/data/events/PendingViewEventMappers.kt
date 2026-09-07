package media.qimeng.app.core.data.events

import java.math.BigDecimal
import media.qimeng.app.core.data.repository.SdkDetailMappers
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.models.ViewEventReport

/**
 * 队列行 → SDK 上报体（drain 出网前的唯一换算点）。
 *
 * kind/startedAt(UTC)/sessionId 复用 [SdkDetailMappers.toViewEventReport] 单源映射
 * （同一映射逻辑禁止两处手抄——代码卫生约束 2）；dwell 秒数按 M4-4 冻结口径
 * `BigDecimal.valueOf(ms).movePointLeft(3)` 毫秒→秒无损换算（不用 mapper 的
 * Long 秒入参：那会把毫秒截断成整秒）。open/play durationMs 恒 0，不带 seconds。
 */
internal fun PendingViewEventEntity.toSdkReport(): ViewEventReport {
    val base = SdkDetailMappers.toViewEventReport(
        assetId = assetId,
        kind = ViewEventKind.valueOf(kind),
        startedAtMs = startedAt,
        sessionId = sessionId,
        dwellSeconds = null,
    )
    if (kind != ViewEventKind.DWELL.name) return base
    return base.copy(seconds = BigDecimal.valueOf(durationMs).movePointLeft(3))
}
