package media.qimeng.app.core.model

/**
 * 词表维护的编辑域模型（ADR-0035；2026-10-24 SDK 隔离批）：词表维护页内存编辑副本的
 * 端内形态——feature 层 UI/VM 只见本模型，SDK 生成物（CustomSourceGroups 族）的双向
 * 映射唯一收口在 core:data（VocabularyEditMappers），页面不再暴露协议可省字段的 null 形态。
 *
 * 列表在端内统一非 null：协议「可省」子列表（variants/characters/aliases/stopWords）
 * 读入时 null 归一为 emptyList，端内增删始终得到空表而非 null；「空子列表 → 协议可省
 * （序列化省略）」的归一时机后移到保存映射（mapper 单测锁定），语义与既有直接编辑
 * SDK 对象时一致——空数组=清空，不是「不提交=保留」。
 *
 * 纯数据类：不依赖 SDK/Android/IO（铁律 3/7）。groups/stopWords 保存恒显式数组、
 * 整体替换（PUT 语义），不携带增删补丁形态。
 */
data class EditableVocabulary(
    /** 自定义出处组（编辑副本；保存前不代表服务端生效形态） */
    val groups: List<VocabularyGroup> = emptyList(),
    /** 停用词追加层（恒非 null；空数组 = 保存时显式清空回内置基线） */
    val stopWords: List<String> = emptyList(),
)

/** 单个出处组：规范名 + 变体写法 + 角色检索表（子列表端内恒非 null） */
data class VocabularyGroup(
    val canonical: String,
    val variants: List<String> = emptyList(),
    val characters: List<VocabularyCharacter> = emptyList(),
)

/** 单个角色：规范名 + 别名（端内恒非 null） */
data class VocabularyCharacter(
    val canonical: String,
    val aliases: List<String> = emptyList(),
)
