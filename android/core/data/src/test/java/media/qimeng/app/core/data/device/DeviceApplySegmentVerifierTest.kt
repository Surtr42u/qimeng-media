package media.qimeng.app.core.data.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceApplySegmentVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `sha256 与标准向量一致且为小写十六进制`() {
        assertEquals(abcSha, DeviceApplySegmentVerifier.sha256Hex("abc".toByteArray()))
        assertTrue(DeviceApplyPolicy.isValidSha256(DeviceApplySegmentVerifier.sha256Hex(byteArrayOf())))
    }

    @Test
    fun `大小与摘要都一致才通过`() {
        assertTrue(DeviceApplySegmentVerifier.verify(3, abcSha, 3, abcSha).ok)
        assertFalse(DeviceApplySegmentVerifier.verify(4, abcSha, 3, abcSha).ok)
        assertFalse(DeviceApplySegmentVerifier.verify(3, abcSha, 3, abcSha.uppercase()).ok)
        assertFalse(DeviceApplySegmentVerifier.verify(0, abcSha, 0, abcSha).ok)
        assertFalse(DeviceApplySegmentVerifier.verify(3, "bad", 3, abcSha).ok)
    }

    @Test
    fun `校验失败原因包含字节或摘要不一致`() {
        val size = DeviceApplySegmentVerifier.verify(4, abcSha, 3, abcSha)
        assertTrue(size.message!!.contains("字节数"))
        val hash = DeviceApplySegmentVerifier.verify(3, abcSha, 3, "b".repeat(64))
        assertTrue(hash.message!!.contains("SHA-256"))
    }

    @Test
    fun `文件摘要按实际字节计算并可用于段校验`() {
        val file = temp.newFile("segment.bin").apply { writeBytes("abc".toByteArray()) }
        val digest = DeviceApplySegmentVerifier.fileDigest(file)
        assertEquals(3L, digest.bytes)
        assertEquals(abcSha, digest.sha256)
        val segment = DeviceApplySegment(DeviceSegmentKind.MASTER_TXT, 3, abcSha)
        assertTrue(DeviceApplySegmentVerifier.verifyFile(segment, file).ok)
        assertFalse(DeviceApplySegmentVerifier.verifyFile(segment.copy(bytes = 4), file).ok)
    }

    @Test
    fun `不存在的文件摘要会失败而非静默通过`() {
        val missing = File(temp.root, "missing.bin")
        val failure = runCatching { DeviceApplySegmentVerifier.fileDigest(missing) }
        assertTrue(failure.isFailure)
        assertNotNull(failure.exceptionOrNull())
    }
}
