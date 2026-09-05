package media.qimeng.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务端地址规范化（唯一入口：登录页输入 → 本函数 → ServerConfigDataSource 持久化）。
 *
 * 为什么允许缺省协议：用户无编程基础，手输 `192.168.1.10:8420` 这类裸地址是高频输入，
 * 统一按局域网场景补 `http://` 前缀（显式带 `https://` 的原样保留）。
 *
 * 为什么必须支持 localhost / 127.0.0.1 写法：ADR-0015 单机形态（M6）服务端内嵌手机本机，
 * 届时地址指向 localhost 即为单机模式，UI 层零改动。
 */
object ServerAddress {

    /** 缺省协议（裸地址自动补前缀；内网场景 http 为主，显式 https 不受影响）。 */
    private const val DEFAULT_SCHEME = "http://"

    /** Android 模拟器访问宿主机回环地址的别名——10.0.2.2 即开发机的 127.0.0.1（官方模拟器网络约定）。 */
    const val EMULATOR_LOOPBACK = "http://10.0.2.2:8420"

    /** 服务端默认端口（与仓库根「启动服务端.bat」及部署文档一致）。 */
    const val DEFAULT_PORT = 8420

    /**
     * 规范化服务端地址：去首尾空白 → 补缺省协议 → HttpUrl 校验 → 去尾部 `/`。
     *
     * @return 规范化后的 base URL（如 `http://10.0.2.2:8420`）；非法输入返回 null（由调用方给中文错误文案）。
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else DEFAULT_SCHEME + trimmed
        val url = withScheme.toHttpUrlOrNull() ?: return null
        // 只接受 http(s)——ftp/file 等协议对媒体服务端无意义，直接判非法
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isEmpty()) return null
        // 允许带路径前缀的地址吗？协议面路径由 SDK 拼 /api/v1/*，base 带路径会得到错误 URL——
        // 服务端从未部署在子路径下，这里直接判非法，避免静默 404 难排查
        if (url.encodedPath != "/") return null
        return url.toString().removeSuffix("/")
    }
}
