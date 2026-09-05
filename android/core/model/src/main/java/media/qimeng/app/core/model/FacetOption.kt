package media.qimeng.app.core.model

/**
 * 相册胶囊栏一条候选（映射 SDK FacetBucket）。
 *
 * @param key 回传筛选值（authorId、角色名、COS 作品名、MediaType 枚举值等）
 * @param name 显示名
 * @param fileCount 排自身口径的文件计数（服务端按当前其他维选择统计）
 * @param kind 该候选回传哪个参数——同一行混合两种候选时（作者行=出处∪COS 作者、
 *   角色行=角色∪COS 作品）按此分派，激活判定用 key+kind 二元组（同名不同 kind 不串行）
 */
data class FacetOption(
    val key: String,
    val name: String,
    val fileCount: Int,
    val kind: FacetParamKind,
)

/** 候选回传的参数名（对应 GET /assets 的 source/authorId/character/work） */
enum class FacetParamKind {
    SOURCE,
    AUTHOR,
    CHARACTER,
    WORK,
}

/** 「其他」桶显示名（服务端兜底桶；协议只承诺 fileCount 降序、不承诺置底——前端恒置底并单测锁定） */
const val OTHER_BUCKET_NAME = "其他"

/**
 * 候选行排序：服务端已按 fileCount 降序，这里只兜底一件事——「其他」桶恒排末尾
 * （含 COS 作品/作者兜底桶；旧版 GUIDE_UI §全部页「'其他'药丸始终排在列表最下面」）。
 * 纯函数，Android 新增逻辑（openapi 不承诺），单测锁定。
 */
fun List<FacetOption>.withOtherBucketLast(): List<FacetOption> {
    val (others, rest) = partition { it.name == OTHER_BUCKET_NAME }
    return rest + others
}
