package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.EditableVocabulary
import media.qimeng.app.core.model.VocabularyCharacter
import media.qimeng.app.core.model.VocabularyGroup
import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 词表编辑域模型 ↔ SDK 双向映射锁定（2026-10-24 SDK 隔离批，任务书 Q2）：映射只允许
 * 存在于 core:data（VocabularyEditMappers），语义与隔离前「VM 直接编辑 SDK 对象」严格
 * 等价——此前 ViewModel 测试里的「空子列表→null」断言自本批起收口到此处。
 *
 * 关键语义（不得漂移）：
 * - GET：协议可省子列表 null → 端内 emptyList；
 * - PUT：端内空子列表 → 协议可省（null=序列化省略）——「空=清空」，不是「不提交=保留」；
 * - groups/stopWords 恒显式数组（整体替换语义）：stopWords 端内空 → 显式空数组
 *   （清空回内置基线），绝不能因可省字段默认值变成「不提交」。
 */
class VocabularyEditMappersTest {

    @Test
    fun `GET 可省子列表归一为端内空表`() {
        val sdk = CustomSourceGroups(
            groups = listOf(
                CustomSourceGroup(canonical = "怪物猎人", variants = null, characters = null),
            ),
            stopWords = null,
        )
        val domain = sdk.toEditableVocabulary()
        val group = domain.groups.single()
        assertEquals("怪物猎人", group.canonical)
        assertEquals(emptyList<String>(), group.variants)
        assertEquals(emptyList<VocabularyCharacter>(), group.characters)
        assertEquals(emptyList<String>(), domain.stopWords)
    }

    @Test
    fun `GET 空协议数组同样归一为端内空表`() {
        // 服务端显式回空数组与可省（null）在端内不可区分：都是 emptyList
        val sdk = CustomSourceGroups(
            groups = listOf(
                CustomSourceGroup(
                    canonical = "G",
                    variants = emptyList(),
                    characters = emptyList(),
                ),
            ),
            stopWords = emptyList(),
        )
        val domain = sdk.toEditableVocabulary()
        assertEquals(emptyList<String>(), domain.groups.single().variants)
        assertEquals(emptyList<VocabularyCharacter>(), domain.groups.single().characters)
        assertEquals(emptyList<String>(), domain.stopWords)
    }

    @Test
    fun `GET 非空子列表原样透传`() {
        val domain = sampleSdk().toEditableVocabulary()
        val group = domain.groups.single()
        assertEquals(listOf("MHW"), group.variants)
        val character = group.characters.single()
        assertEquals("杰玛", character.canonical)
        assertEquals(listOf("Gemma"), character.aliases)
        assertEquals(listOf("触手"), domain.stopWords)
    }

    @Test
    fun `PUT 空子列表回协议可省`() {
        val sdk = EditableVocabulary(
            groups = listOf(VocabularyGroup(canonical = "G")),
            stopWords = emptyList(),
        ).toSdk()
        assertNull(sdk.groups.single().variants)
        assertNull(sdk.groups.single().characters)
        // stopWords 恒显式数组：空数组=清空回内置基线（构造默认值 null 意味着「不提交=保留」，
        // 恰是本断言要锁死的反模式）
        assertNotNull(sdk.stopWords)
        assertEquals(emptyList<String>(), sdk.stopWords)
    }

    @Test
    fun `PUT 空角色别名回协议可省而角色本身保留`() {
        val sdk = EditableVocabulary(
            groups = listOf(
                VocabularyGroup(
                    canonical = "G",
                    characters = listOf(VocabularyCharacter(canonical = "无别名角色")),
                ),
            ),
        ).toSdk()
        val character = sdk.groups.single().characters!!.single()
        assertEquals("无别名角色", character.canonical)
        assertNull(character.aliases)
    }

    @Test
    fun `PUT 非空子列表原样透传`() {
        val sdk = sampleDomain().toSdk()
        val group = sdk.groups.single()
        assertEquals("怪物猎人", group.canonical)
        assertEquals(listOf("MHW"), group.variants)
        assertEquals(listOf("Gemma"), group.characters!!.single().aliases)
        assertEquals(listOf("触手"), sdk.stopWords)
    }

    @Test
    fun `非空数据往返保持不变`() {
        val roundtrip = sampleSdk().toEditableVocabulary().toSdk()
        assertEquals(sampleSdk(), roundtrip)
    }

    private fun sampleSdk() = CustomSourceGroups(
        groups = listOf(
            CustomSourceGroup(
                canonical = "怪物猎人",
                variants = listOf("MHW"),
                characters = listOf(
                    CustomSourceCharacter(canonical = "杰玛", aliases = listOf("Gemma")),
                ),
            ),
        ),
        stopWords = listOf("触手"),
    )

    private fun sampleDomain() = EditableVocabulary(
        groups = listOf(
            VocabularyGroup(
                canonical = "怪物猎人",
                variants = listOf("MHW"),
                characters = listOf(VocabularyCharacter(canonical = "杰玛", aliases = listOf("Gemma"))),
            ),
        ),
        stopWords = listOf("触手"),
    )
}
