package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanDiscoveryProtocolTest {
    private val nonce = "a".repeat(32)

    @Test
    fun `wire format matches Go server and pads request against amplification`() {
        val request = LanDiscoveryProtocol.request(nonce)
        assertEquals(96, request.size)
        assertEquals("QIMENG_DISCOVER_V1:$nonce", request.toString(Charsets.US_ASCII).trimEnd())
        assertEquals(18080, parse("QIMENG_SERVER_V1:$nonce:18080"))
    }

    @Test
    fun `rejects stale nonce invalid ports and oversized responses`() {
        listOf("QIMENG_SERVER_V1:${"b".repeat(32)}:8420", "QIMENG_SERVER_V2:$nonce:8420",
            "QIMENG_SERVER_V1:$nonce:0", "QIMENG_SERVER_V1:$nonce:65536",
            "QIMENG_SERVER_V1:$nonce:+8420", "QIMENG_SERVER_V1:$nonce:8420\n",
            "QIMENG_SERVER_V1:$nonce:${"8".repeat(96)}").forEach { assertNull(parse(it)) }
    }

    private fun parse(text: String) = LanDiscoveryProtocol.parsePort(text.toByteArray(Charsets.US_ASCII), nonce)
}
