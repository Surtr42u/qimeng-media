package media.qimeng.app.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.LocalMediaRepository
import media.qimeng.app.core.model.LocalMediaBucket
import media.qimeng.app.core.model.LocalMediaItem

/**
 * 内置相册选择器 UI 状态（2026-09-25 拍板：上传选取弃 SAF 改 App 内置相册式选择器）。
 * 数据面 = [LocalMediaRepository]（MediaStore Images+Video）；选择跨相册保留，
 * 已选集合用 LinkedHashMap 语义（uri → 条目）保持用户选择序。
 */
data class MediaPickerUiState(
    /** 首屏加载（相册 + 条目一次装载） */
    val loading: Boolean = false,
    /** 相册过滤 chips（「全部」由 UI 恒渲染，不入本列表；API < 29 恒空 = 仅「全部」） */
    val buckets: List<LocalMediaBucket> = emptyList(),
    /** 当前过滤相册名；null = 全部 */
    val selectedBucket: String? = null,
    /** 当前过滤下的媒体条目（dateAdded 倒序，服务上限截断） */
    val items: List<LocalMediaItem> = emptyList(),
    /** 切相册/刷新时的条目重载中（首屏 loading 之外的后台态） */
    val loadingItems: Boolean = false,
    /** 已选项（uri → 条目；跨相册保留、保持选择序） */
    val selected: Map<String, LocalMediaItem> = emptyMap(),
    /** 查询失败文案（MediaStore 不可用/权限被剥） */
    val errorMessage: String? = null,
)

/**
 * 内置相册选择器 ViewModel：相册 chips + 网格条目 + 多选编排。
 * 业务规则（过滤/去重/选择保持）全在 VM，UI 零逻辑（ADR-0008 铁律 7）；
 * MediaStore 访问收口 core:data（UI 层零 android.provider 依赖）。
 */
@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    private val localMediaRepository: LocalMediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaPickerUiState())
    val uiState: StateFlow<MediaPickerUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 首屏装载：相册 chips + 「全部」条目。失败给错误横幅（选择器内重试入口） */
    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val buckets = localMediaRepository.buckets(BUCKET_CHIP_LIMIT)
                _uiState.update { it.copy(loading = false, buckets = buckets) }
                loadItems()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = LOAD_FAILED_MESSAGE) }
            }
        }
    }

    /** 切换相册过滤（null = 全部）；已选集合跨相册保留 */
    fun selectBucket(name: String?) {
        if (_uiState.value.selectedBucket == name) return
        _uiState.update { it.copy(selectedBucket = name) }
        viewModelScope.launch { loadItems() }
    }

    /** 条目装载（相册过滤 + 上限截断）；失败仅提示，不清空已装载内容 */
    private suspend fun loadItems() {
        val bucket = _uiState.value.selectedBucket
        _uiState.update { it.copy(loadingItems = true) }
        try {
            val items = localMediaRepository.items(bucket, ITEMS_LOAD_LIMIT)
            // 仅当过滤条件未再变化时回填（防快速切相册竞态串列表）
            _uiState.update { current ->
                if (current.selectedBucket == bucket) {
                    current.copy(items = items, loadingItems = false)
                } else {
                    current
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(loadingItems = false, errorMessage = LOAD_FAILED_MESSAGE) }
        }
    }

    /** 网格项点选 toggle（已选即取消；未选即加入，保持选择序） */
    fun toggle(item: LocalMediaItem) {
        _uiState.update { current ->
            val selected = LinkedHashMap(current.selected)
            if (item.uri in selected) {
                selected.remove(item.uri)
            } else {
                selected[item.uri] = item
            }
            current.copy(selected = selected)
        }
    }

    companion object {
        /**
         * 相册 chips 上限（数据量控制拍板口径：chips 取前 N 个 bucket——最近有内容
         * 的相册优先，超出的相册内容仍可在「全部」网格中看到，不丢数据只收窄快捷过滤）。
         */
        const val BUCKET_CHIP_LIMIT = 8

        /**
         * 网格单次装载上限（dateAdded 倒序取最近）：选择器选近物为主场景，
         * 不做无限滚动分页（本批范围外）；超出的老条目走相册过滤收敛。
         */
        const val ITEMS_LOAD_LIMIT = 500

        private const val LOAD_FAILED_MESSAGE = "读取本机媒体失败：请检查媒体权限后重试"
    }
}
