package media.qimeng.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.ui.component.QimengPageIntro
import media.qimeng.app.core.ui.component.QimengSettingsGroup
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.glass.TabDockDefaults
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.softDaylightBackground

internal const val APPEARANCE_TITLE = "外观"

/** 外观沿用原偏好流，选择即生效；纵向选项兼容窄屏和大字体。 */
@Composable
internal fun AppearanceScreen(
    appearanceMode: AppearanceMode,
    tabBarMaterial: TabBarMaterial,
    onAppearanceModeSelect: (AppearanceMode) -> Unit,
    onTabMaterialSelect: (TabBarMaterial) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().softDaylightBackground()) {
        QimengTopBar(title = APPEARANCE_TITLE, onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = TabDockDefaults.bottomClearance()),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            QimengPageIntro("选择你喜欢的主题和导航质感，点选后立即生效。")
            AppearanceOptions(
                title = stringResource(R.string.settings_appearance_title),
                options = listOf(
                    stringResource(R.string.settings_appearance_system) to "随设备的浅色与深色模式切换",
                    stringResource(R.string.settings_appearance_light) to "明亮白色卡面，清晰轻盈",
                    stringResource(R.string.settings_appearance_dark) to "深色背景，适合暗光浏览",
                ),
                selectedIndex = AppearanceMode.entries.indexOf(appearanceMode),
                onSelect = { onAppearanceModeSelect(AppearanceMode.entries[it]) },
            )
            AppearanceOptions(
                title = stringResource(R.string.settings_tab_material_title),
                options = listOf(
                    stringResource(R.string.settings_tab_material_liquid) to "通透流动的玻璃质感",
                    stringResource(R.string.settings_tab_material_frosted) to "柔和模糊，减轻背景干扰",
                    stringResource(R.string.settings_tab_material_solid) to "纯色悬浮，强调清晰边界",
                    stringResource(R.string.settings_tab_material_classic) to "简洁熟悉的经典导航",
                ),
                selectedIndex = TabBarMaterial.entries.indexOf(tabBarMaterial),
                onSelect = { onTabMaterialSelect(TabBarMaterial.entries[it]) },
            )
        }
    }
}

@Composable
private fun AppearanceOptions(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    QimengSettingsGroup(title) {
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { index, (label, description) ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .heightIn(min = QimengDimens.ProfileRowHeight)
                        .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceM),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceL),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceXXS)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = selected, onClick = null)
                }
            }
        }
    }
}
