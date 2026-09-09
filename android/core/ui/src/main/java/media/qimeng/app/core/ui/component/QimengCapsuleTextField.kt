package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 胶囊软底文本输入框（任务 G6：Web 胶囊设计语言的安卓输入面统一件；F 批 2026-09-09 尺寸对齐旧版）。
 *
 * 对齐口径：旧版 bg_capsule_soft（全圆角 100dp=[QimengDimens.PillCornerRadius] +
 * surfaceVariant 软底），等价 Web 端 999px 圆角软底输入框（.hist-search）——旧版本体就是
 * EditText + bg_capsule_soft 背景，本组件 F 批起用 foundation [BasicTextField] + 自绘
 * decorationBox 复刻同构（容器/占位/图标布局全部自持，34dp 内不依赖 M3 内部留白）。
 *
 * 为什么不再用 M3 OutlinedTextField/TextField 承载（F 批重写的根因）：material3 1.4.0
 * （BOM 2026.06.01）的无 label 输入框无高度约束时内容高实测 80dp——内部文本区被
 * minimumInteractiveComponentSize 撑到 48dp + 默认 contentPadding 上下各 16dp
 * （TextFieldImplKt TextFieldPadding）= 80dp，直接顶穿 TextFieldDefaults.MinHeight=56dp
 * 下限；而 contentPadding 参数只在 TextFieldState 新态重载上开放（String 经典重载无此参数，
 * 34dp 固定高下内部留白会把文字压到 2dp），新态重载又无 visualTransformation/password
 * 与 KeyboardActions 面（登录页密码、搜索页 IME 提交都依赖）——M3 标准件塞不进 34dp，
 * 换 BasicTextField 自绘是最小破坏面：公开签名零变化，9 处消费方零改动。
 *
 * 高度取舍（F 批 2026-09-09 重拍）：固定 34dp=[QimengDimens.CapsuleFieldHeight]（旧版
 * fragment_search.xml L29 searchInput layout_height=34dp，bg_capsule_soft 全仓输入面统一语言）。
 * G6 时期「不约束高度、保留 56dp 触摸目标」的取舍已被用户 2026-09-09 反馈（「搜索的胶囊 ui
 * 都过大，完全不符合旧版视觉」）推翻，旧版视觉优先。三件套：
 *  1. [Modifier.height] 固定 34dp（M3 布局机制不再参与，本组件全权决定）；
 *  2. CompositionLocal(LMinimumInteractiveComponentSize=0.dp) 兜底消交互面积下限
 *     （旧版 chipMinTouchTargetSize=0dp 的 Compose 等价）；
 *  3. 横向留白 14dp=旧版 paddingHorizontal（fragment_search.xml L40），文字纵向居中
 *     （decorationBox Row CenterVertically，旧版 EditText gravity 同语义）。
 * 字号 bodyMedium 14sp=旧版 textSize 14sp（fragment_search.xml L36）；placeholder 色
 * onSurfaceVariant=旧版 qmColorTextSecondary。
 *
 * 焦点反馈（保 G6 语义）：无描边语言下聚焦=底色加深一档 surfaceVariant→surfaceContainerHigh
 * （双态主题该槽位均比 surfaceVariant 深一档，浅色=ChipBgLight）；光标 primary。
 *
 * label 走 placeholder 语义，对齐 Web：不透传 label（浮动 label 依附描边/填充线，胶囊软底
 * 无可依附；Web 端输入框一律 placeholder 提示），调用方一律用 [placeholder]。
 *
 * 参数面：只透传常用面（见签名），不开放 label/supportingText/isError 等全量面；
 * keyboardActions/enabled 虽不在最小面清单内，但为保调用方既有行为所必需
 * （搜索页 IME Search 提交、登录页提交中禁用输入）。
 *
 * 消费方清单：feature/search 搜索框、feature/author 作者搜索框、feature/login 两字段、
 * feature/upload 新建目录、feature/detail 标签新建 + 时间轴标签命名 + 改名/整理目标输入、
 * core:ui QimengFilterSheet 添加标签（core:ui 自家消费）——全部跟随 34dp，即旧版全局
 * bg_capsule_soft 胶囊语言，不是回退。
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
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(QimengDimens.PillCornerRadius)
    // 软底两态：未聚焦/禁用=surfaceVariant（bg_capsule_soft 同款），聚焦加深一档（见头部 KDoc）
    val containerColor = if (focused) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.height(QimengDimens.CapsuleFieldHeight),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 横向 14dp=旧版 paddingHorizontal；纵向零留白由 Row 垂直居中承担（34dp 固定高）
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor, shape)
                        .padding(horizontal = QimengDimens.ChipHorizontalPadding),
                ) {
                    leadingIcon?.invoke()
                    if (leadingIcon != null) {
                        Spacer(modifier = Modifier.width(QimengDimens.SpaceM))
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // 空值时内层文本零宽，placeholder 与其叠放（M3 TextField 同款叠放语义）
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
    }
}
