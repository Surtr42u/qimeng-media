package media.qimeng.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理表单可提交判定单测（任务G G1b；Web MoveDialog submittable 同口径锁定）：
 * 名字 trim 后非空 且（改名 或 移动）——无任何变化禁用提交（按钮给真实反馈）。
 */
class MoveFormSubmittableTest {

    @Test
    fun `无任何变化 - 不可提交`() {
        assertFalse(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "a", name = "x.jpg"))
    }

    @Test
    fun `名字空白 - 不可提交（换目录也不行，空名会触发服务端改名非法）`() {
        assertFalse(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "b", name = "  "))
    }

    @Test
    fun `仅改名 - 可提交`() {
        assertTrue(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "a", name = "y.jpg"))
    }

    @Test
    fun `仅移动 - 可提交（含移到库根空串）`() {
        assertTrue(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "b", name = "x.jpg"))
        assertTrue(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "", name = "x.jpg"))
    }

    @Test
    fun `改名加移动 - 可提交`() {
        assertTrue(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "b", name = "y.jpg"))
    }

    @Test
    fun `名字带首尾空格 - trim 后与原名相同视为未改名`() {
        assertFalse(isMoveSubmittable(currentDir = "a", currentName = "x.jpg", targetDir = "a", name = " x.jpg "))
    }
}
