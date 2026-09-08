package media.qimeng.app.core.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 胶囊软底文本输入框（任务 G6：Web 胶囊设计语言的安卓输入面统一件）。
 *
 * 对齐口径：旧版 bg_capsule_soft（全圆角 100dp=[QimengDimens.PillCornerRadius] +
 * surfaceVariant 软底 + 无描边 indicator），等价 Web 端 999px 圆角软底输入框（.hist-search）。
 *
 * 焦点反馈取舍：indicator 描边三态全透明后聚焦必须有可见反馈，选
 * focusedContainerColor=surfaceContainerHigh——本项目 Theme 双态主题该槽位均比 surfaceVariant
 * 深一档（浅色=ChipBgLight），聚焦时底色加深一档、不恢复描边，保住「无描边软底」整体语言
 * （另一方案 1dp surfaceVariant 描边会重新引入描边观感，弃用）。
 *
 * label 走 placeholder 语义，对齐 Web：不透传 label（OutlinedTextField 的浮动 label 依附于
 * 描边切角，胶囊软底无描边可依附；Web 端输入框一律 placeholder 提示），调用方一律用
 * [placeholder]。
 *
 * 高度取舍：不设高度档常量、不强约束高度——Material OutlinedTextField 默认最小高 56dp，
 * 旧版 34dp 不满足 48dp 最小触摸目标；只约束圆角与颜色，保留 Material 默认触摸目标防误触。
 *
 * 参数面：只透传常用面（见签名），不开放 label/supportingText/isError 等全量面；
 * keyboardActions/enabled 虽不在最小面清单内，但为保调用方既有行为所必需
 * （搜索页 IME Search 提交、登录页提交中禁用输入）。
 *
 * 消费方清单：feature/search 搜索框、feature/author 作者搜索框、feature/login 两字段、
 * feature/upload 新建目录、feature/detail 标签新建 + 时间轴标签命名、
 * core:ui QimengFilterSheet 添加标签（core:ui 自家消费）。
 */
@Composable
fun QimengCapsuleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        placeholder = if (placeholder != null) {
            { Text(text = placeholder) }
        } else {
            null
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        colors = OutlinedTextFieldDefaults.colors(
            // 软底三态：未聚焦/禁用=surfaceVariant（bg_capsule_soft 同款），聚焦加深一档（见头部 KDoc）
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            // 无描边 indicator：三态全透明（胶囊软底语言的核心，见头部 KDoc）
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
        ),
    )
}
