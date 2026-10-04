package media.qimeng.app.core.data.repository

import media.qimeng.sdk.models.CustomSourceGroups

/**
 * 词表维护端口（ADR-0035，2026-10-04 用户拍板）：本机内嵌库检索词表（自定义出处组 +
 * 停用词）的直接编辑面——ADR-0034 记档推迟的「App 端词表编辑 UI」转正落地。
 *
 * 无门禁（本机/远程登录态都可编辑，编辑对象恒为本机内嵌库，不涉远端）；远程登录态下
 * 内嵌服务端按需拉起、操作收尾停回（LocalVocabularyChannel 收口，同 ADR-0034 通道）。
 *
 * 编辑交互为「内存编辑 + 整体保存」：[load] 拉一次全量词表进 UI 内存编辑，[save] 把
 * 编辑结果整体 PUT——协议整体替换语义天然对齐（用户看到什么就保存什么，无增量合并
 * 语义），保存后服务端规范化（trim/去空/去重/canonical 自并入变体别名）并自动后台
 * 全库重算。协议零改动——只消费既有 GET/PUT /sources/custom-groups（ADR-0033）。
 *
 * 错误分类复用 [VocabularySyncError]（同一底层通道 LocalVocabularyChannel、同一失败
 * 模式，不另立平行枚举）：本端口只产生 LocalServerUnavailable（通道/本地 GET 失败）
 * 与 LocalApplyFailed（保存 PUT 失败）两支，其余三支不会出现。
 */
interface VocabularyEditRepository {

    /** 加载本机当前词表（服务端规范化后的生效形态） */
    suspend fun load(): Result<CustomSourceGroups>

    /** 整体保存编辑结果（groups+stopWords 恒显式数组，整体替换语义） */
    suspend fun save(payload: CustomSourceGroups): Result<Unit>
}
