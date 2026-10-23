package media.qimeng.app.core.data.upload

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import media.qimeng.app.core.model.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadQueuePresenterTest {
    private fun info(id: String, state: WorkInfo.State, output: Data = Data.EMPTY) = WorkInfo(
        UUID.randomUUID(), state,
        setOf(UploadWorkSpec.TAG_ITEM_PREFIX + id, UploadWorkSpec.displayNameTag("原件 中文.png")),
        output,
    )

    @Test fun `new presenter restores queued filename without process memory`() {
        val row = UploadQueuePresenter(UploadCancelRegistry(MemoryUploadPreferences())).entries(listOf(info("old", WorkInfo.State.ENQUEUED))).single()
        assertEquals("原件 中文.png", row.displayName)
        assertEquals(UploadStatus.QUEUED, row.status)
    }

    @Test fun `successful engine result preserves business rejection cancellation and attach failure`() {
        val presenter = UploadQueuePresenter(UploadCancelRegistry(MemoryUploadPreferences()))
        for ((flag, status) in listOf(
            UploadWorkSpec.KEY_TARGET_REJECTED to UploadStatus.FAILED,
            UploadWorkSpec.KEY_CANCELLED to UploadStatus.CANCELLED,
            UploadWorkSpec.KEY_ATTACH_FAILED to UploadStatus.ATTACH_FAILED,
        )) {
            val row = presenter.entries(listOf(info("task", WorkInfo.State.SUCCEEDED, workDataOf(flag to true)))).single()
            assertEquals(status, row.status)
        }
    }

    @Test fun `cancel pending item does not change completed neighbour or queue order`() = kotlinx.coroutines.runBlocking {
        val cancels = UploadCancelRegistry(MemoryUploadPreferences())
        val presenter = UploadQueuePresenter(cancels)
        presenter.remember("first", "first.png"); presenter.remember("second", "second.png")
        cancels.cancel("second")
        val rows = presenter.entries(listOf(info("second", WorkInfo.State.ENQUEUED), info("first", WorkInfo.State.SUCCEEDED)))
        assertEquals(listOf("first", "second"), rows.map { it.localId })
        assertEquals(listOf(UploadStatus.SUCCEEDED, UploadStatus.CANCELLED), rows.map { it.status })
    }
}
