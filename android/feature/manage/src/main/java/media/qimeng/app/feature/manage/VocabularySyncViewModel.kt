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
import media.qimeng.app.core.data.repository.VocabularySyncPreview
import media.qimeng.app.core.data.repository.VocabularySyncRepository

/** 词表同步页 UI 状态（BackupUiState 三件套同款：防重复提交 + 错误横幅 + 结果提示） */
data class VocabularySyncUiState(
    /** 阶段一预览进行中（重试按钮禁用防重） */
    val previewing: Boolean = false,
    /** 阶段二下发进行中（确认按钮禁用防重，文案「同步中…」） */
    val applying: Boolean = false,
    /** 阶段一产物（null=尚未成功预览；确认按钮以非 null 为门） */
    val preview: VocabularySyncPreview? = null,
    /** 错误横幅（点击关闭；含本机模式门禁提示） */
    val errorMessage: String? = null,
    /** 同步结果提示（点击关闭） */
    val noticeMessage: String? = null,
)

/**
 * 词表同步 ViewModel（ADR-0034，2026-10-04）：两段式——进页自动 [preview]（阶段一：
 * 远端/本机词表对照），用户确认后 [apply]（阶段二：单向下发覆盖本机）。业务全在
 * core:data VocabularySyncRepository（铁律 7）：本 VM 只做防重复提交、错误分类→文案
 * 映射与结果提示，不持有任何地址/鉴权/协议知识。
 */
@HiltViewModel
class VocabularySyncViewModel @Inject constructor(
    private val repository: VocabularySyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularySyncUiState())
    val uiState: StateFlow<VocabularySyncUiState> = _uiState.asStateFlow()

    /**
     * 阶段一：预览两端词表对照。进页自动触发（LaunchedEffect），亦可失败后手动重试；
     * 忙态下 no-op（防重复提交）。本机模式等前置不满足时置错误横幅（「进入即提示」口径）。
     */
    fun preview() {
        if (_uiState.value.previewing || _uiState.value.applying) return
        _uiState.update { it.copy(previewing = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            // runCatching 兜底（BackupViewModel 同款宽捕口径）：领域分类失败走 errorText
            // 映射，未预期异常落 ERROR_GENERIC，绝不向 viewModelScope 抛未捕获异常
            val result = runCatching { repository.preview() }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { preview ->
                    _uiState.update { it.copy(previewing = false, preview = preview) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(previewing = false, preview = null, errorMessage = errorText(e))
                    }
                },
            )
        }
    }

    /**
     * 阶段二：确认下发（幂等重走链路，以调用时刻远端词表为准）。仅预览就绪后可触发；
     * 成功清空预览（确认按钮随之消失），结果提示带下发组数/停用词数。
     */
    fun apply() {
        if (_uiState.value.applying || _uiState.value.preview == null) return
        _uiState.update { it.copy(applying = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching { repository.apply() }.getOrElse { Result.failure(it) }
            outcome.fold(
                onSuccess = { applied ->
                    _uiState.update {
                        it.copy(
                            applying = false,
                            preview = null,
                            noticeMessage = NOTICE_APPLIED.format(
                                applied.appliedGroupCount,
                                applied.appliedStopWordCount,
                            ),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(applying = false, errorMessage = errorText(e)) }
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
            VocabularySyncError.LocalServerUnavailable -> ERROR_LOCAL_SERVER
            VocabularySyncError.LocalApplyFailed -> ERROR_APPLY
        }
        else -> ERROR_GENERIC
    }

    private companion object {
        /** 门禁文案（任务书口径：单机模式无权威源，请先登录 NAS/电脑端） */
        const val ERROR_LOCAL_MODE = "单机模式没有权威词表源，请先登录 NAS/电脑端"

        const val ERROR_REMOTE_FETCH = "拉取远端词表失败，请检查网络后重试"
        const val ERROR_LOCAL_SERVER = "本机词表服务不可用，请稍后重试"
        const val ERROR_APPLY = "写入本机词表失败，请重试"
        const val ERROR_GENERIC = "同步失败，请重试"

        /** 结果提示（%d=下发组数、停用词数；「后台重算」与协议 PUT 语义同源） */
        const val NOTICE_APPLIED = "同步完成：已下发 %d 组出处、%d 个停用词，本机将后台重算"
    }
}
