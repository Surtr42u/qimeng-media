package media.qimeng.app.core.data.device

import android.util.Log
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.backup.BackupValidator
import media.qimeng.app.core.data.backup.MaintenanceFolderRepository
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.embedded.LocalServerWarmup
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.util.runSuspendCatching
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException

/**
 * 设备结果包 Worker 的执行体（task-17；协议见 docs/api/device.md / ADR-0055）。
 *
 * 固定顺序（与 docs/AI_MAINTENANCE_P0.md §4.5 一致）：
 * 1. 读本机设置，仅使用专用设备 AI 令牌；未配置不发起任何设备端点请求；
 * 2. 本机回环模式先 ensureStartedIfLocalMode + 等端口就绪；
 * 3. list targetDevice=pending（再按目标/状态二次过滤）→ claim → 详情；
 * 4. 整包校验（目标/协议/指纹/段清单）→ 维护目录门禁（已持久化根 + 所有文件访问）；
 * 5. 下载全部段、复核 bytes/sha256（不通过不应用任何段）；
 * 6. 先 export(automatic=true) 保存本机「当前/上次」两代，失败即中止；
 * 7. 应用 masterTxt → POST /authors/import-txt（同名片段覆盖、服务端统一重建）；
 *    v2Backup → BackupValidator 真校验链 → POST /import/qimeng-backup；
 * 8. 按 manifest expectedReadback 与导入响应逐段比较 → 回传 result；
 * 9. 应用后再 export 一次刷新「当前」；无论成功失败都在 finally 删除下载临时文件。
 *
 * 不使用前台服务；本机模式替身服务仍由既有 EmbeddedServerService 拉起（后台 FGS
 * 限制见文档）。进程内 Mutex 让周期链与手动链串行；进程死亡后的重放依赖服务端
 * 幂等（TXT 同名重建、v2 事件身份幂等），不持久化段级进度。
 */
@Singleton
class DeviceApplyRunner @Inject constructor(
    private val settingsRepository: DeviceApplySettingsRepository,
    private val serverConfig: ServerConfigDataSource,
    private val embeddedServerController: EmbeddedServerController,
    private val localServerWarmup: LocalServerWarmup,
    private val remoteFactory: DeviceApplyRemoteFactory,
    private val maintenance: MaintenanceFolderRepository,
    private val staging: StagingRepository,
    private val authorRepository: AuthorRepository,
    private val backupRepository: BackupRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** 两条 WorkManager 链可能并发调度；进程内串行，避免同时写维护目录/导入。 */
    private val mutex = Mutex()

    suspend fun run(manual: Boolean): DeviceApplyRunResult = mutex.withLock {
        val settings = settingsRepository.settings.first()
        val authorization = DeviceApplyAuthorization.check(settings.targetDeviceId, settings.deviceToken)
        if (authorization !is DeviceApplyAuthorization.AuthorizationState.Ready) {
            val message = (authorization as DeviceApplyAuthorization.AuthorizationState.Missing).message
            return@withLock record(
                manual = manual,
                message = message,
                result = DeviceApplyRunResult.NoOp(message),
            )
        }
        if (!manual && !settings.backgroundEnabled) {
            // 周期链的残留任务在关闭开关后仍可能被系统拉起；直接 no-op，不写状态。
            return@withLock DeviceApplyRunResult.NoOp("后台领取未开启")
        }
        val serverUrl = serverConfig.currentServerUrl()
        if (serverUrl.isNullOrBlank()) {
            return@withLock retry(manual, "尚未登录服务器，稍后重试")
        }
        if (serverConfig.currentToken().isNullOrBlank()) {
            return@withLock retry(manual, "当前登录会话不可用，稍后重试")
        }
        if (ServerAddress.isLocalModePreset(serverUrl)) {
            val started = runSuspendCatching {
                embeddedServerController.ensureStartedIfLocalMode(serverUrl)
            }.getOrElse { failure ->
                Log.w(DeviceApplyWorkSpec.LOG_TAG, "本机服务端拉起失败", failure)
                false
            }
            if (!started) {
                return@withLock retry(manual, "本机服务端未能拉起，稍后重试")
            }
            if (!localServerWarmup.awaitReady()) {
                return@withLock retry(manual, "本机服务端端口尚未就绪，稍后重试")
            }
        }

        val remote = remoteFactory.create(serverUrl, settings.deviceToken)
        val listed = try {
            withContext(ioDispatcher) { remote.listPending(settings.targetDeviceId) }
        } catch (e: ClientException) {
            return@withLock when (e.statusCode) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
                    val message = "设备 AI 令牌无效、已撤销或无权访问该设备；请在设置页更新专用设备令牌"
                    record(manual, message, DeviceApplyRunResult.Completed(message))
                }
                else -> {
                    Log.w(DeviceApplyWorkSpec.LOG_TAG, "设备结果包列表被拒绝 HTTP ${e.statusCode}")
                    val message = "设备结果包列表被服务端拒绝（HTTP ${e.statusCode}）"
                    record(manual, message, DeviceApplyRunResult.Completed(message))
                }
            }
        } catch (e: ServerException) {
            return@withLock retry(manual, "设备结果包列表请求失败（服务端 HTTP ${e.statusCode}），稍后重试")
        } catch (e: IOException) {
            return@withLock retry(manual, "设备结果包列表网络失败，稍后重试")
        }

        val targets = DeviceApplyPolicy.pendingForDevice(listed, settings.targetDeviceId)
        if (targets.isEmpty()) {
            return@withLock record(manual, "没有待领取的设备结果包", DeviceApplyRunResult.NoOp("没有待领取的设备结果包"))
        }

        var applied = 0
        var partial = 0
        var failed = 0
        for (summary in targets) {
            val outcome = try {
                process(remote, summary, settings.targetDeviceId)
            } catch (e: ClientException) {
                if (e.statusCode == HTTP_UNAUTHORIZED || e.statusCode == HTTP_FORBIDDEN) {
                    val message = "设备 AI 令牌在领取/下载时被拒绝；请在设置页更新专用设备令牌"
                    return@withLock record(manual, message, DeviceApplyRunResult.Completed(message))
                }
                if (e.statusCode == HTTP_CONFLICT || e.statusCode == HTTP_NOT_FOUND) {
                    // 领取/下载期间被取消、过期或删除：任务已不可执行，交给下一轮列表对账。
                    continue
                }
                Log.w(DeviceApplyWorkSpec.LOG_TAG, "设备结果包操作被拒绝 HTTP ${e.statusCode}")
                failed++
                continue
            }
            when (outcome) {
                is TaskOutcome.Applied -> applied++
                is TaskOutcome.Partial -> partial++
                is TaskOutcome.Failed -> failed++
                TaskOutcome.Skipped -> Unit
                TaskOutcome.DeviceAuthFailed -> {
                    val message = "设备 AI 令牌在回传时被拒绝，结果包保持服务端领取状态；请更新设备令牌后重试"
                    return@withLock record(manual, message, DeviceApplyRunResult.Completed(message))
                }
            }
        }
        val message = "设备结果包处理完成：成功 $applied，部分 $partial，失败 $failed"
        return@withLock record(manual, message, DeviceApplyRunResult.Completed(message))
    }

    private suspend fun process(
        remote: DeviceApplyRemote,
        summary: DeviceApplySummary,
        expectedTargetDeviceId: String,
    ): TaskOutcome {
        val claimed = try {
            withContext(ioDispatcher) { remote.claim(summary.id) }
        } catch (e: ClientException) {
            if (e.statusCode == HTTP_CONFLICT || e.statusCode == HTTP_NOT_FOUND) {
                // 已被其他执行体领取到终态/已删除：交给详情对账，不重复应用。
                return TaskOutcome.Skipped
            }
            throw e
        }
        if (claimed.status.isTerminal) return TaskOutcome.Skipped

        val task = withContext(ioDispatcher) { remote.get(summary.id) }
        if (task.status.isTerminal) return TaskOutcome.Skipped
        val validation = DeviceApplyPolicy.validateTask(task, expectedTargetDeviceId)
        if (!validation.ok) {
            return failWithReport(remote, task, validation.message ?: "结果包校验失败")
        }

        // 维护目录门禁放在下载前：未授权时尽早回传 failed，不浪费 64MiB 流量。
        val rootPath = maintenance.root.first()
        val gate = DeviceApplyLocalBackup.gate(
            rootPath,
            hasAllFilesAccess = withContext(ioDispatcher) { staging.hasAllFilesAccess() },
        )
        if (gate is LocalWriteGate.Blocked) {
            return failWithReport(remote, task, gate.message)
        }
        val root = File(requireNotNull(rootPath))

        val downloaded = mutableListOf<DownloadedSegment>()
        return try {
            for (segment in task.segments) {
                val file = withContext(ioDispatcher) { remote.downloadSegment(task.id, segment.kind) }
                val digest = withContext(ioDispatcher) { DeviceApplySegmentVerifier.fileDigest(file) }
                val verification = DeviceApplySegmentVerifier.verify(
                    expectedBytes = segment.bytes,
                    expectedSha256 = segment.sha256,
                    actualBytes = digest.bytes,
                    actualSha256 = digest.sha256,
                )
                downloaded += DownloadedSegment(
                    segment,
                    file,
                    DeviceLocalHash(segment.kind, digest.sha256),
                )
                if (!verification.ok) {
                    return failWithReport(
                        remote, task,
                        verification.message ?: "段校验失败",
                        localHashes = downloaded.map { it.localHash },
                    )
                }
            }

            // 先导出两代备份：失败即中止，绝不先写业务数据。
            val snapshot = runSuspendCatching { maintenance.export(automatic = true) }
            if (snapshot.isFailure) {
                Log.w(DeviceApplyWorkSpec.LOG_TAG, "应用前两代备份失败", snapshot.exceptionOrNull())
                return failWithReport(
                    remote, task,
                    "应用前保存本机两代备份失败（维护目录未授权/空间不足/导出失败），" +
                        "请到 数据管理 → 资料备份与归档 检查后重试",
                    localHashes = downloaded.map { it.localHash },
                )
            }
            val missing = withContext(ioDispatcher) { DeviceApplyLocalBackup.missingGenerationDirectories(root) }
            if (missing.isNotEmpty()) {
                return failWithReport(
                    remote, task,
                    "维护目录两代布局不完整（缺少 ${missing.joinToString("、")}），请到资料备份与归档重新选择目录",
                    localHashes = downloaded.map { it.localHash },
                )
            }

            val segmentResults = mutableListOf<SegmentApplyResult>()
            val readbacks = mutableListOf<SegmentReadback>()
            for (download in downloaded) {
                val segment = download.segment
                try {
                    when (segment.kind) {
                        DeviceSegmentKind.MASTER_TXT -> {
                            val text = withContext(ioDispatcher) {
                                download.file.readText(Charsets.UTF_8)
                            }
                            val summaryResult = authorRepository.importTxt(
                                DeviceApplyLocalBackup.AUTHOR_MASTER_FILE,
                                text,
                            )
                            segmentResults += SegmentApplyResult(segment.kind, applied = true)
                            readbacks += SegmentReadback(
                                segment.kind,
                                DeviceApplyReadback.fromMasterTxt(summaryResult),
                            )
                        }

                        DeviceSegmentKind.V2_BACKUP -> {
                            val bytes = withContext(ioDispatcher) { download.file.readBytes() }
                            when (val validated = withContext(ioDispatcher) {
                                BackupValidator.validate(DeviceApplyLocalBackup.BACKUP_FILE, bytes)
                            }) {
                                is BackupValidator.Result.Invalid -> {
                                    segmentResults += SegmentApplyResult(
                                        segment.kind,
                                        applied = false,
                                        message = validated.message,
                                    )
                                }

                                is BackupValidator.Result.Ok -> {
                                    val summaryResult = backupRepository.importBackup(validated.payload)
                                    segmentResults += SegmentApplyResult(segment.kind, applied = true)
                                    readbacks += SegmentReadback(
                                        segment.kind,
                                        DeviceApplyReadback.fromBackup(summaryResult),
                                    )
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: ClientException) {
                    if (e.statusCode == HTTP_CONFLICT && segment.kind == DeviceSegmentKind.MASTER_TXT) {
                        segmentResults += SegmentApplyResult(
                            segment.kind,
                            applied = false,
                            message = "作者 TXT 与上传写入条目冲突，需在作者主表页选择保留或移除后重试",
                        )
                    } else {
                        segmentResults += SegmentApplyResult(
                            segment.kind,
                            applied = false,
                            message = importRejectionMessage(e.statusCode),
                        )
                    }
                } catch (e: IOException) {
                    throw e
                } catch (e: ServerException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(DeviceApplyWorkSpec.LOG_TAG, "段 ${segment.kind.wireName} 应用失败", e)
                    segmentResults += SegmentApplyResult(
                        segment.kind,
                        applied = false,
                        message = "段应用失败（${e.javaClass.simpleName}）",
                    )
                }
            }

            val localWriteOk = if (segmentResults.any { it.applied }) {
                val refreshed = runSuspendCatching { maintenance.export(automatic = true) }
                if (refreshed.isFailure) {
                    Log.w(DeviceApplyWorkSpec.LOG_TAG, "应用后两代备份刷新失败", refreshed.exceptionOrNull())
                }
                refreshed.isSuccess
            } else {
                true
            }

            val byKind = readbacks.associateBy { it.kind }
            val comparisons = task.segments.map { segment ->
                SegmentReadbackComparison(
                    kind = segment.kind,
                    comparison = DeviceApplyReadback.compare(
                        segment.expectedReadback,
                        byKind[segment.kind]?.values.orEmpty(),
                    ),
                )
            }
            val readbackMatched = comparisons.all { it.comparison.matched }
            val status = DeviceApplyResultPlanner.resolve(
                authorized = true,
                segmentResults = segmentResults,
                readbackMatched = readbackMatched,
                localWriteOk = localWriteOk,
            )
            val message = DeviceApplyResultPlanner.message(status, segmentResults, comparisons, localWriteOk)
            val report = DeviceApplyReport(
                status = status,
                readback = DeviceApplyReadback.merge(
                    readbacks,
                    nestWhenMultiple = task.segments.size > 1,
                ),
                deviceLocalHashes = downloaded.map { it.localHash }.sortedBy { it.kind.ordinal },
                message = message,
            )
            return when (reportResult(remote, task, report)) {
                ReportResult.Reported -> when (status) {
                    DeviceResultStatus.APPLIED -> TaskOutcome.Applied(message)
                    DeviceResultStatus.PARTIAL -> TaskOutcome.Partial(message)
                    DeviceResultStatus.FAILED -> TaskOutcome.Failed(message)
                }
                ReportResult.AlreadyTerminal -> TaskOutcome.Skipped
                ReportResult.Conflict -> TaskOutcome.Failed("结果包已有不同终态，未覆盖")
                ReportResult.DeviceAuthFailed -> TaskOutcome.DeviceAuthFailed
            }
        } catch (e: ClientException) {
            // 下载/详情期间被取消或过期：不重复应用，也不把它计为业务失败。
            if (e.statusCode == HTTP_CONFLICT || e.statusCode == HTTP_NOT_FOUND) {
                TaskOutcome.Skipped
            } else {
                throw e
            }
        } finally {
            for (download in downloaded) {
                // 临时包只在 App 缓存；删除失败不改变业务终态（下轮会重新下载并校验）。
                runCatching { download.file.delete() }
            }
        }
    }

    private suspend fun failWithReport(
        remote: DeviceApplyRemote,
        task: DeviceApplyDetail,
        message: String,
        localHashes: List<DeviceLocalHash> = emptyList(),
    ): TaskOutcome {
        val report = DeviceApplyReport(
            status = DeviceResultStatus.FAILED,
            deviceLocalHashes = localHashes.sortedBy { it.kind.ordinal },
            message = DeviceApplyMessages.truncate(message),
        )
        return when (reportResult(remote, task, report)) {
            ReportResult.Reported -> TaskOutcome.Failed(message)
            ReportResult.AlreadyTerminal -> TaskOutcome.Skipped
            ReportResult.Conflict -> TaskOutcome.Failed("结果包已有不同终态，未覆盖")
            ReportResult.DeviceAuthFailed -> TaskOutcome.DeviceAuthFailed
        }
    }

    private suspend fun reportResult(
        remote: DeviceApplyRemote,
        task: DeviceApplyDetail,
        report: DeviceApplyReport,
    ): ReportResult = try {
        withContext(ioDispatcher) { remote.report(task.id, report) }
        ReportResult.Reported
    } catch (e: ClientException) {
        when (e.statusCode) {
            HTTP_CONFLICT -> {
                // 协议规定：终态结果不同才 409。先对账；已终态说明此前某次执行已回传，
                // 不要改结果重试，也不要覆盖。
                val current = runSuspendCatching { withContext(ioDispatcher) { remote.get(task.id) } }
                val detail = current.getOrNull()
                if (detail != null && detail.status.isTerminal) {
                    ReportResult.AlreadyTerminal
                } else {
                    Log.w(DeviceApplyWorkSpec.LOG_TAG, "结果回传 409 但任务未终态，停止重试")
                    ReportResult.Conflict
                }
            }

            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ReportResult.DeviceAuthFailed
            else -> {
                Log.w(DeviceApplyWorkSpec.LOG_TAG, "结果回传被拒绝 HTTP ${e.statusCode}")
                ReportResult.Conflict
            }
        }
    } catch (e: ServerException) {
        throw e
    } catch (e: IOException) {
        throw e
    }

    private fun importRejectionMessage(statusCode: Int): String = when (statusCode) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "当前登录会话无权导入，请重新登录后重试"
        HTTP_BAD_REQUEST -> "导入请求被服务端拒绝（HTTP 400）"
        HTTP_TOO_LARGE -> "导入内容超过服务端上限"
        else -> "导入被服务端拒绝（HTTP $statusCode）"
    }

    private suspend fun retry(manual: Boolean, message: String): DeviceApplyRunResult =
        record(manual, message, DeviceApplyRunResult.Retry(message))

    private suspend fun record(
        manual: Boolean,
        message: String,
        result: DeviceApplyRunResult,
    ): DeviceApplyRunResult {
        if (manual || result !is DeviceApplyRunResult.NoOp) {
            // 设置写失败只影响本机状态行，不影响服务端账本；取消仍要传播。
            runSuspendCatching { settingsRepository.recordLastRun(message) }
        }
        return result
    }

    private data class DownloadedSegment(
        val segment: DeviceApplySegment,
        val file: File,
        val localHash: DeviceLocalHash,
    )

    private sealed interface TaskOutcome {
        data class Applied(val message: String) : TaskOutcome
        data class Partial(val message: String) : TaskOutcome
        data class Failed(val message: String) : TaskOutcome
        data object Skipped : TaskOutcome
        data object DeviceAuthFailed : TaskOutcome
    }

    private enum class ReportResult { Reported, AlreadyTerminal, Conflict, DeviceAuthFailed }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_TOO_LARGE = 413
    }
}

/** Worker 执行结果：Retry=可重试；其余按 success 收尾（周期链不会因 failure 停摆）。 */
sealed interface DeviceApplyRunResult {
    val message: String

    data class NoOp(override val message: String) : DeviceApplyRunResult
    data class Completed(override val message: String) : DeviceApplyRunResult
    data class Retry(override val message: String) : DeviceApplyRunResult
}
