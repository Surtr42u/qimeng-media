package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.component.QimengCapsuleTextField

/**
 * 协议容量投影（api/openapi.yaml CustomSourceGroups 族 maxItems/maxLength，ADR-0033 定）。
 * 词表维护页输入框与「新增」按钮按此封顶，超限会被服务端 400 拒收——协议侧改动须同步
 * 此处，反之亦然。
 */
internal object VocabularyLimits {

    /** 单串上限（canonical/变体/角色名/别名/停用词同档 100 rune） */
    const val TEXT_MAX = 100

    /** 出处组数上限 */
    const val GROUPS_MAX = 256

    /** 单组变体数上限 */
    const val VARIANTS_MAX = 64

    /** 单组角色数上限 */
    const val CHARACTERS_MAX = 128

    /** 单角色别名数上限 */
    const val ALIASES_MAX = 32

    /** 停用词数上限 */
    const val STOP_WORDS_MAX = 256
}

/**
 * 单输入框对话框请求（词表维护页唯一的文本录入机制）：新增出处组/组改名/新增变体/
 * 新增角色/角色改名/新增别名/新增停用词共用，差异只在标题、初值与确认回调。
 * 确认传**未 trim 原文**——去空/去重是服务端 PUT 规范化职责（存储形态=生效形态），
 * UI 只拦空白提交；展示层若与保存后规范化形态有差异，由保存后静默回读抹平。
 */
internal class VocabularyPrompt(
    val title: String,
    val initial: String = "",
    val onConfirm: (String) -> Unit,
)

/** 单输入框对话框（确认钮空白禁用；字数按 [VocabularyLimits.TEXT_MAX] 封顶。
 *  输入面用 QimengCapsuleTextField（全仓输入面统一语言，bg_capsule_soft 胶囊软底）——
 *  用户反馈「添加框太复古」的定稿：不再用 M3 OutlinedTextField 描边框，placeholder 提示
 *  对齐 Web 端输入面口径。
 *  容器色显式落 surface（卡片面）：M3 AlertDialog 默认容器 = surfaceContainerHigh，与
 *  本主题夜间胶囊底 surfaceVariant 同档合并（旧版灰阶四档 1A/24/2E/38，见 Theme.kt），
 *  夜间弹窗里胶囊与弹窗底同色 #2E2E2E 完全隐形（用户反馈「夜间胶囊不太行」根因）——
 *  落 surface 恢复「页面底 1A < 弹窗 24 < 胶囊 2E」抬升梯度，浅色同构（弹窗纯白=卡片语言） */
@Composable
internal fun VocabularyPromptDialog(
    prompt: VocabularyPrompt,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(prompt) { mutableStateOf(prompt.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = prompt.title) },
        text = {
            Column {
                QimengCapsuleTextField(
                    value = text,
                    onValueChange = { if (it.length <= VocabularyLimits.TEXT_MAX) text = it },
                    placeholder = "输入词条（自动去除首尾空格）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${text.length}/${VocabularyLimits.TEXT_MAX}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    prompt.onConfirm(text)
                },
                enabled = text.isNotBlank(),
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

/** 未保存放弃确认对话框（返回拦截；「词表维护」返回/系统返回共用；容器色同
 *  [VocabularyPromptDialog] 落 surface——同页弹窗统一卡片面语言） */
@Composable
internal fun VocabularyDiscardDialog(
    onDiscard: () -> Unit,
    onStay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStay,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = "有未保存的修改") },
        text = {
            Text(
                text = "离开将丢失本次编辑，确定离开吗？",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(text = "放弃修改离开")
            }
        },
        dismissButton = {
            TextButton(onClick = onStay) {
                Text(text = "继续编辑")
            }
        },
    )
}
