package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情海报「md 先行」分层裁决单测（协议批 2026-09-18，裁决单源 [videoPosterLayering]）：
 * 锁定 md/lg 分层换图口径——md 先挂、lg 就绪升级、无 md 退化直接 lg、md==lg 防重复请求。
 */
class VideoPosterLayeringTest {

    @Test
    fun `有md - 底层恒挂md 且md成功前不放行lg`() {
        val pending = videoPosterLayering(thumbUrlMd = "md", thumbUrl = "lg", mdSucceeded = false)
        assertEquals("md", pending.baseModel)
        assertFalse(pending.upgradeToLg) // md 未成功不挂覆盖层：lg 恰好只在升级瞬间发一次

        val upgraded = videoPosterLayering(thumbUrlMd = "md", thumbUrl = "lg", mdSucceeded = true)
        assertEquals("md", upgraded.baseModel) // 底层不动（换 model 重开会回占位=闪灰块的根因）
        assertTrue(upgraded.upgradeToLg)
    }

    @Test
    fun `无md - 退化直接lg且永不升级`() {
        // 旧服务端/异常：现状行为（直接 lg）；mdSucceeded 守卫防「底层 lg 成功误记账后
        // 重复挂 lg 覆盖层」——baseModel 与 upgradeToLg 必须同时只认 md 在场
        val layering = videoPosterLayering(thumbUrlMd = null, thumbUrl = "lg", mdSucceeded = true)
        assertEquals("lg", layering.baseModel)
        assertFalse(layering.upgradeToLg)
    }

    @Test
    fun `md与lg同URL - 不升级防重复请求`() {
        val layering = videoPosterLayering(thumbUrlMd = "same", thumbUrl = "same", mdSucceeded = true)
        assertEquals("same", layering.baseModel)
        assertFalse(layering.upgradeToLg)
    }

    @Test
    fun `lg缺失 - 只出md不升级`() {
        val layering = videoPosterLayering(thumbUrlMd = "md", thumbUrl = null, mdSucceeded = true)
        assertEquals("md", layering.baseModel)
        assertFalse(layering.upgradeToLg)
    }

    @Test
    fun `两者皆空 - baseModel空走占位`() {
        val layering = videoPosterLayering(thumbUrlMd = null, thumbUrl = null, mdSucceeded = false)
        assertNull(layering.baseModel)
        assertFalse(layering.upgradeToLg)
    }
}
