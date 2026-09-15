package media.qimeng.app.core.data.embedded

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import media.qimeng.app.core.network.ServerAddress

/**
 * 内嵌服务端装配纯逻辑锁定（任务U11 批次D）：环境变量构造与路径拼接是
 * Service 子进程能否起来的唯一决定面，拼错=本机模式全链不通。
 */
class EmbeddedServerConfigTest {

    private val filesDir = "/data/user/0/media.qimeng.app/files"
    private val nativeDir = "/data/app/~~xyz==/media.qimeng.app-abc==/lib/arm64"

    @Test
    fun `监听地址回环且端口与 ServerAddress 单值互指`() {
        assertEquals("127.0.0.1:${ServerAddress.LOCAL_MODE_PORT}", EmbeddedServerConfig.LISTEN_ADDRESS)
        // 互指红线：预设地址的端口必须就是监听端口（漂移=App 连不上自家服务端）
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, "http://${EmbeddedServerConfig.LISTEN_ADDRESS}")
    }

    @Test
    fun `环境变量五键齐备且路径直指 nativeLibraryDir 成品`() {
        val env = EmbeddedServerConfig.environment(
            dataDir = "$filesDir/server",
            nativeLibraryDir = nativeDir,
        )
        assertEquals("127.0.0.1:18430", env["QIMENG_LISTEN"])
        assertEquals("$filesDir/server", env["QIMENG_DATA_DIR"])
        // 期望用 File 构造（与实现同一拼接语义）：Windows JVM 的 absolutePath 带盘符，
        // 字面硬编码期望会平台漂移；键名/基目录/成品文件名的锁定不受影响
        assertEquals(File(nativeDir, "libffmpeg_cli.so").absolutePath, env["QIMENG_THUMBNAIL_FFMPEG_PATH"])
        assertEquals(File(nativeDir, "libffprobe_cli.so").absolutePath, env["QIMENG_THUMBNAIL_FFPROBE_PATH"])
        assertEquals("1", env["QIMENG_AUTH_DEV_MODE"])
    }

    @Test
    fun `数据目录挂 filesDir 下且服务端二进制落在 nativeLibraryDir`() {
        assertEquals(
            File("$filesDir/server"),
            EmbeddedServerConfig.dataDir(filesDir),
        )
        assertEquals(
            File("$nativeDir/libqimeng.so"),
            EmbeddedServerConfig.serverBinary(nativeDir),
        )
        assertEquals(
            File("$nativeDir/libffmpeg_cli.so"),
            EmbeddedServerConfig.ffmpegBinary(nativeDir),
        )
    }

    @Test
    fun `本机模式判定认回环双写法但端口须精确`() {
        assertTrue(ServerAddress.isLocalModePreset("http://127.0.0.1:18430"))
        // localhost 与 127.0.0.1 同指一台内嵌服务（reviewer P3-3），误判非会误停服
        assertTrue(ServerAddress.isLocalModePreset("http://localhost:18430"))
        // 自定义端口=自带 Termux 场景，不触发内嵌拉起
        assertFalse(ServerAddress.isLocalModePreset("http://127.0.0.1:18500"))
        assertFalse(ServerAddress.isLocalModePreset("http://localhost:18500"))
        assertFalse(ServerAddress.isLocalModePreset("http://192.168.1.8:8420"))
    }
}
