package media.qimeng.app.core.model

/**
 * 上传批次默认配置（持久化：目标库/作者/来源三项，上传页直传管道入队时自动继承）。
 * 持久化编码（JSON）与存储在 :core:data；本模块零 Android 依赖。
 * 2026-09-29 直传化收窄：暂存条目模型随暂存区退役删除，本文件只余批次配置。
 */
data class StagingBatchConfig(
    /** 批次目标库 id（null = 未选；直传门禁要求已选） */
    val libraryId: String? = null,
    /** 批次作者 id（null = 未选；来源以其为前提） */
    val authorId: String? = null,
    /** 批次作者展示名（纯 UI 展示） */
    val authorName: String? = null,
    /** 批次来源词（批次作者未选时恒空） */
    val sources: List<String> = emptyList(),
)
