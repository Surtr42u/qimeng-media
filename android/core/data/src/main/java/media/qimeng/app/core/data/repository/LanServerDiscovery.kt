package media.qimeng.app.core.data.repository

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 只产生未认证的地址候选；不读取 token、不改当前连接、不发登录请求。 */
fun interface LanServerDiscovery {
    suspend fun discover(rememberedUrls: List<String>): List<String>
}

/** 标准 UDP 广播，无新依赖。每轮重新枚举网卡，回包来源就是当前可达地址。 */
class UdpLanServerDiscovery @Inject constructor() : LanServerDiscovery {
    override suspend fun discover(rememberedUrls: List<String>): List<String> = withContext(Dispatchers.IO) {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val request = LanDiscoveryProtocol.request(nonce)
        val targets = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .distinct()
            .take(MAX_RESULTS)
            .ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
        // 只对成功历史中的 IPv4 局域网字面量发无凭据单播，避免 DNS/外网探测。
        val preferred = rememberedUrls.take(MAX_HISTORY).mapNotNull { raw ->
            val uri = URI(raw)
            val host = uri.host ?: return@mapNotNull null
            if (uri.scheme != "http" || !host.matches(Regex("[0-9.]+"))) return@mapNotNull null
            val address = InetAddress.getByName(host)
            address.takeIf { it is Inet4Address && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
        }.distinct()
        val found = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            for (target in preferred) {
                currentCoroutineContext().ensureActive()
                try {
                    socket.send(DatagramPacket(request, request.size, target, LanDiscoveryProtocol.PORT))
                } catch (_: IOException) {
                    // 旧网段无路由时继续下一条历史，再走当前网卡广播。
                    continue
                }
                val until = System.nanoTime() + PREFERRED_WAIT_NS
                while (System.nanoTime() < until) {
                    currentCoroutineContext().ensureActive()
                    val packet = DatagramPacket(bufferForReply(), LanDiscoveryProtocol.PACKET_SIZE + 1)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        // 已失效历史地址的ICMP/路由错误不阻断后续历史与广播。
                        break
                    }
                    if (packet.address != target || packet.port != LanDiscoveryProtocol.PORT) continue
                    val port = LanDiscoveryProtocol.parsePort(packet.data.copyOf(packet.length), nonce) ?: continue
                    return@withContext listOf("http://${target.hostAddress}:$port")
                }
            }
            val started = System.nanoTime()
            var nextSend = started
            val buffer = ByteArray(LanDiscoveryProtocol.PACKET_SIZE + 1)
            while (System.nanoTime() - started < SCAN_DURATION_NS && found.size < MAX_RESULTS) {
                currentCoroutineContext().ensureActive()
                val now = System.nanoTime()
                if (now >= nextSend) {
                    var sent = false
                    var sendError: IOException? = null
                    targets.forEach { target ->
                        try {
                            socket.send(DatagramPacket(request, request.size, target, LanDiscoveryProtocol.PORT))
                            sent = true
                        } catch (error: IOException) {
                            // VPN/虚拟网卡可能不可广播；其他网卡仍发送，全部失败才报告错误。
                            sendError = error
                        }
                    }
                    if (!sent) throw checkNotNull(sendError)
                    nextSend = now + SEND_INTERVAL_NS
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    // 有界等待，同时给取消与下一次广播让出检查点。
                    continue
                }
                val address = packet.address
                if (packet.port != LanDiscoveryProtocol.PORT || address !is Inet4Address ||
                    address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress
                ) continue
                LanDiscoveryProtocol.parsePort(buffer.copyOf(packet.length), nonce)?.let { port ->
                    found += "http://${address.hostAddress}:$port"
                }
            }
        }
        found.sorted()
    }

    private fun bufferForReply() = ByteArray(LanDiscoveryProtocol.PACKET_SIZE + 1)

    private companion object {
        const val MAX_HISTORY = 5
        const val PREFERRED_WAIT_NS = 300_000_000L
        const val MAX_RESULTS = 32
        const val RECEIVE_TIMEOUT_MS = 100
        const val SCAN_DURATION_NS = 3_000_000_000L
        const val SEND_INTERVAL_NS = 1_000_000_000L
    }
}

/** 与 Go landiscovery 逐字同步；这只是不可信地址发现，不是服务器身份证明。 */
internal object LanDiscoveryProtocol {
    const val PORT = 18421
    const val PACKET_SIZE = 96
    private const val REQUEST_PREFIX = "QIMENG_DISCOVER_V1:"
    private const val RESPONSE_PREFIX = "QIMENG_SERVER_V1:"

    fun request(nonce: String): ByteArray =
        (REQUEST_PREFIX + nonce).padEnd(PACKET_SIZE, ' ').toByteArray(Charsets.US_ASCII)

    fun parsePort(packet: ByteArray, nonce: String): Int? {
        if (packet.size > PACKET_SIZE) return null
        val text = packet.toString(Charsets.US_ASCII)
        val prefix = "$RESPONSE_PREFIX$nonce:"
        if (!text.startsWith(prefix)) return null
        val portText = text.removePrefix(prefix)
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
        return portText.toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
