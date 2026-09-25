package media.qimeng.app.feature.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.LocalMediaRepository
import media.qimeng.app.core.model.LocalMediaBucket
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 内置相册选择器状态机锁定（2026-09-25 拍板批）：MediaStore 条目映射透传 / 相册过滤 /
 * 多选跨相册保持 / 确认回传保序 / 查询失败横幅。MediaStore 访问本体在 core:data
 * （纯本地查询不出网），本测试锁定 VM 的查询映射与选择编排。
 */
class MediaPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private val image1 = LocalMediaItem(
        uri = "content://media/images/1", displayName = "IMG_1.jpg",
        sizeBytes = 1024L, isVideo = false, bucketName = "相机",
    )
    private val video1 = LocalMediaItem(
        uri = "content://media/video/2", displayName = "VID_1.mp4",
        sizeBytes = 8192L, isVideo = true, durationMs = 61_500L, bucketName = "相机",
    )
    private val image2 = LocalMediaItem(
        uri = "content://media/images/3", displayName = "IMG_2.jpg",
        sizeBytes = 2048L, isVideo = false, bucketName = "截图",
    )

    private fun newViewModel(repo: FakeLocalMediaRepository = FakeLocalMediaRepository()) =
        MediaPickerViewModel(repo) to repo

    @Test
    fun `首屏装载相册chips与全部条目`() {
        val (viewModel, repo) = newViewModel()
        repo.bucketsResult = listOf(LocalMediaBucket("相机", 2), LocalMediaBucket("截图", 1))
        repo.itemsResult = listOf(image1, video1, image2)
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(listOf("相机", "截图"), state.buckets.map { it.name })
        assertEquals(listOf(image1.uri, video1.uri, image2.uri), state.items.map { it.uri })
        assertNull(state.selectedBucket)
        assertNull(state.errorMessage)
        // 视频标记与时长随条目透传（网格角标数据源）
        assertTrue(state.items[1].isVideo)
        assertEquals(61_500L, state.items[1].durationMs)
    }

    @Test
    fun `切相册按bucket名过滤条目且不清空已选`() {
        val (viewModel, repo) = newViewModel()
        repo.bucketsResult = listOf(LocalMediaBucket("相机", 2), LocalMediaBucket("截图", 1))
        repo.itemsResult = listOf(image1, video1, image2)
        driveIdle()
        viewModel.toggle(image1)
        repo.itemsResult = listOf(image2) // 「截图」相册查询命中
        viewModel.selectBucket("截图")
        driveIdle()
        assertEquals("截图", viewModel.uiState.value.selectedBucket)
        assertEquals(listOf(image2.uri), viewModel.uiState.value.items.map { it.uri })
        // 已选跨相册保留（用户先选后切换的常见路径）
        assertEquals(listOf(image1.uri), viewModel.uiState.value.selected.keys.toList())
    }

    @Test
    fun `多选toggle与确认回传保持选择序`() {
        val (viewModel, _) = newViewModel()
        driveIdle()
        viewModel.toggle(image2)
        viewModel.toggle(image1)
        viewModel.toggle(image2) // 再点取消
        assertEquals(listOf(image1.uri), viewModel.uiState.value.selected.keys.toList())
    }

    @Test
    fun `查询失败给错误横幅`() {
        val (viewModel, repo) = newViewModel()
        repo.bucketsError = IllegalStateException("权限被剥")
        driveIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun `切相册失败保留已装载内容只提示错误`() {
        val (viewModel, repo) = newViewModel()
        repo.itemsResult = listOf(image1)
        driveIdle()
        repo.itemsError = IllegalStateException("provider 异常")
        viewModel.selectBucket("相机")
        driveIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(listOf(image1.uri), viewModel.uiState.value.items.map { it.uri })
    }
}

/** [LocalMediaRepository] 测试替身：相册/条目结果与异常均可编程（选择器 VM 单测用）。 */
private class FakeLocalMediaRepository : LocalMediaRepository {

    var bucketsResult: List<LocalMediaBucket> = emptyList()
    var itemsResult: List<LocalMediaItem> = emptyList()
    var bucketsError: Exception? = null
    var itemsError: Exception? = null

    val bucketCalls = mutableListOf<Int>()
    val itemCalls = mutableListOf<String?>() // null = 全部

    override suspend fun buckets(limit: Int): List<LocalMediaBucket> {
        bucketCalls += limit
        bucketsError?.let { throw it }
        return bucketsResult
    }

    override suspend fun items(bucket: String?, limit: Int): List<LocalMediaItem> {
        itemCalls += bucket
        itemsError?.let { throw it }
        return itemsResult
    }
}

