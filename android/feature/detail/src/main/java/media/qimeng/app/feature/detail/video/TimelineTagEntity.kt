package media.qimeng.app.feature.detail.video

/**
 * 时间轴标签（3c）——旧项目 QimengMedia Room 实体 `TimelineTagEntity` 的纯数据类副本
 * （ADR-0014 桥接例外③的配套剥离：**字段不变、Room 注解剥离**）。
 *
 * 剥离原因：本模块依赖面不含 Room（feature 只依赖 core，ADR-0014）；BiliPlayerView 桥接件
 * 公开面 `updateTimelineTags`/`onTagLongPress` 以此类型为参数，搬运时保持公开面不变。
 * 数据源与持久化随 3d 接线（届时由详情数据层映射成本类型）。
 *
 * @param timelineTagId 旧实体自增主键（保留字段名，3d 若映射服务端 id 则另行收敛）
 * @param recordKey 旧实体所属记录键（保留字段，剥离 Room 索引语义）
 * @param fileName 旧实体所属文件名（保留字段）
 * @param timeMillis 标签时间点（毫秒；点击跳转 seekTo 目标）
 * @param name 标签名（「❤️」「⭐」前缀决定芯片色调，见 BiliPlayerView.createTagChip）
 * @param createdAtMillis 创建时间（保留字段）
 */
data class TimelineTagEntity(
    val timelineTagId: Long = 0,
    val recordKey: String,
    val fileName: String,
    val timeMillis: Long,
    val name: String,
    val createdAtMillis: Long,
)
