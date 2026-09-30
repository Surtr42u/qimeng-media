package media.qimeng.app.core.data.upload

import android.util.Log
import java.io.InputStream

/**
 * 断点续传会话流编排器（ADR-0028 Android 接入批）：创建/复用会话 → 每轮先 GET 探测
 * 服务端权威 offset → 按片 PATCH 追加 → offset 达 size 后 complete，把与直传同构的
 * 资产响应收敛为 [UploadOutcome.Success]（worker 侧挂靠/归档后处理管线零改动复用）。
 *
 * 状态设计（任务书口径）：断点 offset 以服务端为唯一真相源，本地不存断点——每轮
 * WorkManager 重试（含首次）都先 GET 探测再续传，天然幂等；会话 id 经
 * [UploadSessionRegistry] 进程内记忆（丢表 = 重建会话的最后手段）。
 * 全部传输错误经 [SessionCall] 收敛，本类无 IO 细节（readChunk 由调用方注入），
 * 纯 JVM 单测锁定时序（ChunkedUploadSessionTest）。
 */
internal class ChunkedUploadSession(
    private val client: UploadSessionClient,
    /** 打开定位到 [offset] 的源分片流（content:// 每片重开流 skip 到位）；null = 源暂不可读 */
    private val readChunk: (offset: Long) -> InputStream?,
    private val totalBytes: Long,
    private val libraryId: String,
    private val fileName: String,
    /** 目标子目录（库内相对路径；空 = 库根）：create 时透传，服务端校验后随会话落位 */
    private val dir: String,
    private val isCancelled: () -> Boolean,
    /** 上一轮尝试遗留的会话 id（null = 无记忆 → 创建新会话） */
    private val previousSessionId: String?,
    /** 新建/重建会话后的记忆回调（[UploadSessionRegistry.remember]） */
    private val onSessionCreated: (String) -> Unit,
    /** 单片大小（默认 [UploadRouting.CHUNK_BYTES]；测试注入小片长锁定分片序列） */
    private val chunkBytes: Long = UploadRouting.CHUNK_BYTES,
    /** 分片粒度进度回调（每片成功推进一次，参数 = 服务端权威 offset） */
    private val onProgress: suspend (bytesDone: Long) -> Unit = {},
) {

    suspend fun run(): UploadOutcome {
        // 续传优先：有进程内会话记忆直接探测续传；无记忆才创建新会话（创建即做服务端
        // 可前置校验——白名单/超限/上传关闭，4xx 直接落既有 Permanent 失败路径）
        var sessionId = previousSessionId ?: when (val created = client.create(libraryId, fileName, dir, totalBytes)) {
            is SessionCall.Ok -> {
                onSessionCreated(created.value)
                created.value
            }
            is SessionCall.ClientError -> return UploadOutcome.Permanent(created.message)
            is SessionCall.Transient -> return UploadOutcome.Retryable("创建上传会话失败：${created.reason}")
        }
        var offset = UNPROBED
        var rebuilds = 0
        var noAdvanceRounds = 0

        /**
         * 会话失效（404 = 过期被清扫/服务端重启）后的重建：重新 create 从 0 续传是
         * 最后手段（日志记档）。单次 run 上限 [MAX_SESSION_REBUILDS] 次，防 create →
         * probe 404 的服务端异常死循环；超限转 Retryable 交 WorkManager 下轮重开。
         * 返回 null = 重建成功（sessionId/offset 已复位）；非 null = 终局 outcome。
         */
        suspend fun rebuild(reason: String): UploadOutcome? {
            if (rebuilds >= MAX_SESSION_REBUILDS) {
                return UploadOutcome.Retryable("上传会话反复失效（$reason），转下轮重试")
            }
            rebuilds += 1
            Log.w(
                UploadWorker.LOG_TAG,
                "chunked-rebuild #$rebuilds file=$fileName reason=$reason（会话失效，从 0 重传）",
            )
            noAdvanceRounds = 0
            return when (val created = client.create(libraryId, fileName, dir, totalBytes)) {
                is SessionCall.Ok -> {
                    onSessionCreated(created.value)
                    sessionId = created.value
                    offset = UNPROBED
                    null
                }
                is SessionCall.ClientError -> UploadOutcome.Permanent(created.message)
                is SessionCall.Transient -> UploadOutcome.Retryable("重建上传会话失败：${created.reason}")
            }
        }

        while (true) {
            if (isCancelled()) {
                // 用户显式取消：DELETE 会话（best-effort，失败不阻断）+ 既有取消终态
                client.abandon(sessionId)
                return UploadOutcome.Cancelled
            }

            if (offset == UNPROBED) {
                // 每轮（含首次）先探测：服务端是断点唯一真相源
                when (val probed = client.probe(sessionId)) {
                    is SessionCall.Ok -> offset = probed.value
                    is SessionCall.ClientError -> when (probed.code) {
                        HTTP_NOT_FOUND -> {
                            val rebuilt = rebuild("断点探测 404（会话已被清扫/服务端重启）")
                            if (rebuilt != null) return rebuilt
                            continue
                        }
                        else -> return UploadOutcome.Permanent(probed.message)
                    }
                    is SessionCall.Transient -> return UploadOutcome.Retryable("断点探测失败：${probed.reason}")
                }
            }

            if (offset >= totalBytes) {
                // complete 与直传逐字同语义（同名自动改名、永不 409），响应即同构资产详情
                return when (val done = client.complete(sessionId)) {
                    is SessionCall.Ok -> UploadOutcome.Success(
                        finalFileName = done.value.fileName ?: FINAL_NAME_FALLBACK,
                        // 挂靠序列的目标 id；缺 id 由 worker 收敛为挂靠失败（既有路径）
                        assetId = done.value.id,
                    )
                    is SessionCall.ClientError -> when (done.code) {
                        HTTP_NOT_FOUND -> {
                            val rebuilt = rebuild("完结时 404（会话已被清扫）")
                            if (rebuilt != null) return rebuilt
                            continue
                        }
                        else -> UploadOutcome.Permanent(done.message)
                    }
                    is SessionCall.Transient -> UploadOutcome.Retryable("完结上传会话失败：${done.reason}")
                }
            }

            val chunkLength = minOf(chunkBytes, totalBytes - offset)
            val source = readChunk(offset)
            if (source == null) {
                return UploadOutcome.Retryable(OPEN_FAILED_MESSAGE)
            }
            val patched = try {
                source.use { client.patch(sessionId, offset, it, chunkLength, isCancelled) }
            } catch (e: UploadCancelledException) {
                // 写流中用户取消（64KB 粒度断流，直传同款信号异常）：放弃会话 + 取消终态
                client.abandon(sessionId)
                return UploadOutcome.Cancelled
            }
            when (patched) {
                is SessionCall.Ok -> {
                    // 用响应里的权威 offset 推进；进度按分片粒度（每片一跳）映射既有机制
                    // （UI 语义粗粒度化，任务书记档口径）
                    if (patched.value <= offset) {
                        // 防御：服务端 offset 未推进（异常实现）——连续多轮即中止转重试，
                        // 避免前台任务空转（真实服务端串行追加语义下不可达）
                        noAdvanceRounds += 1
                        if (noAdvanceRounds >= MAX_NO_ADVANCE_ROUNDS) {
                            return UploadOutcome.Retryable("服务端 offset 多轮未推进，转下轮探测续传")
                        }
                    } else {
                        noAdvanceRounds = 0
                        offset = patched.value
                        onProgress(offset)
                    }
                }

                is SessionCall.ClientError -> when {
                    // offset 过期（权威漂移）：409 响应体即权威 UploadSession，立即重同步
                    // 继续发送，不算失败；无权威体的防御分支回退重新探测
                    patched.code == HTTP_CONFLICT -> offset = patched.authorityOffset ?: UNPROBED

                    patched.code == HTTP_NOT_FOUND -> {
                        val rebuilt = rebuild("分片时 404（会话已被清扫）")
                        if (rebuilt != null) return rebuilt
                    }

                    // 4xx 校验类（400 累计超 size / 413 单片超限等）：既有失败路径
                    // （条目标错带原因，不无限重试）
                    else -> return UploadOutcome.Permanent(patched.message)
                }

                is SessionCall.Transient -> return UploadOutcome.Retryable("分片上传失败：${patched.reason}")
            }
        }
    }

    private companion object {
        /** 尚未探测哨兵（offset 合法域 ≥ 0） */
        const val UNPROBED = -1L

        const val HTTP_NOT_FOUND = 404

        const val HTTP_CONFLICT = 409

        /** 单次 run 内会话重建上限（防 create→probe 404 异常死循环；超限转 WorkManager 重试） */
        const val MAX_SESSION_REBUILDS = 2

        /** 连续未推进轮次上限（服务端异常防御；进度不前即中止转重试，避免前台任务空转） */
        const val MAX_NO_ADVANCE_ROUNDS = 8

        /** 分片源流打不开的可重试文案（镜像 AssetUploader.OPEN_FAILED_MESSAGE——直传文件冻结不动，双侧同步） */
        const val OPEN_FAILED_MESSAGE = "本地文件暂不可读（可能已被移动），稍后自动重试"

        /** complete 响应缺 fileName 时的展示回退（协议 AssetDetail.fileName 理论恒在） */
        const val FINAL_NAME_FALLBACK = "（服务端未返回文件名）"
    }
}
