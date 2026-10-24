package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.EditableVocabulary
import media.qimeng.app.core.model.VocabularyCharacter
import media.qimeng.app.core.model.VocabularyGroup
import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups

/**
 * 词表编辑域模型 ↔ SDK 生成物的唯一双向映射（2026-10-24 SDK 隔离批，ADR-0035）。
 * 只允许 core:data 引用；feature 层经 [VocabularyEditRepository] 拿到的恒为
 * [EditableVocabulary]，不再触达协议可省字段的 null 形态。
 *
 * 映射语义与隔离前「VM 直接编辑 SDK 对象」严格等价（mapper 单测锁定）：
 * - 读入（GET）：协议可省子列表 null → 端内 emptyList；
 * - 保存（PUT）：端内空子列表 → 协议可省（null=序列化省略），非空原样透传；
 * - groups/stopWords 恒显式数组（整体替换语义）：端内空 → 显式空数组
 *   （stopWords 空数组=清空回内置基线，不是「不提交=保留」）。
 *
 * 两端并集合并（[VocabularyMerger]）仍直接操作 SDK 对象（词表同步链路不经过本
 * mapper，同步算法不因编辑面类型隔离而改动）。
 */

/** GET 响应 → 编辑域模型（可省子列表归一为空表） */
internal fun CustomSourceGroups.toEditableVocabulary() = EditableVocabulary(
    groups = groups.map { group ->
        VocabularyGroup(
            canonical = group.canonical,
            variants = group.variants.orEmpty(),
            characters = group.characters.orEmpty().map { character ->
                VocabularyCharacter(character.canonical, character.aliases.orEmpty())
            },
        )
    },
    stopWords = stopWords.orEmpty(),
)

/** 编辑域模型 → PUT 载荷（空子列表回协议可省；groups/stopWords 恒显式数组） */
internal fun EditableVocabulary.toSdk() = CustomSourceGroups(
    groups = groups.map { group ->
        CustomSourceGroup(
            canonical = group.canonical,
            variants = group.variants.ifEmpty { null },
            characters = group.characters.map { character ->
                CustomSourceCharacter(
                    canonical = character.canonical,
                    aliases = character.aliases.ifEmpty { null },
                )
            }.ifEmpty { null },
        )
    },
    // 显式传参（即使为空）而非用构造默认值：PUT 语义要求空数组=清空，缺省=保持不变
    stopWords = stopWords,
)
