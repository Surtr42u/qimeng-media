package media.qimeng.app.core.data.embedded

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 内嵌服务端（任务T T6 / 任务U11 批次D，ADR-0015 形态 B）的装配纯逻辑：
 * 服务端二进制定位、子进程环境变量构造、监听地址。
 *
 * 为什么 exec nativeLibraryDir 里的 `.so`（W^X 红线）：targetSdk≥29 SELinux 禁止
 * exec 应用数据目录（filesDir）——唯一合法的可执行投放位是 APK 原生库目录；
 * Go 服务端与 ffmpeg/ffprobe 成品按 AGP jniLibs 约定改名 `lib*.so` 随包分发，
 * 安装后由系统解出到 nativeLibraryDir（useLegacyPackaging=true）。
 *
 * 零 Android import（JVM 单测锁定构造）；Service 层只做生命周期编排。
 */
object EmbeddedServerConfig {

    /** 服务端二进制在 jniLibs 里的名字（make server-android-arm64 产物改名投放） */
    const val SERVER_BINARY = "libqimeng.so"

    /** ffmpeg/ffprobe 成品在 jniLibs 里的名字（hzw1199 LGPL-2.1，锁 commit hash，
     *  见 deploy/embedded/README.md 的供应与哈希清单；改名 .so 是 jniLibs 打包约定，
     *  文件本体仍是标准 arm64 ELF——服务端经显式路径 exec，不依赖扩展名语义） */
    const val FFMPEG_BINARY = "libffmpeg_cli.so"
    const val FFPROBE_BINARY = "libffprobe_cli.so"

    /**
     * 监听地址：只绑回环（红线，ADR-0015——媒体服务不出局域网、不出本机）。
     * 端口从 [media.qimeng.app.core.network.ServerAddress.LOCAL_MODE_PORT] 单值派生
     * （端口单值互指清单见其注释；预览变体随该源错开 18431——ADR-0031「预览变体与端口纪律」）。
     */
    val LISTEN_ADDRESS = "127.0.0.1:${media.qimeng.app.core.network.ServerAddress.LOCAL_MODE_PORT}"

    /** 服务端数据目录名（挂 App filesDir 下；与 Termux 形态 A 的 ~/.qimeng 互不相干，
     *  内嵌形态是独立空库，首次使用需重新注册库——ADR-0015 语义） */
    const val DATA_DIR_NAME = "server"

    /** 服务端 stdout/stderr 落盘文件名（Go 结构化日志，排障用；每次启动截断） */
    const val LOG_FILE_NAME = "server.log"

    /** 子进程 pid 落盘文件名（残留子进程回收用，见 [parseRecordedPid]；每次启动覆写） */
    const val PID_FILE_NAME = "server.pid"

    /**
     * dev 共享密钥原始字节数：32 字节 = 256 bit 熵——一次性随机数抗暴力猜解的
     * 通用下限（NIST SP 800-133 对称密钥量级），再长只增加环境变量长度不增益安全。
     * 每次拉起子进程新生成（SecureRandom），生命周期=子进程，不落盘。
     */
    const val DEV_SHARED_SECRET_BYTES = 32

    /**
     * 子进程环境变量键（与 Go 服务端 config.go 的 QIMENG_AUTH_DEV_SHARED_SECRET
     * 同名同源——协议侧互指：App 生成注入 / 服务端读取校验 devLogin 请求头
     * X-Qimeng-Dev-Secret，堵「同机其他 App 打内嵌回环预设端口免密拿管理员 token」）。
     */
    const val DEV_SHARED_SECRET_ENV = "QIMENG_AUTH_DEV_SHARED_SECRET"

    private val secureRandom = SecureRandom()

    /**
     * 生成一次性的 dev 共享密钥（2026-09-30 批A，内嵌形态防同机越权）：
     * SecureRandom 取 [DEV_SHARED_SECRET_BYTES] 字节，Base64 URL-safe 无换行无填充
     * 编码——URL-safe 是因为值经进程环境变量传递，避免任何转义歧义；无填充让长度
     * 定长（ceil(256bit/6) = 43 字符）便于校验。密钥只经环境变量进子进程 + 内存槽
     * 供 App 侧 devLogin 带出，绝不落盘、绝不入日志。
     */
    fun generateDevSharedSecret(): String {
        val bytes = ByteArray(DEV_SHARED_SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 解析 pid 文件里的子进程 pid（2026-09-25 冻结事故修复的配套件）。为什么回收要靠
     * 落盘 pid：Service 销毁重建后手里的子进程句柄丢失，而孤儿子进程仍占着内嵌回环
     * 端口——新子进程 bind 失败秒退、本机模式反复「已退出」。pid 文件是跨 Service 生命
     * 周期的唯一线索；误杀防线在 Service 侧（/proc cmdline 仍是本服务端二进制才动手）。
     *
     * 纯逻辑（JVM 单测锁定）：trim 后 toIntOrNull，非正数视为脏数据返回 null。
     */
    fun parseRecordedPid(pidFileText: String?): Int? =
        pidFileText?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * 子进程环境变量（QIMENG_* 语义与 deploy/termux 脚本同源）：
     * - LISTEN 回环固定端口（端口红线）；DATA_DIR 独立数据根；
     * - THUMBNAIL 两路径直喂 nativeLibraryDir 成品——服务端路径配置化
     *   （config.go QIMENG_THUMBNAIL_FFMPEG_PATH）的既有通道，不拼 PATH；
     * - AUTH_DEV_MODE 与 Termux 形态 A 同款（qimeng-start.sh 口径）：回环监听 +
     *   用户本地免密约定（AI_README 用户约定①），风险面=本机 localhost only；
     * - AUTH_DEV_SHARED_SECRET（2026-09-30 批A）：同机越权防护的共享密钥，devLogin
     *   必须带头 X-Qimeng-Dev-Secret 才放行——键名与 server config.go 同名同源。
     *   密钥由调用方每次拉起前经 [generateDevSharedSecret] 新生成（生命周期=子进程），
     *   参数收口成必传而非内部生成：调用方要同时把同值写入内存槽供 App 侧 devLogin
     *   带出，集中在一处显式传递避免「环境变量与内存槽两个值」的分叉。
     */
    fun environment(
        dataDir: String,
        nativeLibraryDir: String,
        devSharedSecret: String,
    ): Map<String, String> = mapOf(
        "QIMENG_LISTEN" to LISTEN_ADDRESS,
        "QIMENG_DATA_DIR" to dataDir,
        "QIMENG_THUMBNAIL_FFMPEG_PATH" to File(nativeLibraryDir, FFMPEG_BINARY).absolutePath,
        "QIMENG_THUMBNAIL_FFPROBE_PATH" to File(nativeLibraryDir, FFPROBE_BINARY).absolutePath,
        "QIMENG_AUTH_DEV_MODE" to "1",
        DEV_SHARED_SECRET_ENV to devSharedSecret,
    )

    /** 服务端数据目录（filesDir/server） */
    fun dataDir(filesDir: String): File = File(File(filesDir), DATA_DIR_NAME)

    /** 服务端二进制 File（存在性由 Service 层启动前校验） */
    fun serverBinary(nativeLibraryDir: String): File = File(nativeLibraryDir, SERVER_BINARY)

    /** ffmpeg 成品缺失但服务端在=降级形态（缩略图 404 占位），不算启动失败——
     *  与服务端「无 ffmpeg 是既定降级形态」语义同源（thumbnail.CheckBinaries 注释） */
    fun ffmpegBinary(nativeLibraryDir: String): File = File(nativeLibraryDir, FFMPEG_BINARY)
}
