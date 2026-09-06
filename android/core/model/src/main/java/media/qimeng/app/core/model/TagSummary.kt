package media.qimeng.app.core.model

/**
 * 标签（M4-2A-B3 万能筛选面板「标签」流候选；GET /tags 全量数组 / POST /tags / DELETE /tags/{id}）。
 * 领域侧镜像（协议侧改动须同步）；fileCount 协议有但面板不用，不收。
 */
data class TagSummary(
    val id: String,
    val name: String,
)
