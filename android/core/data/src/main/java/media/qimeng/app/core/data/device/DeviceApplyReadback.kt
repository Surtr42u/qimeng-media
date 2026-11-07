package media.qimeng.app.core.data.device

import java.math.BigDecimal
import media.qimeng.app.core.model.LegacyImportSummary
import media.qimeng.app.core.model.TxtImportSummary

/**
 * 回读指标与 manifest expectedReadback 的纯比较（JVM 单测锁定）。
 *
 * 指标来源 = 导入响应（作者 TXT 的 authorsImported/filesMatched、v2 备份的
 * eventsReplayed/authorsImported/authorRefsImported/tagsImported/...）。协议没有
 * 「按指标读取」端点，导入响应本身就是服务端权威计数；Worker 不伪造未返回的指标。
 *
 * 回传 readback 的结构约定（写入 docs/api/device.md）：
 * - 单段包：指标平铺，如 `{"authors":3,"assetAuthorRefs":4}`；
 * - 双段包：按段嵌套，如 `{"masterTxt":{...},"v2Backup":{...}}`，避免同名键互相覆盖。
 * expectedReadback 始终按段与对应指标比较（不是拿整包平铺对象比较）。
 */
object DeviceApplyReadback {

    /** 作者 TXT 导入结果 → 该段指标。 */
    fun fromMasterTxt(summary: TxtImportSummary): Map<String, Any> = buildMap {
        summary.authorsImported?.let {
            put("authors", it)
            put("authorsImported", it)
        }
        summary.filesMatched?.let {
            put("assetAuthorRefs", it)
            put("filesMatched", it)
        }
    }

    /** v2 备份导入结果 → 该段指标（只放响应真实返回的计数）。 */
    fun fromBackup(summary: LegacyImportSummary): Map<String, Any> = buildMap {
        summary.eventsReplayed?.let {
            put("viewEvents", it)
            put("eventsReplayed", it)
        }
        summary.authorsImported?.let {
            put("authors", it)
            put("authorsImported", it)
        }
        summary.authorRefsImported?.let {
            put("assetAuthorRefs", it)
            put("authorRefsImported", it)
        }
        summary.tagsImported?.let {
            put("tags", it)
            put("tagsImported", it)
        }
        summary.assetsMatched?.let { put("assetsMatched", it) }
        summary.mediaFilesTotal?.let { put("mediaFilesTotal", it) }
    }

    /**
     * manifest expectedReadback 与单段实际指标的逐键比较。
     * - expected 为 null/空 = 该段未声明回读要求，视为匹配；
     * - 缺失键与值不等都记入结果（缺失键单独列出，便于提示「旧服务端未返回」或「包声明了本端不认识的指标」）；
     * - 数字按数值比较（Moshi 把 JSON 数字解析为 Double，实际计数为 Int/Long），字符串/布尔/数组/对象递归精确比较。
     */
    fun compare(expected: Map<String, Any>?, actual: Map<String, Any>): ReadbackComparison {
        if (expected.isNullOrEmpty()) return ReadbackComparison(matched = true)
        val missing = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        for ((key, expectedValue) in expected) {
            if (!actual.containsKey(key)) {
                missing += key
            } else if (!valuesEqual(expectedValue, actual[key])) {
                mismatched += key
            }
        }
        return ReadbackComparison(
            matched = missing.isEmpty() && mismatched.isEmpty(),
            missingKeys = missing.sorted(),
            mismatchedKeys = mismatched.sorted(),
        )
    }

    /**
     * 回传 readback：单段平铺；多段按 `<kind>` 嵌套（段顺序保持 manifest 顺序）。
     * [nestWhenMultiple] 由调用方按「任务声明段数」传入——双段包即使只有一段应用成功，
     * 也保持嵌套结构，避免同名键与「单段包」混淆。
     */
    fun merge(segments: List<SegmentReadback>, nestWhenMultiple: Boolean = segments.size > 1): Map<String, Any> {
        if (segments.isEmpty()) return emptyMap()
        if (segments.size == 1 && !nestWhenMultiple) return segments.first().values
        return segments.associate { it.kind.wireName to it.values }
    }

    private fun valuesEqual(expected: Any?, actual: Any?): Boolean = when {
        expected is Number && actual is Number -> numbersEqual(expected, actual)
        expected is Map<*, *> && actual is Map<*, *> -> {
            expected.keys == actual.keys && expected.all { (key, value) ->
                valuesEqual(value, actual[key])
            }
        }
        expected is List<*> && actual is List<*> -> {
            expected.size == actual.size && expected.indices.all { index ->
                valuesEqual(expected[index], actual[index])
            }
        }
        else -> expected == actual
    }

    private fun numbersEqual(expected: Number, actual: Number): Boolean =
        runCatching {
            BigDecimal(expected.toString()).compareTo(BigDecimal(actual.toString())) == 0
        }.getOrDefault(expected.toDouble() == actual.toDouble())
}

/** 单段实际回读指标（用于整包 readback 装配与按段比较）。 */
data class SegmentReadback(val kind: DeviceSegmentKind, val values: Map<String, Any>)

/** 回读比较结果；[matched] 为 true 时两个列表都为空。 */
data class ReadbackComparison(
    val matched: Boolean,
    val missingKeys: List<String> = emptyList(),
    val mismatchedKeys: List<String> = emptyList(),
)
