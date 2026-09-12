package media.qimeng.app.core.model

/**
 * 作者页行数据（映射 SDK Author）。
 */
data class AuthorSummary(
    val id: String,
    val displayName: String,
    val type: AuthorType,
    val fileCount: Int?,
    val followed: Boolean,
    val viewCount: Int?,
)

enum class AuthorType { REGULAR, COS }

/** 作者页排序三项（Web AuthorsPage A_SORTERS 同口径：默认=API 原序、浏览数降序、文件数降序） */
enum class AuthorSortOption(val label: String) {
    DEFAULT("默认"),
    BROWSE("浏览数"),
    WORKS("文件数"),
}

/** 作者行显示名：COS 作者追加「 ·COS」标识（对齐 Web authorDisplayName 单源翻译） */
val AuthorSummary.displayLabel: String
    get() = if (type == AuthorType.COS) "$displayName ·COS" else displayName

/** 体系→类型映射（全部=null 不筛；[applyAuthorRows] 的体系段单源） */
private fun Zone.toAuthorType(): AuthorType? = when (this) {
    Zone.ALL -> null
    Zone.REGULAR -> AuthorType.REGULAR
    Zone.COS -> AuthorType.COS
}

/** 作者页排序（纯函数；先 filter 后 sort，slice 语义用 toList 保证不污染原序基准） */
fun List<AuthorSummary>.applyAuthorRows(
    zone: Zone,
    keyword: String,
    sort: AuthorSortOption,
): List<AuthorSummary> {
    val type = zone.toAuthorType()
    return filter { author ->
        (type == null || author.type == type) &&
            (keyword.isBlank() || author.displayName.contains(keyword.trim()))
    }
        .toList()
        .sortedWith(
            when (sort) {
                // API 原序（全等比较器 + 稳定排序 = 保持原序）
                AuthorSortOption.DEFAULT -> compareBy { 0 }
                AuthorSortOption.BROWSE -> compareByDescending { it.viewCount ?: 0 }
                AuthorSortOption.WORKS -> compareByDescending { it.fileCount ?: 0 }
            },
        )
}

/** 搜索建议领域模型（映射 SDK SearchSuggestion） */
data class NameSuggestion(
    val name: String,
    val kind: SuggestionKind,
)

/** 建议五维（协议 SearchSuggestionType；右侧类型徽标文案见 [badgeLabel]） */
enum class SuggestionKind {
    SOURCE,
    CHARACTER,
    COS_AUTHOR,
    COS_WORK,
    AUTHOR,
}

/** 建议右侧类型徽标（旧版 §搜索页：出处/角色/COS作者/COS作品/作者） */
val SuggestionKind.badgeLabel: String
    get() = when (this) {
        SuggestionKind.SOURCE -> "出处"
        SuggestionKind.CHARACTER -> "角色"
        SuggestionKind.COS_AUTHOR -> "COS作者"
        SuggestionKind.COS_WORK -> "COS作品"
        SuggestionKind.AUTHOR -> "作者"
    }

/**
 * 补全排序：从短到长（旧版 §搜索页「一行一个，从短到长排序」；
 * 服务端不承诺顺序，客户端重排并单测锁定。稳定排序保持同长的原相对序）。
 */
fun List<NameSuggestion>.sortedShortestFirst(): List<NameSuggestion> =
    sortedBy { it.name.length }
