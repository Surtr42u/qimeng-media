package media.qimeng.app.core.data.repository

import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 词表并集合并引擎纯函数锁定（ADR-0035 定稿语义）：折叠键 union / 同键组并入（规范名
 * 取先出现方、变体/别名并集折叠去重）/ 角色跨组合并 / 停用词并集 / 空表回 null /
 * 规范名自身不重复进变体（canonical 自并入语义的服务端等价）。零 IO（铁律 3 同款
 * 纯函数纪律）。
 */
class VocabularyMergerTest {

    @Test
    fun `不相交两端并集保序且远端在前`() {
        val remote = CustomSourceGroups(
            groups = listOf(CustomSourceGroup(canonical = "怪物猎人", variants = listOf("MHW"))),
            stopWords = listOf("触手"),
        )
        val local = CustomSourceGroups(
            groups = listOf(CustomSourceGroup(canonical = "战锤40k")),
            stopWords = listOf("白丝"),
        )
        val merged = VocabularyMerger.merge(remote, local)
        assertEquals(listOf("怪物猎人", "战锤40k"), merged.groups.map { it.canonical })
        assertEquals(listOf("MHW"), merged.groups[0].variants)
        assertNull(merged.groups[1].variants)
        assertEquals(listOf("触手", "白丝"), merged.stopWords)
    }

    @Test
    fun `同键组并入变体与角色并集且规范名取远端写法`() {
        val remote = CustomSourceGroups(
            groups = listOf(
                CustomSourceGroup(
                    canonical = "D.Mon",
                    variants = listOf("Dmon"),
                    characters = listOf(
                        CustomSourceCharacter(canonical = "杰玛", aliases = listOf("Gemma")),
                    ),
                ),
            ),
        )
        val local = CustomSourceGroups(
            groups = listOf(
                CustomSourceGroup(
                    canonical = "d.mon",
                    variants = listOf("DMON", "戴萌"),
                    characters = listOf(
                        CustomSourceCharacter(canonical = "杰玛", aliases = listOf("杰玛酱")),
                        CustomSourceCharacter(canonical = "艾露猫"),
                    ),
                ),
            ),
        )
        val merged = VocabularyMerger.merge(remote, local)
        val group = merged.groups.single()
        // 规范名保留先出现方（远端）的原始写法；折叠同键的本机组并入而非追加
        assertEquals("D.Mon", group.canonical)
        // 变体并集折叠去重（Dmon/DMON 同键取先）且不含规范名自身
        assertEquals(listOf("Dmon", "戴萌"), group.variants)
        // 角色按折叠键并集：杰玛别名并集，艾露猫为本机独有角色
        val characters = group.characters.orEmpty()
        assertEquals(2, characters.size)
        assertEquals("杰玛", characters[0].canonical)
        assertEquals(listOf("Gemma", "杰玛酱"), characters[0].aliases)
        assertEquals("艾露猫", characters[1].canonical)
    }

    @Test
    fun `停用词折叠去重保序`() {
        val remote = CustomSourceGroups(groups = emptyList(), stopWords = listOf("触手", "白丝"))
        val local = CustomSourceGroups(groups = emptyList(), stopWords = listOf(" 白丝 ", "婚纱"))
        val merged = VocabularyMerger.merge(remote, local)
        assertEquals(listOf("触手", "白丝", "婚纱"), merged.stopWords)
    }

    @Test
    fun `GET 回读的规范名自并入变体被合并滤除`() {
        // 服务端存储形态=生效形态（canonical 自并入自身变体），GET 回读数据里规范名
        // 可能重复出现在变体中——合并滤除，PUT 后服务端重新补上，最终存储形态一致
        val remote = CustomSourceGroups(
            groups = listOf(
                CustomSourceGroup(canonical = "怪物猎人", variants = listOf("怪物猎人", "MHW")),
            ),
        )
        val local = CustomSourceGroups(groups = emptyList())
        val merged = VocabularyMerger.merge(remote, local)
        assertEquals(listOf("MHW"), merged.groups.single().variants)
    }

    @Test
    fun `全空输入并集为空且停用词回 null`() {
        val empty = CustomSourceGroups(groups = emptyList())
        val merged = VocabularyMerger.merge(empty, empty)
        assertEquals(emptyList<CustomSourceGroup>(), merged.groups)
        assertNull(merged.stopWords)
    }

    @Test
    fun `无词条组与无别名角色归一为 null`() {
        val merged = VocabularyMerger.merge(
            CustomSourceGroups(
                groups = listOf(
                    CustomSourceGroup(
                        canonical = "空组",
                        characters = listOf(CustomSourceCharacter(canonical = "无名角色")),
                    ),
                ),
            ),
            CustomSourceGroups(groups = emptyList()),
        )
        val group = merged.groups.single()
        assertNull(group.variants)
        val character = group.characters.orEmpty().single()
        assertNull(character.aliases)
    }
}
