package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.softDaylightShadow

/** 我的及管理子页共用的简介；只渲染内容，不持有页面状态。 */
@Composable
fun QimengPageIntro(description: String, modifier: Modifier = Modifier) {
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = QimengDimens.SpaceL),
    )
}

/** 同类入口共用一个卡面，避免长设置列表的每一行都形成独立视觉层。 */
@Composable
fun QimengSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = QimengDimens.SpaceXS,
                top = QimengDimens.SpaceL,
                bottom = QimengDimens.SpaceM,
            ),
        )
        Surface(
            shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().softDaylightShadow(),
        ) { Column(content = content) }
    }
}

/** 整行触控/键盘入口；装饰图标不重复朗读，长副标题自然换行。 */
@Composable
fun QimengSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .heightIn(min = QimengDimens.ProfileRowHeight)
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceL),
        horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(QimengDimens.SpaceL),
            modifier = Modifier.size(SettingsIconContainerSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(SettingsIconSize),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXXS)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 设置入口的图标尺寸；行高继续复用全局触控规格。
private val SettingsIconContainerSize = 36.dp
private val SettingsIconSize = 20.dp
