package media.qimeng.app.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import media.qimeng.app.core.model.STATS_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.ui.component.Dimens

/** 失败不复用空态，主/详情两页共用明确错误与同档重试入口。 */
@Composable
internal fun StatsLoadError(message: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message ?: STATS_LOAD_FAILED_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
