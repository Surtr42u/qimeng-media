package media.qimeng.app.core.data.repository

import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups

/**
 * 词表并集合并引擎（ADR-0035 定稿语义，2026-10-04 用户拍板）：**词表是只增不删的
 * 数据**——与媒体库/统计不同，词条只有「补录」没有「删除」工作流，两端并集合并是
 * 无损增量，不会出现覆盖式同步的丢失问题。本对象是纯函数（无 IO，行为由单测锁定）。
 *
 * 合并规则（对齐匹配引擎折叠口径与服务端 PUT 规范化）：
 * - 折叠键 = trim + 忽略大小写（canonical/变体/角色名/别名/停用词同口径）；
 * - 出处组按折叠键并集：远端在前、本机独有组按原顺序追加；同名（同键）组并入——
 *   规范名保留先出现方的原始写法、变体并集、角色按折叠键并集（别名并集）；
 * - 变体/别名去重含「不与规范名自身重复」（服务端 canonical 自并入自身变体语义的
 *   客户端等价：GET 回读数据里规范名可能重复出现在变体中，合并时滤掉，PUT 后服务端
 *   规范化会重新补上，最终存储形态一致）；
 * - 空表回 null（协议「可省」字段归一）。
 *
 * 合并结果对两端各 PUT 一次（显式整体替换），两端收敛到同一并集——同步幂等，中断
 * 后重跑即收敛。
 */
object VocabularyMerger {

    /** 折叠键：trim + 忽略大小写（引擎大小写不敏感折叠口径，与同步预览对照同源） */
    private fun String.foldKey(): String = trim().lowercase()

    /** 两端词表并集合并（远端在前 = 规范名展示形态与组序以远端为先；空并集回 null=协议可省） */
    fun merge(remote: CustomSourceGroups, local: CustomSourceGroups): CustomSourceGroups =
        CustomSourceGroups(
            groups = mergeGroups(remote.groups, local.groups),
            stopWords = mergeStrings(remote.stopWords.orEmpty(), local.stopWords.orEmpty()).ifEmpty { null },
        )

    private fun mergeGroups(
        remote: List<CustomSourceGroup>,
        local: List<CustomSourceGroup>,
    ): List<CustomSourceGroup> {
        val mergedByKey = LinkedHashMap<String, GroupBuilder>()
        (remote.asSequence() + local.asSequence()).forEach { group ->
            val key = group.canonical.foldKey()
            val existing = mergedByKey[key]
            if (existing == null) {
                mergedByKey[key] = GroupBuilder(group)
            } else {
                existing.absorb(group)
            }
        }
        return mergedByKey.values.map { it.build() }
    }

    private fun mergeStrings(remote: List<String>, local: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<String>()
        (remote.asSequence() + local.asSequence()).forEach { value ->
            val key = value.foldKey()
            if (key !in seen) {
                seen.add(key)
                merged.add(value)
            }
        }
        return merged
    }

    /** 同键组归并器：规范名取先出现方，变体并集（折叠去重），角色按折叠键并集 */
    private class GroupBuilder(group: CustomSourceGroup) {
        val canonical: String = group.canonical
        private val seenVariantKeys = mutableSetOf(canonical.foldKey())
        private val variants = mutableListOf<String>()
        private val characters = LinkedHashMap<String, CharacterBuilder>()

        init {
            absorb(group)
        }

        fun absorb(other: CustomSourceGroup) {
            other.variants.orEmpty().forEach { variant ->
                val key = variant.foldKey()
                if (key !in seenVariantKeys) {
                    seenVariantKeys.add(key)
                    variants.add(variant)
                }
            }
            other.characters.orEmpty().forEach { character ->
                characters.getOrPut(character.canonical.foldKey()) { CharacterBuilder(character) }
                    .absorb(character)
            }
        }

        fun build(): CustomSourceGroup = CustomSourceGroup(
            canonical = canonical,
            variants = variants.ifEmpty { null },
            characters = characters.values.map { it.build() }.ifEmpty { null },
        )
    }

    /** 同键角色归并器：规范名取先出现方，别名并集（折叠去重，不含规范名自身） */
    private class CharacterBuilder(character: CustomSourceCharacter) {
        val canonical: String = character.canonical
        private val seenAliasKeys = mutableSetOf(canonical.foldKey())
        private val aliases = mutableListOf<String>()

        init {
            absorb(character)
        }

        fun absorb(other: CustomSourceCharacter) {
            other.aliases.orEmpty().forEach { alias ->
                val key = alias.foldKey()
                if (key !in seenAliasKeys) {
                    seenAliasKeys.add(key)
                    aliases.add(alias)
                }
            }
        }

        fun build(): CustomSourceCharacter = CustomSourceCharacter(
            canonical = canonical,
            aliases = aliases.ifEmpty { null },
        )
    }
}
