package media.qimeng.app.core.model

/**
 * 推荐偏好 9 维权重值（`GET/PUT /recommendations/prefs` 的载荷形态）。
 * 字段名与 openapi RecommendPrefs schema 一一对应（协议侧改动须同步此处）。
 * 数值口径 = DOMAIN_RULES §1 十维公式的 9 个可调权重 + maxRandom。
 */
data class RecommendPrefsValues(
    val tagRelevance: Double,
    val tagCollection: Double,
    val engagement: Double,
    val recency: Double,
    val likeScore: Double,
    val discovery: Double,
    val freshness: Double,
    val browseDepth: Double,
    val maxRandom: Double,
)

/**
 * 推荐偏好四预设（C4 拍板；BottomSheet 四行）。
 * 每档 9 维数值**逐字**取自 DOMAIN_RULES §1.3 预设表——该表标注「逐字遵守」级别，
 * 改动必须先改 DOMAIN_RULES 并同步单测（RecommendPresetsTest 逐维锁定）。
 *
 * @param label BottomSheet 行文案（均衡/高记忆流行/深度探索/新鲜优先）
 */
enum class RecommendPreset(
    val label: String,
    val tagRelevance: Double,
    val tagCollection: Double,
    val engagement: Double,
    val recency: Double,
    val likeScore: Double,
    val discovery: Double,
    val freshness: Double,
    val browseDepth: Double,
    val maxRandom: Double,
) {
    /** 均衡推荐（默认）：设计权重原样 */
    BALANCED(
        label = "均衡推荐",
        tagRelevance = 0.22, tagCollection = 0.15, engagement = 0.10, recency = 0.15,
        likeScore = 0.05, discovery = 0.20, freshness = 0.05, browseDepth = 0.03, maxRandom = 0.30,
    ),

    /** 高记忆流行：互动/时效权重抬升，发现/随机压低 */
    MEMORY_POPULAR(
        label = "高记忆流行",
        tagRelevance = 0.15, tagCollection = 0.10, engagement = 0.20, recency = 0.25,
        likeScore = 0.10, discovery = 0.05, freshness = 0.05, browseDepth = 0.05, maxRandom = 0.10,
    ),

    /** 深度探索：标签两维与发现拉满方向，随机扰动最大 */
    DEEP_EXPLORATION(
        label = "深度探索",
        tagRelevance = 0.30, tagCollection = 0.20, engagement = 0.05, recency = 0.05,
        likeScore = 0.02, discovery = 0.30, freshness = 0.02, browseDepth = 0.05, maxRandom = 0.40,
    ),

    /** 新鲜优先：新鲜度/发现抬升，记忆类权重回落 */
    FRESH_FIRST(
        label = "新鲜优先",
        tagRelevance = 0.10, tagCollection = 0.05, engagement = 0.05, recency = 0.10,
        likeScore = 0.02, discovery = 0.25, freshness = 0.20, browseDepth = 0.03, maxRandom = 0.35,
    ),
}

/** 预设 → 9 维载荷（PUT /recommendations/prefs 请求体来源；唯一出口，禁止在 UI 手抄数值） */
fun RecommendPreset.toPrefsValues(): RecommendPrefsValues = RecommendPrefsValues(
    tagRelevance = tagRelevance,
    tagCollection = tagCollection,
    engagement = engagement,
    recency = recency,
    likeScore = likeScore,
    discovery = discovery,
    freshness = freshness,
    browseDepth = browseDepth,
    maxRandom = maxRandom,
)

/**
 * 当前 9 维值 → 命中的预设（BottomSheet「当前项高亮」判定，C4）。
 * 逐维精确比对：命中返回该预设；服务端值不在四预设内（用户在 Web 端自定义过等）返回 null
 * ——null = 四行都不高亮，不猜近似档。
 */
fun RecommendPrefsValues.matchPreset(): RecommendPreset? =
    RecommendPreset.entries.firstOrNull { preset ->
        val values = preset.toPrefsValues()
        values == this
    }
