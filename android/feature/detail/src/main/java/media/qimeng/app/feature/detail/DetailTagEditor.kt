package media.qimeng.app.feature.detail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.DetailRepository

/** 标签动作共用详情单一状态；VM 提供生命周期 scope 和详情重拉入口。 */
internal class DetailTagEditor(
    private val detailRepository: DetailRepository,
    private val _uiState: MutableStateFlow<DetailUiState>,
    private val assetId: String?,
    private val scope: CoroutineScope,
    private val reloadAssetOnly: () -> Unit,
) {
    /** 打开弹窗：先拉全量池（失败→报错且不开弹窗）；勾选态以详情当前标签复位（Web 同款打开即复位） */
    fun openTagSheet() {
        val asset = _uiState.value.asset ?: return
        scope.launch {
            runCatching { detailRepository.allTags() }
                .onSuccess { pool ->
                    _uiState.value = _uiState.value.copy(
                        tagSheetOpen = true,
                        tagPool = pool,
                        selectedTagIds = asset.tags.map { it.id },
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(errorMessage = ERROR_LOAD_TAGS)
                }
        }
    }

    fun dismissTagSheet() {
        _uiState.value = _uiState.value.copy(tagSheetOpen = false)
    }

    /** 勾选即时本地增删（增删后立即体现——LEGACY §A:15；保存前不落库） */
    fun toggleTagSelection(tagId: String) {
        val current = _uiState.value
        val next = if (tagId in current.selectedTagIds) {
            current.selectedTagIds - tagId
        } else {
            current.selectedTagIds + tagId
        }
        _uiState.value = current.copy(selectedTagIds = next)
    }

    /**
     * 「当前标签」chip 关闭图标 = **立即** DELETE 单条解绑（N4 I7b；N3 #32 逐条删端点），
     * 不再只是草稿勾选：乐观移除 UI → 服务端成功后重拉详情（服务端按关联时间倒序回「最终序」）；
     * 失败回滚乐观态 + 错误提示。草稿新增流（其他标签点选 + 新建 + 保存=整体替换）保留不动。
     */
    fun unbindTag(tagId: String) {
        val current = _uiState.value
        val asset = current.asset ?: return
        val id = assetId ?: return
        if (tagId in current.unbindingTagIds) return // 同标签在途防重
        val chip = current.tagPool.firstOrNull { it.id == tagId } ?: return
        val removedTagIndex = asset.tags.indexOfFirst { it.id == tagId }
        if (removedTagIndex < 0) return // 不在当前标签列表：无可解绑（防御）
        val removedTag = asset.tags[removedTagIndex]
        // 乐观态快照（StateFlow 值可被并发替换，不能依赖链式引用）
        val snapshotSelected = current.selectedTagIds
        _uiState.value = current.copy(
            unbindingTagIds = current.unbindingTagIds + tagId,
            asset = asset.copy(tags = asset.tags.filterNot { it.id == tagId }),
            selectedTagIds = current.selectedTagIds - tagId,
        )
        scope.launch {
            runCatching { detailRepository.unbindTag(id, chip.name) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        unbindingTagIds = _uiState.value.unbindingTagIds - tagId,
                        errorMessage = null,
                    )
                    // 重拉详情对齐服务端最终序（保存同款收尾；失败不影响已解绑事实）
                    reloadAssetOnly()
                }
                .onFailure { error ->
                    // R13（审计 2026-09-20）：失败只回滚「本 tagId 的乐观改动」，
                    // 不再整覆盖乐观态快照——乐观态与失败回调之间可能有更新的
                    // 状态落入 _uiState（窗口邻位重拉完成/用户其他操作），整覆盖
                    // 会把它们一并回退到旧时刻。
                    val rollback = _uiState.value
                    val restoredAsset = rollback.asset?.let { cur ->
                        if (cur.tags.any { it.id == tagId }) {
                            cur // 已被后续重拉恢复：不重复插回
                        } else {
                            val idx = removedTagIndex.coerceAtMost(cur.tags.size)
                            cur.copy(tags = cur.tags.toMutableList().apply { add(idx, removedTag) })
                        }
                    }
                    _uiState.value = rollback.copy(
                        unbindingTagIds = rollback.unbindingTagIds - tagId,
                        asset = restoredAsset,
                        // distinct 防重复：窗口期用户可能在弹窗重新勾回同一标签
                        // （List + element 追加不去重，重复 id 会随 saveTags 整体
                        // 替换 PUT 上行）——createAndSelectTag 同款口径（2026-09-21 维护批）。
                        selectedTagIds = (
                            if (tagId in snapshotSelected) rollback.selectedTagIds + tagId else rollback.selectedTagIds
                            ).distinct(),
                        errorMessage = "$ERROR_UNBIND_TAG${error.message ?: ""}",
                    )
                }
        }
    }

    /**
     * 新建并勾选：trim 非空才发；重名失败透传服务端错误文案；成功入池且选中。
     * [onCreated] 仅创建成功后回调（弹窗据此清空输入框；失败保留输入供重试，对齐 Web 成功回调清空）。
     */
    fun createAndSelectTag(rawName: String, onCreated: () -> Unit = {}) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        scope.launch {
            runCatching { detailRepository.createTag(name) }
                .onSuccess { chip ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        tagPool = if (current.tagPool.any { it.id == chip.id }) {
                            current.tagPool
                        } else {
                            current.tagPool + chip
                        },
                        selectedTagIds = (current.selectedTagIds + chip.id).distinct(),
                        errorMessage = null,
                    )
                    onCreated()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "$ERROR_CREATE_TAG${error.message ?: ""}",
                    )
                }
        }
    }

    /**
     * 保存 = PUT tags 整体替换（读-改-写一次提交；PUT 返回 void 故无回填），
     * 成功后重新 GET assetDetail：服务端按关联时间倒序返回「最近添加置顶」最终序（LEGACY §A）。
     * 注：整体替换会刷新全部关联时间=协议已知限制（唯一标签端点，DOMAIN_RULES §7）。
     */
    fun saveTags() {
        val id = assetId ?: return
        val current = _uiState.value
        if (current.savingTags) return
        _uiState.value = current.copy(savingTags = true)
        scope.launch {
            runCatching { detailRepository.replaceAssetTags(id, current.selectedTagIds) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        savingTags = false,
                        tagSheetOpen = false,
                        errorMessage = null,
                    )
                    reloadAssetOnly()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        savingTags = false,
                        errorMessage = "$ERROR_SAVE_TAGS${error.message ?: ""}",
                    )
                }
        }
    }

    private companion object {
        private const val ERROR_LOAD_TAGS = "标签列表加载失败"
        private const val ERROR_CREATE_TAG = "新建标签失败："
        private const val ERROR_SAVE_TAGS = "标签保存失败："
        private const val ERROR_UNBIND_TAG = "解除标签失败："
    }
}
