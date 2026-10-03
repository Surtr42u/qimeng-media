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

    /**
     * URL 协议前缀单源（U11 小债清偿批公开）：地址规范化、SDK 映射 absolutize、
     * 缩略图缓存键协议判定三方共用——协议前缀第 2 处手抄即漂移风险，收拢于此。
     */
    const val SCHEME_HTTP = "http://"
    const val SCHEME_HTTPS = "https://"

    /** 缺省协议（裸地址自动补前缀；内网场景 http 为主，显式 https 不受影响）。 */
    private const val DEFAULT_SCHEME = SCHEME_HTTP

    /** Android 模拟器访问宿主机回环地址的别名——10.0.2.2 即开发机的 127.0.0.1（官方模拟器网络约定）。 */
    const val EMULATOR_LOOPBACK = "http://10.0.2.2:8420"

    /** 服务端默认端口（与仓库根「启动服务端.bat」及部署文档一致）。 */
    const val DEFAULT_PORT = 8420

    /**
     * 本机模式端口。端口单源（App 端唯一字面值点）：内嵌形态 Service 的监听地址
     * （QIMENG_LISTEN）、健康探测、本机预设地址 [LOCAL_MODE_PRESET] 与
     * [isLocalModePreset] 判定全部从本值派生——端口漂移=App 连不上自家服务端，
     * 单值单源是唯一防线。部署侧互指清单见 [LOCAL_MODE_PRESET] 注释。
     *
     * 为什么改为构建期注入（2026-10-03 悬浮玻璃坞预览变体，端口纪律同 ADR-0031
     * 预览先例）：云端预览包与正式包并存装机，两内嵌服务端同抢回环端口时后装者
     * 起不来——预览变体错开 18432。值在 :core:network/build.gradle.kts 经
     * BuildConfig.QM_LOCAL_MODE_PORT 注入（正式构建=18430，与历史一致；跑单测
     * 不带预览属性，单测锁定正式口径）。因值随构建浮动，本属性不再是 const
     * （消费方均为值语义，无需编译期常量）。
     */
    val LOCAL_MODE_PORT: Int = BuildConfig.QM_LOCAL_MODE_PORT

    /**
     * 本机模式预设地址（任务T T3 兑现 ADR-0015 单点预留；端口跟随 [LOCAL_MODE_PORT]
     * 构建期注入，正式=18430 = T2 批 deploy/termux 定稿口径，预览变体=18432）。
     * 服务端内嵌手机本机（Termux 形态 A / 内嵌形态 B）监听 127.0.0.1 回环、不暴露局域网（ADR-0015），
     * 登录页/设置页快捷填入入口共用本常量，UI 零结构改动。
     *
     * 单值互指（部署侧改动须同步此处，反之亦然）：deploy/termux/qimeng-start.sh、
     * qimeng-watchdog.sh、qimeng-stop.sh 三脚本的 PORT 常量与 deploy/termux/README.md
     * 「端口 18430 定稿」行 + 内嵌形态 EmbeddedServerConfig 的 QIMENG_LISTEN（:core:data
     * embedded 包，U11 批次D）——多方内嵌同一份常量注释互指，本常量是 App 端唯一对应点。
     */
    val LOCAL_MODE_PRESET = "http://127.0.0.1:$LOCAL_MODE_PORT"

    /**
     * 是否为内嵌形态预设地址（规范化后的 URL 判定：回环主机名 + 预设端口）。
     * 为什么接受 localhost 与 127.0.0.1 两种写法（reviewer P3-3）：两者解析到同一
     * 本机回环、指向同一台内嵌服务——localhost 写法若判非，壳层 collector 会把用户
     * 正要用的内嵌服务误停。端口仍须精确等于 [LOCAL_MODE_PORT]（内嵌 Service 只监听
     * 固定端口，端口单值互指红线）——自定义端口=「自带 Termux 服务端」场景（T2 形态
     * A），不触发内嵌拉起。消费方：设置页本机模式切换与 MainActivity 冷启动自拉起
     * （U11 批次D）。
     */
    fun isLocalModePreset(normalizedUrl: String): Boolean {
        val url = normalizedUrl.toHttpUrlOrNull() ?: return false
        val loopback = url.host == "127.0.0.1" || url.host == "localhost"
        return loopback && url.port == LOCAL_MODE_PORT
    }

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
