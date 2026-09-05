package media.qimeng.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 地址规范化单测：登录页输入宽容度（裸地址补协议/去空白/去尾斜杠）与非法输入判定。 */
class ServerAddressTest {

    @Test
    fun `裸地址补默认 http 协议`() {
        assertEquals("http://192.0.2.10:8420", ServerAddress.normalize("192.0.2.10:8420"))
    }

    @Test
    fun `localhost 与 127_0_0_1 写法均合法（M6 单机形态预留）`() {
        assertEquals("http://localhost:8420", ServerAddress.normalize("localhost:8420"))
        assertEquals("http://127.0.0.1:8420", ServerAddress.normalize("http://127.0.0.1:8420"))
    }

    @Test
    fun `模拟器回路地址原样通过`() {
        assertEquals(ServerAddress.EMULATOR_LOOPBACK, ServerAddress.normalize(ServerAddress.EMULATOR_LOOPBACK))
    }

    @Test
    fun `首尾空白与尾部斜杠被清理`() {
        assertEquals("http://10.0.2.2:8420", ServerAddress.normalize("  http://10.0.2.2:8420/ "))
    }

    @Test
    fun `显式 https 保留不被改写`() {
        assertEquals("https://nas.example.com:8420", ServerAddress.normalize("https://nas.example.com:8420"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("not a url"))
        // 非 http(s) 协议对媒体服务端无意义
        assertNull(ServerAddress.normalize("ftp://10.0.2.2:8420"))
        // base 带路径会拼出错误 API 地址，直接判非法
        assertNull(ServerAddress.normalize("http://10.0.2.2:8420/qimeng"))
    }
}
