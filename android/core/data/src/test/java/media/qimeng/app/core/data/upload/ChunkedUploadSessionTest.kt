package media.qimeng.app.core.data.upload

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传会话流编排锁定（ADR-0028 Android 接入批，任务书测试面）：
 * - 全链分片序列（create → probe → PATCH×N → complete）与分片粒度进度；
 * - 中断续传：下轮先 GET 探测权威 offset，再从断点 PATCH（服务端 offset 唯一真相源）；
 * - PATCH 409：用响应体权威 offset 立即重同步继续，不重探测、不算失败；
 * - 会话 404（探测/分片/完结三处）：重建会话从 0 续传（最后手段，单次 run 上限 2 次）；
 * - complete 资产响应 → UploadOutcome.Success(id+最终名)——worker 既有挂靠/归档
 *   管线的入参接线（UploadWorkSpecTest 已锁 outcomeToResult/attacher 侧）；
 * - dir 目标子目录透传 create（协议 CreateUploadRequest.dir，含重建会话路径）；
 * - 4xx 校验类失败落 Permanent 文案透传（既有失败路径）；用户取消 abandon + Cancelled。
 * 传输替身就地定义（本模块测试不依赖 :core:testing，UploadAttacherTest 同款）。
 * 小片长（3 字节）注入 chunkBytes 锁定分片序列，与生产 8MB 常量解耦。
 */
class ChunkedUploadSessionTest {

    /** 时序记录替身：按预置脚本出牌，全量记录调用序列（create 默认回 session-1） */
    private class FakeSessionClient : UploadSessionClient {
        val calls = mutableListOf<String>()
        var createResponses = ArrayDeque<SessionCall<String>>()
        var probeResponses = ArrayDeque<SessionCall<Long>>()
        var patchResponses = ArrayDeque<SessionCall<Long>>()
        var completeResponses = ArrayDeque<SessionCall<UploadApiBodies.UploadResult>>()
        var patchThrow: Exception? = null
        var abandonCount = 0

        override suspend fun create(libraryId: String, fileName: String, dir: String, sizeBytes: Long): SessionCall<String> {
            calls += "create:$libraryId:$fileName:$dir:$sizeBytes"
            return createResponses.removeFirstOrNull() ?: SessionCall.Ok("session-1")
        }

        override suspend fun probe(sessionId: String): SessionCall<Long> {
            calls += "probe:$sessionId"
            return probeResponses.removeFirstOrNull() ?: SessionCall.Ok(0L)
        }

        override suspend fun patch(
            sessionId: String,
            offset: Long,
            source: InputStream,
            length: Long,
            isCancelled: () -> Boolean,
        ): SessionCall<Long> {
            calls += "patch:$sessionId@$offset+$length"
            patchThrow?.let { throw it }
            // 模拟消费分片字节（内容无关断言，只验时序与 offset）
            source.read(ByteArray(length.toInt()))
            return patchResponses.removeFirstOrNull() ?: SessionCall.Ok(offset + length)
        }

        override suspend fun complete(sessionId: String): SessionCall<UploadApiBodies.UploadResult> {
            calls += "complete:$sessionId"
            return completeResponses.removeFirstOrNull()
                ?: SessionCall.Ok(UploadApiBodies.UploadResult(id = "asset-1", fileName = "a (2).jpg"))
        }

        override suspend fun abandon(sessionId: String) {
            calls += "abandon:$sessionId"
            abandonCount += 1
        }
    }

    private val client = FakeSessionClient()
    private val progress = mutableListOf<Long>()

    /** total=10、片长=3 的编排器（readChunk 从 offset 读到源尾，传输侧只消费 length） */
    private fun session(
        previousSessionId: String? = null,
        cancelled: () -> Boolean = { false },
        onSessionCreated: (String) -> Unit = {},
        dir: String = "",
    ): ChunkedUploadSession {
        val total = 10L
        val data = ByteArray(total.toInt()) { it.toByte() }
        return ChunkedUploadSession(
            client = client,
            readChunk = { offset -> ByteArrayInputStream(data, offset.toInt(), (total - offset).toInt()) },
            totalBytes = total,
            libraryId = "lib-1",
            fileName = "a.jpg",
            dir = dir,
            isCancelled = cancelled,
            previousSessionId = previousSessionId,
            onSessionCreated = onSessionCreated,
            chunkBytes = 3,
            onProgress = { progress += it },
        )
    }

    @Test
    fun `全新上传_探测0起_分片序列至完成_complete接线`() = runTest {
        client.completeResponses += SessionCall.Ok(
            UploadApiBodies.UploadResult(id = "asset-9", fileName = "最终名.jpg"),
        )
        val outcome = session().run()
        assertTrue(outcome is UploadOutcome.Success)
        // complete 响应与直传同构：id + 冲突自动重命名后的最终名原样透传，
        // worker 侧 resolveOutcome（挂靠）与归档分派即以此为准（接线断言）
        assertEquals("最终名.jpg", (outcome as UploadOutcome.Success).finalFileName)
        assertEquals("asset-9", outcome.assetId)
        assertEquals(
            listOf(
                "create:lib-1:a.jpg::10",
                "probe:session-1",
                "patch:session-1@0+3",
                "patch:session-1@3+3",
                "patch:session-1@6+3",
                "patch:session-1@9+1",
                "complete:session-1",
            ),
            client.calls,
        )
        // 分片粒度进度：每片完成按服务端权威 offset 推进一次
        assertEquals(listOf(3L, 6L, 9L, 10L), progress)
    }

    @Test
    fun `子目录dir透传到create请求`() = runTest {
        // dir 已入分片协议：编排器把目标子目录原样交给 create（服务端按与直传
        // 同规则校验后随会话落位）；重建会话（rebuild）同样带同一 dir
        client.probeResponses += SessionCall.ClientError(404, "会话不存在")
        client.createResponses += SessionCall.Ok("session-2")
        val outcome = session(previousSessionId = "session-old", dir = "画作/2024").run()
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals("create:lib-1:a.jpg:画作/2024:10", client.calls.first { it.startsWith("create:") })
    }

    @Test
    fun `中断后下轮先GET探测权威offset再从断点续传`() = runTest {
        val remembered = mutableListOf<String>()
        // 第一轮：首片后网络断（Transient）→ Retryable（WorkManager 退避），会话保留
        client.patchResponses += SessionCall.Transient("网络异常：连接重置")
        val first = session(onSessionCreated = remembered::add).run()
        assertTrue(first is UploadOutcome.Retryable)
        assertEquals(listOf("create:lib-1:a.jpg::10", "probe:session-1", "patch:session-1@0+3"), client.calls)
        assertEquals(listOf("session-1"), remembered)

        // 第二轮（重试执行）：无 create——进程内会话记忆直接探测；服务端权威 6 → 从 6 续传
        client.probeResponses += SessionCall.Ok(6L)
        val second = session(previousSessionId = "session-1").run()
        assertTrue(second is UploadOutcome.Success)
        assertEquals(
            listOf("probe:session-1", "patch:session-1@6+3", "patch:session-1@9+1", "complete:session-1"),
            client.calls.drop(3),
        )
    }

    @Test
    fun `PATCH409_用响应体权威offset立即重同步_不重探测不算失败`() = runTest {
        client.patchResponses += SessionCall.ClientError(409, "分片起点过期", authorityOffset = 5L)
        val outcome = session().run()
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(
            listOf(
                "create:lib-1:a.jpg::10",
                "probe:session-1",
                "patch:session-1@0+3",
                // 409 后立即以权威 5 重发（中间无 probe）——不算失败、不耗重试
                "patch:session-1@5+3",
                "patch:session-1@8+2",
                "complete:session-1",
            ),
            client.calls,
        )
        // 409 轮不推进进度；权威 offset 随后续分片完成一并体现
        assertEquals(listOf(8L, 10L), progress)
    }

    @Test
    fun `探测404_重建会话从0续传并记忆新会话`() = runTest {
        client.probeResponses += SessionCall.ClientError(404, "会话不存在")
        client.createResponses += SessionCall.Ok("session-2")
        val remembered = mutableListOf<String>()
        val outcome = session(previousSessionId = "session-old", onSessionCreated = remembered::add).run()
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(listOf("session-2"), remembered)
        assertEquals(
            listOf(
                // 旧会话探测 404（已被清扫）→ 重建 create → 新会话 probe 0 → 全量重传
                "probe:session-old",
                "create:lib-1:a.jpg::10",
                "probe:session-2",
                "patch:session-2@0+3",
                "patch:session-2@3+3",
                "patch:session-2@6+3",
                "patch:session-2@9+1",
                "complete:session-2",
            ),
            client.calls,
        )
    }

    @Test
    fun `分片404_重建会话重传剩余部分`() = runTest {
        client.patchResponses += SessionCall.ClientError(404, "会话不存在")
        client.createResponses += SessionCall.Ok("session-2")
        val outcome = session(previousSessionId = "session-old").run()
        assertTrue(outcome is UploadOutcome.Success)
        // 首片命中 404 → 重建 → probe 0 起全量重传 → complete
        assertEquals(
            listOf(
                "probe:session-old",
                "patch:session-old@0+3",
                "create:lib-1:a.jpg::10",
                "probe:session-2",
                "patch:session-2@0+3",
                "patch:session-2@3+3",
                "patch:session-2@6+3",
                "patch:session-2@9+1",
                "complete:session-2",
            ),
            client.calls,
        )
    }

    @Test
    fun `complete404_重建会话全量重传后再完结成功`() = runTest {
        // 进程内会话记忆 session-1 起步（上轮 Retryable 保留）；重建 create 出牌 session-2
        client.createResponses += SessionCall.Ok("session-2")
        client.completeResponses += SessionCall.ClientError(404, "会话不存在")
        val outcome = session(previousSessionId = "session-1").run()
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals(
            listOf(
                "probe:session-1",
                "patch:session-1@0+3",
                "patch:session-1@3+3",
                "patch:session-1@6+3",
                "patch:session-1@9+1",
                "complete:session-1", // 404（恰在完结时被清扫）
                "create:lib-1:a.jpg::10",
                "probe:session-2",
                "patch:session-2@0+3",
                "patch:session-2@3+3",
                "patch:session-2@6+3",
                "patch:session-2@9+1",
                "complete:session-2",
            ),
            client.calls,
        )
    }

    @Test
    fun `会话反复失效_重建超限转可重试不死循环`() = runTest {
        client.probeResponses += SessionCall.ClientError(404, "会话不存在")
        client.probeResponses += SessionCall.ClientError(404, "会话不存在")
        client.probeResponses += SessionCall.ClientError(404, "会话不存在")
        val outcome = session(previousSessionId = "session-old").run()
        // 上限 2 次重建后放 Retryable：交 WorkManager 下轮重开（防御 create→404 死循环）
        assertTrue(outcome is UploadOutcome.Retryable)
        assertEquals(2, client.calls.count { it.startsWith("create:") })
    }

    @Test
    fun `校验类4xx_落Permanent带服务端文案_不重建不放弃`() = runTest {
        client.patchResponses += SessionCall.ClientError(413, "UPLOAD_TOO_LARGE 单片超过 32MB 上限")
        val outcome = session().run()
        assertTrue(outcome is UploadOutcome.Permanent)
        assertEquals("UPLOAD_TOO_LARGE 单片超过 32MB 上限", (outcome as UploadOutcome.Permanent).serverMessage)
        // 既有失败路径口径：不重试不重建，会话留给服务端 TTL，也不误报取消
        assertTrue(client.calls.none { it.startsWith("abandon") })
        assertEquals(listOf("create:lib-1:a.jpg::10", "probe:session-1", "patch:session-1@0+3"), client.calls)
    }

    @Test
    fun `创建会话4xx_直接Permanent不进分片循环`() = runTest {
        client.createResponses += SessionCall.ClientError(413, "UPLOAD_TOO_LARGE 声明 size 超过上限")
        val outcome = session().run()
        assertTrue(outcome is UploadOutcome.Permanent)
        assertEquals("UPLOAD_TOO_LARGE 声明 size 超过上限", (outcome as UploadOutcome.Permanent).serverMessage)
        assertEquals(listOf("create:lib-1:a.jpg::10"), client.calls)
    }

    @Test
    fun `循环顶检测取消_abandon会话并落取消终态`() = runTest {
        // 第 2 次查询（首片完成后）起恒为已取消 → 下一轮循环顶拦截
        var checks = 0
        val outcome = session(cancelled = { checks++ >= 1 }).run()
        assertTrue(outcome is UploadOutcome.Cancelled)
        assertEquals(1, client.abandonCount)
        assertEquals(
            listOf("create:lib-1:a.jpg::10", "probe:session-1", "patch:session-1@0+3", "abandon:session-1"),
            client.calls,
        )
    }

    @Test
    fun `写流中取消_断流并abandon会话`() = runTest {
        // OkHttp 写线程内取消以 UploadCancelledException 穿透（直传同款信号异常）
        client.patchThrow = UploadCancelledException()
        val outcome = session().run()
        assertTrue(outcome is UploadOutcome.Cancelled)
        assertEquals(1, client.abandonCount)
        assertEquals(
            listOf("create:lib-1:a.jpg::10", "probe:session-1", "patch:session-1@0+3", "abandon:session-1"),
            client.calls,
        )
    }
}
