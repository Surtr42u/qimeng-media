package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.data.repository.VocabularySyncRepository

/**
 * 词表合并同步 UI 状态（BackupUiState 三件套同款：防重复提交 + 错误横幅 + 结果提示）。
 * ADR-0035 定稿语义：合并是只增不删的无损操作——无方向选择、无两段式确认，一键同步。
 */
data class VocabularySyncUiState(
    /** 合并同步进行中（按钮禁用防重） */
    val syncing: Boolean = false,
    /** 错误横幅（点击关闭；含本机模式门禁提示） */
    val errorMessage: String? = null,
    /** 结果提示（点击关闭） */
    val noticeMessage: String? = null,
)

/**
 * 词表合并同步 ViewModel（ADR-0035 定稿，2026-10-04）：一键触发 [sync]（两端拉取 →
 * core:data VocabularyMerger 并集合并 → 显式 PUT 回两端）。业务全在 core:data
 * VocabularySyncRepository（铁律 7）：本 VM 只做防重复提交、错误分类→文案映射与结果
 * 提示，不持有任何地址/鉴权/协议知识。
 */
@HiltViewModel
class VocabularySyncViewModel @Inject constructor(
    private val repository: VocabularySyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularySyncUiState())
    val uiState: StateFlow<VocabularySyncUiState> = _uiState.asStateFlow()

    /**
     * 一键合并同步。本机模式等前置不满足时置错误横幅；成功提示两端收敛规模与各端
     * 补入增量（服务端 PUT 后自动后台重算）。
     */
    fun sync() {
        if (_uiState.value.syncing) return
        _uiState.update { it.copy(syncing = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            // runCatching 兜底（BackupViewModel 同款宽捕口径）：领域分类失败走 errorText
            // 映射，未预期异常落 ERROR_GENERIC，绝不向 viewModelScope 抛未捕获异常
            val result = runCatching { repository.sync() }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { merged ->
                    _uiState.update {
                        it.copy(
                            syncing = false,
                            noticeMessage = NOTICE_MERGED.format(
                                merged.mergedGroupCount,
                                merged.mergedStopWordCount,
                                merged.newFromRemoteCount,
                                merged.newFromLocalCount,
                            ),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(syncing = false, errorMessage = errorText(e)) }
                },
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    /** 领域分类 → 中文文案（分类单源 core:data VocabularySyncError，映射只此一处） */
    private fun errorText(e: Throwable?): String = when {
        e is VocabularySyncException -> when (e.error) {
            VocabularySyncError.NoAuthoritativeSource -> ERROR_LOCAL_MODE
            VocabularySyncError.RemoteFetchFailed -> ERROR_REMOTE_FETCH
            VocabularySyncError.RemoteApplyFailed -> ERROR_REMOTE_APPLY
            VocabularySyncError.LocalServerUnavailable -> ERROR_LOCAL_SERVER
            VocabularySyncError.LocalApplyFailed -> ERROR_LOCAL_APPLY
        }
        else -> ERROR_GENERIC
    }

    private companion object {
        /** 门禁文案（无远端会话即无合并对端） */
        const val ERROR_LOCAL_MODE = "单机模式无法同步词表，请先登录 NAS/电脑端"

        const val ERROR_REMOTE_FETCH = "拉取远端词表失败，请检查网络后重试"
        const val ERROR_REMOTE_APPLY = "写入 NAS/电脑端词表失败，请检查网络后重试"
        const val ERROR_LOCAL_SERVER = "本机词表服务不可用，请稍后重试"
        const val ERROR_LOCAL_APPLY = "写入本机词表失败，请重试"
        const val ERROR_GENERIC = "同步失败，请重试"

        /** 结果提示（%1$d=合并后组数、%2$d=停用词数、%3$d=远端补入组数、%4$d=本机补入组数；
         *  「后台重算」与协议 PUT 语义同源） */
        const val NOTICE_MERGED =
            "合并完成：两端共 %1\$d 组出处、%2\$d 个停用词（远端补入 %3\$d 组 · 本机补入 %4\$d 组），" +
                "两端将后台重算"
    }
}
