package media.qimeng.app.core.data.embedded

import java.io.File

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
     * 端口单值互指清单见 [media.qimeng.app.core.network.ServerAddress.LOCAL_MODE_PRESET]。
     */
    const val LISTEN_ADDRESS = "127.0.0.1:${media.qimeng.app.core.network.ServerAddress.LOCAL_MODE_PORT}"

    /** 服务端数据目录名（挂 App filesDir 下；与 Termux 形态 A 的 ~/.qimeng 互不相干，
     *  内嵌形态是独立空库，首次使用需重新注册库——ADR-0015 语义） */
    const val DATA_DIR_NAME = "server"

    /** 服务端 stdout/stderr 落盘文件名（Go 结构化日志，排障用；每次启动截断） */
    const val LOG_FILE_NAME = "server.log"

    /**
     * 子进程环境变量（QIMENG_* 语义与 deploy/termux 脚本同源）：
     * - LISTEN 回环固定端口（端口红线）；DATA_DIR 独立数据根；
     * - THUMBNAIL 两路径直喂 nativeLibraryDir 成品——服务端路径配置化
     *   （config.go QIMENG_THUMBNAIL_FFMPEG_PATH）的既有通道，不拼 PATH；
     * - AUTH_DEV_MODE 与 Termux 形态 A 同款（qimeng-start.sh 口径）：回环监听 +
     *   用户本地免密约定（AI_README 用户约定①），风险面=本机 localhost only。
     */
    fun environment(dataDir: String, nativeLibraryDir: String): Map<String, String> = mapOf(
        "QIMENG_LISTEN" to LISTEN_ADDRESS,
        "QIMENG_DATA_DIR" to dataDir,
        "QIMENG_THUMBNAIL_FFMPEG_PATH" to File(nativeLibraryDir, FFMPEG_BINARY).absolutePath,
        "QIMENG_THUMBNAIL_FFPROBE_PATH" to File(nativeLibraryDir, FFPROBE_BINARY).absolutePath,
        "QIMENG_AUTH_DEV_MODE" to "1",
    )

    /** 服务端数据目录（filesDir/server） */
    fun dataDir(filesDir: String): File = File(File(filesDir), DATA_DIR_NAME)

    /** 服务端二进制 File（存在性由 Service 层启动前校验） */
    fun serverBinary(nativeLibraryDir: String): File = File(nativeLibraryDir, SERVER_BINARY)

    /** ffmpeg 成品缺失但服务端在=降级形态（缩略图 404 占位），不算启动失败——
     *  与服务端「无 ffmpeg 是既定降级形态」语义同源（thumbnail.CheckBinaries 注释） */
    fun ffmpegBinary(nativeLibraryDir: String): File = File(nativeLibraryDir, FFMPEG_BINARY)
}
