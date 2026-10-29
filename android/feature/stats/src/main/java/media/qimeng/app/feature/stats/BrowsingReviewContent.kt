package media.qimeng.app.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import media.qimeng.app.core.model.BrowsingReviewValues
import media.qimeng.app.core.ui.theme.QimengBrandColors
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
    var showInterestDetails by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReviewActivity(review)
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
                    Text("浏览份额 · 越靠右越常看\n○ 上期　● 本期", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    review.interests.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(if (item.kind == "author") "作者" else "标签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (item.changePoints > 0) "↗ 更多关注" else "↙ 较少关注",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ReviewInterestTrack(item.previousShare, item.currentShare)
                            if (showInterestDetails) Text(String.format(Locale.CHINA,
                                "上期 %.1f%% · %d 次 → 本期 %.1f%% · %d 次\n%s %.1f 个百分点",
                                item.previousShare, item.previousViews, item.currentShare, item.currentViews,
                                if (item.changePoints > 0) "上升" else "下降", kotlin.math.abs(item.changePoints)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { showInterestDetails = !showInterestDetails }) {
                        Text(if (showInterestDetails) "收起详细数据 −" else "查看详细数据 +")
                    }
                }
            }
            Text("同一刻度对比前一等长时段（0–100%）；作者与标签可多关联。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 固定0–100刻度，仅将服务端份额映射为位置。 */
@Composable
private fun ReviewInterestTrack(previous: Double, current: Double) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val description = String.format(Locale.CHINA, "浏览份额，上期 %.1f%%，本期 %.1f%%", previous, current)
    Canvas(Modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics { contentDescription = description }) {
        val radius = 5.dp.toPx()
        val start = radius + 1.dp.toPx()
        val width = size.width - start * 2
        val before = Offset(start + width * previous.toFloat() / 100, center.y)
        val after = Offset(start + width * current.toFloat() / 100, center.y)
        drawLine(track.copy(alpha = 0.5f), Offset(start, center.y), Offset(size.width - start, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(ink.copy(alpha = 0.5f), before, after, 3.dp.toPx(), StrokeCap.Round)
        drawCircle(surface, radius, before)
        drawCircle(muted, radius, before, style = Stroke(1.5.dp.toPx()))
        drawCircle(surface, radius + 1.dp.toPx(), after)
        drawCircle(ink, radius, after)
    }
}

@Composable
private fun ReviewActivity(review: BrowsingReviewValues) {
    ReviewSection {
        var selectedHour by remember { mutableStateOf<Int?>(null) }
        val peak = (review.hours.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)
        val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        val colors = listOf(QimengBrandColors.ActivityBlue, QimengBrandColors.ActivityVideo, QimengBrandColors.ActivityAnimated)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when (review.range) { "all" -> "全部"; "day" -> "近30天"; "90d" -> "近90天"; else -> "近7天" }, fontSize = 24.sp)
            ReviewDurationText(review.seconds, large = true)
        }
        Column(Modifier.padding(top = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                review.hours.forEach { hour ->
                    Text(if (hour.hour in listOf(0, 6, 12, 18)) String.format(Locale.CHINA, "%02d", hour.hour) else "", Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth().height(136.dp).drawBehind {
                for (line in 0..2) { val y = size.height * line / 2f; drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx()) }
                for (line in 0..3) { val x = size.width * line / 4f; drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx()) }
            }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                review.hours.forEach { hour ->
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .background(if (hour.hour == selectedHour) colors[0].copy(alpha = 0.09f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button) { selectedHour = hour.hour }
                        .semantics { contentDescription = "${hour.hour}时，${hour.seconds}秒，${hour.views}次浏览"; selected = hour.hour == selectedHour }, contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxWidth().height((hour.seconds.toFloat() / peak * 136).dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))) {
                            listOf(hour.imageSeconds, hour.videoSeconds, hour.animatedSeconds).forEachIndexed { index, seconds ->
                                Box(Modifier.fillMaxWidth().height((seconds.toFloat() / peak * 136).dp).background(colors[index]))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("图片" to review.imageSeconds, "视频" to review.videoSeconds, "动图" to review.animatedSeconds).forEachIndexed { index, (name, seconds) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(colors[index]))
                        Text(name, fontSize = 16.sp, color = colors[index])
                    }
                    Text(formatDurationDetail(seconds), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(selectedHour?.let { String.format(Locale.CHINA, "%02d:00–%02d:00 · %s · %d 次浏览", it, it + 1, formatDurationDetail(review.hours[it].seconds), review.hours[it].views) } ?: "点击柱形查看小时详情",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("按服务端本地时间 · 统计详情停留，视频不等同播放时长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewDurationText(seconds: Long, large: Boolean) {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    Row(verticalAlignment = Alignment.Bottom) {
        if (hours > 0) { Text(hours.toString(), fontSize = if (large) 32.sp else 12.sp); Text("小时", Modifier.padding(bottom = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text((if (seconds >= 60) minutes else seconds).toString(), fontSize = if (large) 32.sp else 12.sp)
        Text(if (seconds >= 60) "分钟" else "秒", Modifier.padding(bottom = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
