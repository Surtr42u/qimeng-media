package media.qimeng.app.feature.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import media.qimeng.app.core.model.BrowsingReviewValues
import media.qimeng.app.core.ui.theme.softDaylightShadow
import media.qimeng.app.core.ui.icon.ChevronRightIcon

/** 手机主页只显示名称；进入详情前不请求回顾接口。 */
@Composable
internal fun BrowsingReviewEntry(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(color = MaterialTheme.colorScheme.surface, shape = shape,
        modifier = Modifier.fillMaxWidth().softDaylightShadow(shape).clip(shape).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("浏览回顾", style = MaterialTheme.typography.titleMedium)
            Icon(ChevronRightIcon, contentDescription = null)
        }
    }
}

@Composable
internal fun BrowsingReviewContent(review: BrowsingReviewValues) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReviewSection {
            Text("探索与回访", style = MaterialTheme.typography.titleMedium)
            Text(if (review.range == "all") "全部已记录浏览" else "${review.fromDay} — ${review.toDay}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("本期浏览 ${review.viewCount} 次" + if (review.range != "all") "  ·  上期 ${review.previousViewCount} 次" else "",
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReviewMetric("首次浏览", review.firstViews, "次", Modifier.weight(1f))
                ReviewMetric("再次浏览", review.repeatViews, "次", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReviewMetric("浏览内容", review.uniqueFiles, "个", Modifier.weight(1f))
                ReviewMetric("活跃天数", review.activeDays, "天", Modifier.weight(1f))
            }
            Text(if (review.viewCount == 0L) "这个时段还没有浏览记录" else "每个内容的首条浏览记为首次，其后的打开记为再次浏览。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReviewSection {
            Text("兴趣变化", style = MaterialTheme.typography.titleMedium)
            when {
                review.range == "all" -> Text("选择近7天或近30天，查看与前一时段的变化。")
                !review.comparisonAvailable -> Text("前后两个时段都有浏览记录后，这里会显示兴趣变化。")
                review.interests.isEmpty() -> Text("与前一时段相比，兴趣份额没有变化。")
                else -> {
                    Text("对比 ${review.previousFromDay} — ${review.previousToDay}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    review.interests.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (item.kind == "author") "作者" else "标签"} · ${item.name}", style = MaterialTheme.typography.titleSmall)
                            Text(String.format(Locale.CHINA, "%s %.1f 个百分点", if (item.changePoints > 0) "↗ 份额上升" else "↘ 份额下降", kotlin.math.abs(item.changePoints)),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            ReviewShareRow("上期", item.previousViews, item.previousShare, previous = true)
                            ReviewShareRow("本期", item.currentViews, item.currentShare, previous = false)
                        }
                    }
                }
            }
            Text("份额按关联浏览占本期全部浏览计算，同一内容可关联多个作者或标签。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReviewSection {
            Text("活跃时段", style = MaterialTheme.typography.titleMedium)
            Text("按服务端本地时间汇总已记录的浏览次数", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            var selectedHour by remember { mutableIntStateOf(0) }
            val peak = (review.hours.maxOfOrNull { it.views } ?: 0L).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.height(112.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatCountCompact(peak)}次", style = MaterialTheme.typography.labelSmall)
                    Text("0", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        review.hours.forEach { hour ->
                            Surface(color = if (hour.hour == selectedHour) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).height((hour.views.toFloat() / peak * 100).dp).clip(RoundedCornerShape(3.dp))
                                    .semantics { contentDescription = "${hour.hour}时，${hour.views}次" }) { Box(Modifier) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        review.hours.forEach { hour ->
                            Text(if (hour.hour in listOf(0, 6, 12, 18, 23)) hour.hour.toString() else "", Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
            Text(String.format(Locale.CHINA, "%02d:00–%02d:00 · %d 次浏览", selectedHour, selectedHour + 1, review.hours[selectedHour].views),
                style = MaterialTheme.typography.bodyMedium)
            Slider(value = selectedHour.toFloat(), onValueChange = { selectedHour = kotlin.math.round(it).toInt() }, valueRange = 0f..23f, steps = 22,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "查看小时" })
            Text("导入历史按保存的记录时间统计。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewShareRow(label: String, views: Long, share: Double, previous: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label · $views 次浏览", style = MaterialTheme.typography.bodySmall)
            Text(String.format(Locale.CHINA, "%.1f%%", share), style = MaterialTheme.typography.labelMedium)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().height(8.dp)) {
            Box {
                Surface(color = if (previous) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth((share / 100).toFloat()).height(8.dp), shape = RoundedCornerShape(4.dp)) {}
            }
        }
    }
}

@Composable
private fun ReviewMetric(title: String, value: Long, unit: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${formatCountCompact(value)} $unit", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(color = MaterialTheme.colorScheme.surface, shape = shape,
        modifier = Modifier.fillMaxWidth().softDaylightShadow(shape)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
