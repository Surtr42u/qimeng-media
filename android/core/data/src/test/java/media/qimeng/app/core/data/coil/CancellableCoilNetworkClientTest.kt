package media.qimeng.app.core.data.coil

import coil3.network.NetworkRequest
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableCoilNetworkClientTest {
    @Test
    fun cancellationAfterHeadersInterruptsBlockedBody() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val release = CountDownLatch(1)
            val peer = AtomicReference<Socket?>()
            val server = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    peer.set(socket)
                    readHeaders(socket)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n".toByteArray())
                        write(ByteArray(16384))
                        flush()
                    }
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            val http = OkHttpClient()
            val activeCall = AtomicReference<Call?>()
            val client = CancellableCoilNetworkClient(Call.Factory { request ->
                http.newCall(request).also(activeCall::set)
            })
            val bodyReading = CompletableDeferred<Unit>()
            try {
                val request = async(Dispatchers.IO) {
                    client.executeRequest(NetworkRequest("http://127.0.0.1:${listener.localPort}/image")) { response ->
                        val sink = object : ForwardingSink(Buffer()) {
                            override fun write(source: Buffer, byteCount: Long) {
                                super.write(source, byteCount)
                                bodyReading.complete(Unit)
                            }
                        }.buffer()
                        response.body!!.writeTo(sink)
                    }
                }
                withTimeout(5000) { bodyReading.await() }
                request.cancel()
                val interrupted = withTimeoutOrNull(2000) { request.join(); true } ?: false
                // 失败时也释放对端，避免测试自身等待一个不能退出的阻塞读。
                release.countDown()
                withTimeout(5000) { request.join() }
                assertTrue("cancel did not interrupt an active response body", interrupted)
                assertTrue(activeCall.get()!!.isCanceled())
            } finally {
                release.countDown()
                peer.get()?.close()
                listener.close()
                server.join(1000)
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun successfulBodyIsNotCanceledByWatcherCleanup() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val server = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    readHeaders(socket)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        flush()
                    }
                }
            }
            val http = OkHttpClient()
            val activeCall = AtomicReference<Call?>()
            val client = CancellableCoilNetworkClient(Call.Factory { request ->
                http.newCall(request).also(activeCall::set)
            })
            try {
                val body = withTimeout(5000) {
                    client.executeRequest(NetworkRequest("http://127.0.0.1:${listener.localPort}/image")) { response ->
                        val sink = Buffer()
                        response.body!!.writeTo(sink)
                        sink.readUtf8()
                    }
                }
                assertEquals("ok", body)
                assertFalse(activeCall.get()!!.isCanceled())
            } finally {
                listener.close()
                server.join(1000)
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun readHeaders(socket: Socket) {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) return
        }
    }
}
