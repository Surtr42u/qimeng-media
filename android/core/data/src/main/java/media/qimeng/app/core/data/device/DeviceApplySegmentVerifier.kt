package media.qimeng.app.core.data.device

import java.io.File
import java.security.MessageDigest

/**
 * 段字节校验（纯逻辑 + 文件摘要，JVM 单测锁定）。
 *
 * 服务端登记时已复算 SHA-256；下载后仍必须复核 size 与 sha256 再应用——网络截断、
 * 中间缓存替换或旧服务端错误响应都不能把坏段送进导入链路。摘要不符不做「尽量应用」。
 */
object DeviceApplySegmentVerifier {

    /** 单段纯校验：声明字节数、实际字节数、声明摘要、本地复算摘要。 */
    fun verify(
        expectedBytes: Long,
        expectedSha256: String,
        actualBytes: Long,
        actualSha256: String,
    ): SegmentVerification {
        if (expectedBytes <= 0) {
            return SegmentVerification(false, "段声明字节数非法")
        }
        if (!DeviceApplyPolicy.isValidSha256(expectedSha256)) {
            return SegmentVerification(false, "段声明摘要格式非法")
        }
        if (actualBytes != expectedBytes) {
            return SegmentVerification(
                false,
                "段字节数不一致：声明 $expectedBytes，实际 $actualBytes",
            )
        }
        if (!actualSha256.equals(expectedSha256, ignoreCase = false)) {
            return SegmentVerification(false, "段 SHA-256 与 manifest 不一致")
        }
        return SegmentVerification(true, null)
    }

    /** 下载文件的实际字节数 + 流式 SHA-256；只用于打开后复核，不把整段读进内存。 */
    fun fileDigest(file: File): FileDigest {
        require(file.isFile) { "段临时文件不存在" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return FileDigest(bytes = file.length(), sha256 = digest.digest().toHex())
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun verifyFile(segment: DeviceApplySegment, file: File): SegmentVerification {
        val digest = fileDigest(file)
        return verify(segment.bytes, segment.sha256, digest.bytes, digest.sha256)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

data class FileDigest(val bytes: Long, val sha256: String)

data class SegmentVerification(val ok: Boolean, val message: String?)
