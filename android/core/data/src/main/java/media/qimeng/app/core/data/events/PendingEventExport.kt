package media.qimeng.app.core.data.events

import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.infrastructure.Serializer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 未上传事件导出（任务L L5；用户原话 #25「可以把安卓本地的直接传给 nas 合并」）：
 * 队列全量行（可重试 + 终局）序列化为 JSON **数组**，每个元素就是一个
 * POST /api/v1/events/view 的合法请求体——用户手动 POST 与 App 自动补传携带
 * 同一 clientEventId，服务端唯一索引幂等，两条路径结果完全一致（不双计）。
 *
 * 字段与 [toSdkReport] 同口径单源复用：kind 大写枚举名转协议小写、dwell 才带
 * seconds（ms→秒无损换算）、startedAt 存绝对时刻（UTC RFC3339）。
 */
internal fun List<PendingViewEventEntity>.toPendingExportJson(): String {
    val exports: List<Map<String, Any>> = map { event ->
        val report: MutableMap<String, Any> = mutableMapOf(
            // 协议字段名与 openapi.yaml ViewEventReport 一致（改动须两侧同步）
            "assetId" to event.assetId,
            // 实体存 ViewEventKind.name（OPEN/PLAY/DWELL 大写），协议枚举是小写
            "kind" to event.kind.lowercase(),
            "startedAt" to OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(event.startedAt),
                ZoneOffset.UTC,
            ).toString(),
            "sessionId" to event.sessionId,
            "clientEventId" to event.clientEventId,
        )
        if (event.kind == ViewEventKind.DWELL.name) {
            report["seconds"] = event.durationMs / 1000.0
        }
        report
    }
    val type = com.squareup.moshi.Types.newParameterizedType(
        List::class.java,
        com.squareup.moshi.Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java,
        ),
    )
    // 紧凑输出（不加 indent）：数组元素保持单行紧凑形态，手动 POST 时零改写
    return Serializer.moshi.adapter<List<Map<String, Any>>>(type).toJson(exports)
}
