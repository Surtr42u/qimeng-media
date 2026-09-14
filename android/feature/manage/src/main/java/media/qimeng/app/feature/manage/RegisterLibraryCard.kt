package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.LibraryKind
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/** 注册新库卡文案（对齐 Web LibraryManagePage.tsx 注册表单 settings-card 的字段与提示逐字） */
private const val REGISTER_TITLE = "注册新库"
private const val REGISTER_DESC = "把服务端可访问的磁盘文件夹登记为媒体库，注册后自动扫描入库"
private const val FORM_NAME_LABEL = "库名称"
private const val FORM_NAME_PLACEHOLDER = "例如：1 图集"
private const val FORM_PATH_LABEL = "根路径（服务端可访问的绝对路径）"
private const val FORM_PATH_PLACEHOLDER = "C:\\Users\\你\\Desktop\\某目录"

/** 路径提示（Web 表单 small 注：数据目录红线 + COS 目录结构约定） */
private const val FORM_PATH_NOTE = "不能位于服务端数据目录内；COS 库按「作者/作品/文件」目录结构组织"
private const val FORM_KIND_LABEL = "库类型"

/** 类型两档选项文案（Web Select 两项逐字；选中后行徽标走 core:model displayLabel=COS/常规） */
internal const val KIND_OPTION_NORMAL = "常规"
internal const val KIND_OPTION_COS = "COS 作者库"

private const val REGISTER_BUTTON = "注册并扫描"
private const val REGISTERING_BUTTON = "注册中…"

/** 卡内字段纵向间距（dp）：表单卡统一 8dp 节奏（上传子页列间距同档） */
private val CardInnerSpacing = 8.dp

/** 卡内四向内边距（dp）：与库列表行卡一致的内密（视觉语言=上传子页 Card padding 8/12 档的中档） */
private val CardInnerPadding = 16.dp

/**
 * 「注册新库」表单卡（U10-6）：名称 + 根路径 + 类型两档 + 注册并扫描钮。
 * 注册 = 手输 rootPath 对齐 Web（服务端任意路径浏览协议无端点，已砍项记档）。
 * 表单状态全部由 ViewModel 持有（UI 零内嵌业务规则，铁律 7），本卡纯受控渲染。
 */
@Composable
internal fun RegisterLibraryCard(
    name: String,
    rootPath: String,
    kind: LibraryKind,
    registering: Boolean,
    canSubmit: Boolean,
    onNameChange: (String) -> Unit,
    onRootPathChange: (String) -> Unit,
    onKindChange: (LibraryKind) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardInnerSpacing),
        ) {
            Text(REGISTER_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                text = REGISTER_DESC,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FieldLabel(FORM_NAME_LABEL)
            QimengCapsuleTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = FORM_NAME_PLACEHOLDER,
                singleLine = true,
                enabled = !registering,
            )
            FieldLabel(FORM_PATH_LABEL)
            QimengCapsuleTextField(
                value = rootPath,
                onValueChange = onRootPathChange,
                placeholder = FORM_PATH_PLACEHOLDER,
                singleLine = true,
                enabled = !registering,
            )
            Text(
                text = FORM_PATH_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FieldLabel(FORM_KIND_LABEL)
            Row(horizontalArrangement = Arrangement.spacedBy(CardInnerSpacing)) {
                QimengSegPill(
                    text = KIND_OPTION_NORMAL,
                    selected = kind == LibraryKind.NORMAL,
                    onClick = { onKindChange(LibraryKind.NORMAL) },
                )
                QimengSegPill(
                    text = KIND_OPTION_COS,
                    selected = kind == LibraryKind.COS,
                    onClick = { onKindChange(LibraryKind.COS) },
                )
            }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                // 主按钮通栏容器色与上传页同源（夜间不透明底消 dither，W6 #49 同口径）
                colors = qimengFilledButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (registering) REGISTERING_BUTTON else REGISTER_BUTTON)
            }
        }
    }
}

/** 字段标签（Web settings-field 的 span 同位；labelLarge 档与表单小标签语义对齐） */
@Composable
private fun FieldLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}
