package media.qimeng.app.core.model

/**
 * 万能筛选面板 UI 状态（M4-2A-B3；原 feature/all AlbumViewModel.kt 内声明，任务Y Y4b 抽共享——
 * 相册页与首页 COS 流共用同一面板组件 QimengFilterSheet 与草稿模型 [AlbumPanelDraft]，
 * 状态形态同构，单源防漂移）。
 * visible=面板开关；draft=编辑中草稿（打开时拷贝已应用值，应用/重置/关闭语义见各页
 * ViewModel 面板方法组：AlbumViewModel / HomeViewModel 同范式互指）；tags=标签候选流
 * （GET /tags 全量）；message=面板内操作反馈（标签重名/操作失败专用语义，文案由 UI 层用
 * strings.xml 落地——VM 只发语义不触 Android 资源）。
 */
data class FilterPanelUiState(
    val visible: Boolean = false,
    val draft: AlbumPanelDraft = AlbumPanelDraft(),
    val tags: List<TagSummary> = emptyList(),
    val message: PanelFeedback? = null,
)

/**
 * 面板内操作反馈的领域语义（修复轮 P2-1）：标签新建/删除的「重名」与「其他失败」分流，
 * 不再与列表加载失败共用「加载失败」文案通道。文案落地在 :core:ui strings.xml（重名文案
 * 逐字照旧版 v1.16 Toast），VM 只发语义。
 */
sealed interface PanelFeedback {
    /** 标签重名：候选预查重命中，或服务端 409（core/data TagNameConflictException 兜底通道） */
    data class TagExists(val name: String) : PanelFeedback

    /** 重名之外的操作失败（新建/删除请求失败等），给中性文案 */
    data object OpFailed : PanelFeedback
}
