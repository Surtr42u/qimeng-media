package media.qimeng.app.core.data.upload

/**
 * 直传/分片双通道分流规则（ADR-0028 Android 接入批）。
 * 纯函数无 IO：worker 入口读一次即定通道，单测锁定两分支。
 */
internal object UploadRouting {

    /**
     * 分片会话通道的文件大小阈值（字节）：≥ 此值走 POST /uploads 会话流，< 此值走
     * 既有整文件直传。16MB 取舍：LAN 小文件单发直传更快（一次往返即完成，无
     * create/probe/PATCH/complete 四步握手开销）；会话流的价值在弱网大文件按片续传。
     * 协议侧改动须同步此处，反之亦然（openapi.yaml /api/v1/uploads 注释「双端按
     * 阈值分流」口径；服务端单片上限 32MB 见同文件 PATCH 描述）。Web 侧同值
     * 常量在 web/src/lib/upload-chunked.ts，双端同步责任互指。
     */
    const val CHUNKED_THRESHOLD_BYTES: Long = 16L * 1024 * 1024

    /**
     * 单个分片大小（字节）：PATCH /uploads/{id} 每次追加的原始字节数。8MB = 服务端
     * 单片上限 32MB 的 1/4（客户端常规路径永不触发 413），请求数（2GB ≈ 256 片）与
     * 进度粒度（每片一跳）的折中。协议侧改动须同步此处，反之亦然；Web 侧同值
     * 常量在 web/src/lib/upload-chunked.ts（双端同步责任）。
     */
    const val CHUNK_BYTES: Long = 8L * 1024 * 1024

    /**
     * 是否走分片会话通道：纯 size 阈值分流。子目录目标同样走会话流——dir 已入
     * 分片协议（CreateUploadRequest.dir，与直传 dir 参数逐字同语义，complete 按
     * 其落位）。初版「有 dir 一律直传」是协议无 dir 期的临时限制（静默放错目录
     * 比不续传更糟），随协议补齐同日撤销，分流回归 size 单变量。
     */
    fun useChunkedSession(sizeBytes: Long): Boolean = sizeBytes >= CHUNKED_THRESHOLD_BYTES
}
