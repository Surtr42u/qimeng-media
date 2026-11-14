package media.qimeng.app.feature.detail

import java.text.Collator
import java.util.Locale
import media.qimeng.app.core.model.TagChip

/** 仅用于详情标签池展示：中文拼音顺序，连续数字按数值排列；不依赖勾选状态。 */
internal fun orderDetailTags(tags: List<TagChip>): List<TagChip> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    val parts = tags.associate { it.id to TAG_NAME_PARTS.findAll(it.name).map { part -> part.value }.toList() }
    return tags.sortedWith { left, right ->
        val a = parts.getValue(left.id)
        val b = parts.getValue(right.id)
        var result = 0
        for (index in 0 until minOf(a.size, b.size)) {
            val x = a[index]
            val y = b[index]
            result = if (x.first() in '0'..'9' && y.first() in '0'..'9') {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                compareValues(nx.length, ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else {
                collator.compare(x, y)
            }
            if (result != 0) break
        }
        if (result == 0) result = compareValues(a.size, b.size)
        if (result == 0) result = collator.compare(left.name, right.name)
        if (result == 0) result = left.id.compareTo(right.id)
        result
    }
}

private val TAG_NAME_PARTS = Regex("[0-9]+|[^0-9]+")
