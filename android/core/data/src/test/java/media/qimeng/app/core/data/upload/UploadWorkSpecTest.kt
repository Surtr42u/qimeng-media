package media.qimeng.app.core.data.upload

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上传队列契约与失败重试状态机锁定（M4-5）。串行性本身由 WorkManager unique 链官方语义保证。 */
class UploadWorkSpecTest {

    private fun spec(
        uri: String = "content://media/external/images/1",
        displayName: String = "IMG_2026.jpg",
    ) = UploadWorkSpec.UploadRequestSpec(
        localId = "local-1",
        uri = uri,
        libraryId = "lib-uuid",
        dir = "photos/2026",
        displayName = displayName,
        sizeBytes = 12345L,
    )

    // ---- 入队载荷映射 ----

    @Test
    fun `入队载荷往返无损`() {
        val data = UploadWorkSpec.itemToInputData(spec())
        val back = UploadWorkSpec.specFromInputData(data)
        assertNotNull(back)
        assertEquals("local-1", back!!.localId)
        assertEquals("content://media/external/images/1", back.uri)
        assertEquals("lib-uuid", back.libraryId)
        assertEquals("photos/2026", back.dir)
        assertEquals("IMG_2026.jpg", back.displayName)
        assertEquals(12345L, back.sizeBytes)
    }

    // ---- 兼容锁定：旧在途载荷（含已退役挂靠键）反解为纯上传 ----

    /**
     * 协议批 2026-09-25：上传挂靠参数（authorId/authorName/source）随协议退役，
     * 键位常量与新载荷一并拆除。WorkManager 在途旧队列可能仍携带挂靠键——
     * Data 反解按「未知键忽略」语义收敛为纯上传任务（缺读新键即静默降级），本组
     * 用例锁定该兼容口径：反解成功、必需字段无损。
     */
    @Test
    fun `含未知挂靠键的旧载荷反解为纯上传且正常执行`() {
        val legacyWithAttach = androidx.work.Data.Builder()
            .putString(UploadWorkSpec.KEY_LOCAL_ID, "local-1")
            .putString(UploadWorkSpec.KEY_URI, "content://media/external/images/1")
            .putString(UploadWorkSpec.KEY_LIBRARY_ID, "lib-uuid")
            .putString(UploadWorkSpec.KEY_DIR, "photos/2026")
            .putString(UploadWorkSpec.KEY_DISPLAY_NAME, "IMG_2026.jpg")
            .putLong(UploadWorkSpec.KEY_SIZE_BYTES, 12345L)
            // 旧版挂靠键（本版已删常量，按字符串字面量写）：反解必须整体忽略
            .putString("authorId", "a-1")
            .putString("authorName", "旧作者")
            .putStringArray("sources", arrayOf("kemono", "r34"))
            .build()
        val back = UploadWorkSpec.specFromInputData(legacyWithAttach)
        assertNotNull(back)
        assertEquals("local-1", back!!.localId)
        assertEquals("content://media/external/images/1", back.uri)
        assertEquals("lib-uuid", back.libraryId)
        assertEquals("photos/2026", back.dir)
        assertEquals("IMG_2026.jpg", back.displayName)
        assertEquals(12345L, back.sizeBytes)
    }

    @Test
    fun `旧载荷无挂靠键反解为纯上传`() {
        // 兼容口径：挂靠批之前形态的入队载荷（只写必需键）照常反解
        val legacy = androidx.work.Data.Builder()
            .putString(UploadWorkSpec.KEY_LOCAL_ID, "local-1")
            .putString(UploadWorkSpec.KEY_URI, "content://media/external/images/1")
            .putString(UploadWorkSpec.KEY_LIBRARY_ID, "lib-uuid")
            .putString(UploadWorkSpec.KEY_DIR, "photos/2026")
            .putString(UploadWorkSpec.KEY_DISPLAY_NAME, "IMG_2026.jpg")
            .putLong(UploadWorkSpec.KEY_SIZE_BYTES, 12345L)
            .build()
        val back = UploadWorkSpec.specFromInputData(legacy)
        assertNotNull(back)
        assertEquals("IMG_2026.jpg", back!!.displayName)
        assertEquals(12345L, back.sizeBytes)
    }

    @Test
    fun `载荷缺失键返回null走终局失败`() {
        val data = androidx.work.Data.Builder()
            .putString(UploadWorkSpec.KEY_LOCAL_ID, "local-1")
            .putString(UploadWorkSpec.KEY_DISPLAY_NAME, "IMG_2026.jpg")
            .build()
        assertNull(UploadWorkSpec.specFromInputData(data))
    }

    @Test
    fun `tag前缀反查localId`() {
        val tags = setOf("qm-upload", UploadWorkSpec.TAG_ITEM_PREFIX + "local-9")
        assertEquals("local-9", UploadWorkSpec.localIdFromTag(tags))
        assertNull(UploadWorkSpec.localIdFromTag(setOf("qm-upload")))
    }

    // ---- 失败重试状态机 ----

    @Test
    fun `成功映射为success并携带最终文件名`() {
        val result = UploadWorkSpec.outcomeToResult(
            UploadOutcome.Success(finalFileName = "IMG_2026 (2).jpg"),
            runAttemptCount = 0,
        )
        val success = result as ListenableWorker.Result.Success
        assertEquals("IMG_2026 (2).jpg", success.outputData.getString(UploadWorkSpec.KEY_FINAL_FILE_NAME))
    }

    @Test
    fun `永久失败映射为failure并透传文案`() {
        val result = UploadWorkSpec.outcomeToResult(
            UploadOutcome.Permanent(serverMessage = "UPLOAD_DISABLED 上传已被关闭"),
            runAttemptCount = 0,
        )
        val failure = result as ListenableWorker.Result.Failure
        assertEquals("UPLOAD_DISABLED 上传已被关闭", failure.outputData.getString(UploadWorkSpec.KEY_ERROR_MESSAGE))
    }

    @Test
    fun `可重试失败未耗尽额度走retry`() {
        for (attempt in 0 until UploadWorkSpec.MAX_RETRIES) {
            val result = UploadWorkSpec.outcomeToResult(
                UploadOutcome.Retryable(reason = "网络异常"),
                runAttemptCount = attempt,
            )
            assertTrue("attempt=$attempt 应为 retry", result is ListenableWorker.Result.Retry)
        }
    }

    @Test
    fun `可重试失败耗尽额度转终局failure`() {
        val result = UploadWorkSpec.outcomeToResult(
            UploadOutcome.Retryable(reason = "网络异常"),
            runAttemptCount = UploadWorkSpec.MAX_RETRIES,
        )
        val failure = result as ListenableWorker.Result.Failure
        val message = failure.outputData.getString(UploadWorkSpec.KEY_ERROR_MESSAGE)
        assertTrue(message!!.contains("重试 ${UploadWorkSpec.MAX_RETRIES} 次"))
    }

    @Test
    fun `用户取消映射为success并携带取消标志与文案`() {
        val result = UploadWorkSpec.outcomeToResult(
            UploadOutcome.Cancelled,
            runAttemptCount = 0,
        )
        // 必须是 Success 类型（批C 342 笔 P1 返工）：failure 会经 WorkManager 引擎
        // iterativelyFailWorkAndDependents 级联把 unique 链上后续任务全部标 FAILED
        // （永不执行）——取消走 success 载荷承载语义，下游正常解锁执行
        val success = result as ListenableWorker.Result.Success
        assertTrue(success.outputData.getBoolean(UploadWorkSpec.KEY_CANCELLED, false))
        assertEquals(UploadWorkSpec.CANCELLED_MESSAGE, success.outputData.getString(UploadWorkSpec.KEY_ERROR_MESSAGE))
    }

    @Test
    fun `取消不传播——success类型即解锁下游的唯一前提`() {
        // 传播语义的纯函数表达：引擎只对 Failure 级联（CancelWorkRunnable/
        // iterativelyFailWorkAndDependents），Success 恒不级联。断言三种终态映射的
        // 类型面——取消与成功同为 Success（不触发级联）、永久失败与重试耗尽为 Failure
        // （既有级联语义，非本批取消路径）。
        val cancelled = UploadWorkSpec.outcomeToResult(UploadOutcome.Cancelled, 0)
        val success = UploadWorkSpec.outcomeToResult(UploadOutcome.Success("a.jpg"), 0)
        assertTrue(cancelled is ListenableWorker.Result.Success)
        assertTrue(success is ListenableWorker.Result.Success)

        val permanent = UploadWorkSpec.outcomeToResult(UploadOutcome.Permanent("4xx"), 0)
        val exhausted = UploadWorkSpec.outcomeToResult(
            UploadOutcome.Retryable("网络异常"),
            UploadWorkSpec.MAX_RETRIES,
        )
        assertTrue(permanent is ListenableWorker.Result.Failure)
        assertTrue(exhausted is ListenableWorker.Result.Failure)
    }

    @Test
    fun `取消标记注册表置位与消费`() {
        val registry = UploadCancelRegistry()
        assertFalse(registry.isCancelled("local-1"))
        registry.cancel("local-1")
        assertTrue(registry.isCancelled("local-1"))
        // 幂等置位 + consume 清除
        registry.cancel("local-1")
        assertTrue(registry.isCancelled("local-1"))
        registry.consume("local-1")
        assertFalse(registry.isCancelled("local-1"))
    }

    // ---- 进度计算 ----

    @Test
    fun `进度百分比钳制在0到100`() {
        assertEquals(0, UploadWorkSpec.progressPercent(0, 100))
        assertEquals(50, UploadWorkSpec.progressPercent(50, 100))
        assertEquals(100, UploadWorkSpec.progressPercent(100, 100))
        assertEquals(100, UploadWorkSpec.progressPercent(150, 100))
    }

    @Test
    fun `总长未知进度恒0`() {
        assertEquals(0, UploadWorkSpec.progressPercent(50, -1))
        assertEquals(0, UploadWorkSpec.progressPercent(50, 0))
    }
}
