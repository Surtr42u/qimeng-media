package media.qimeng.app.core.model

/**
 * 媒体类型三档（协议 MediaType 枚举，DOMAIN_RULES §3；「音频」不存在——旧原型音频 tab 是 mock 残留）。
 */
enum class MediaKind {
    IMAGE,
    ANIMATED_IMAGE,
    VIDEO,
}

/**
 * 列表资产领域模型（映射 SDK AssetSummary/HistoryItem 的列表所需子集）。
 * 只保留列表族页面渲染需要的字段；详情字段随 M4-3 批次扩充。
 *
 * @param thumbUrl 已是「服务端 base URL + 签名路径」拼好的绝对直链（签名 URL 禁改写，ADR-0002）
 * @param title 卡片标题：cosWork 优先、回退 fileName（协议 AssetSummary.cosWork 注释的客户端口径）
 */
data class MediaAsset(
    val id: String,
    val fileName: String,
    val title: String,
    val mediaType: MediaKind,
    val thumbUrl: String?,
    val source: String?,
    val characters: List<String>,
    val isFavorite: Boolean,
    val authorNames: List<String>,
    /** 文件修改时间 epoch 毫秒（协议 modifiedAt；相册页日期分组/短日期用） */
    val modifiedAtMs: Long?,
    /** 文件修改时间 ISO 原文（dateLabel 兼容展示；grouping 用 [modifiedAtMs]） */
    val durationMs: Long?,
    val viewCount: Int?,
    val playCount: Int?,
    /** 浏览时间 epoch 毫秒（仅历史页条目有值，其余来源为 null） */
    val lastViewedAtMs: Long?,
    /**
     * COS 作品子目录名（协议 AssetSummary.cosWork；仅 COS 库扫描赋值，DOMAIN_RULES §6）。
     * 相册页角色模式分组键（M4-2A-B2）；非空即 COS 作品文件，null=常规资产或无作品子目录的 COS 资产。
     */
    val cosWork: String? = null,
)
