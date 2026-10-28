package media.qimeng.app.feature.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.theme.QimengDimens

/** 上传各任务区的统一卡面与文字层级；不持有上传或表单状态。 */
@Composable
internal fun UploadPanel(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    QimengSettingsCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(QimengDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}
