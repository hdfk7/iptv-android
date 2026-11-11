package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.ProxySettings
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ProxyHttpClientTests {
    @Test fun validatesAddressPortAndCredentials() {
        assertTrue(ProxySettings(true, "127.0.0.1", "8080").valid())
        assertTrue(ProxySettings(true, "::1", "8080").valid())
        for (settings in listOf(ProxySettings(true, "256.0.0.1", "80"), ProxySettings(true, "127.0.0.1", "0"), ProxySettings(true, "http://127.0.0.1", "80"), ProxySettings(true, "127.0.0.1", "65536"), ProxySettings(true, "127.0.0.1", "80", "", "secret"))) {
            assertFalse(settings.valid())
        }
        assertFalse(ProxySettings(password = "secret").toString().contains("secret"))
    }

    @Test fun playlistUsesProxyAndAnswersAuthenticationChallenge(): Unit = runBlocking(block = {
        ServerSocket(0).use(block = { server ->
            val requests = CompletableFuture.supplyAsync(java.util.function.Supplier {
                val captured = mutableListOf<String>()
                repeat(2, action = { index ->
                    server.accept().use(block = { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            lines.add(line)
                            line = reader.readLine()
                        }
                        captured.add(lines.joinToString("\n"))
                        val response = if (index == 0) "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=qa\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\n#EXTM3U"
                        socket.getOutputStream().write(response.toByteArray())
                    })
                })
                captured
            })
            val settings = ProxySettings(true, "127.0.0.1", server.localPort.toString(), "tester", "test-password")
            assertEquals("#EXTM3U", PlaylistDownloader(settings = { settings }).download("http://unresolvable.invalid/list.m3u"))
            val captured = requests.get(10, TimeUnit.SECONDS)
            assertTrue(captured[0].startsWith("GET http://unresolvable.invalid/list.m3u HTTP/1.1"))
            assertFalse(captured[0].contains("Proxy-Authorization"))
            assertTrue(captured[1].contains("Proxy-Authorization: " + Credentials.basic("tester", "test-password")))
            assertFalse(captured[1].contains("\nAuthorization:"))
        })
    })

    @Test fun disabledProxyConnectsDirectly() {
        ServerSocket(0).use(block = { server ->
            val received = CompletableFuture.supplyAsync(java.util.function.Supplier {
                server.accept().use(block = { socket ->
                    val request = socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    request
                })
            })
            val client = ProxyHttpClient.create(ProxySettings(false, "192.0.2.1", "9"))
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/direct").build()).execute().use(block = { assertEquals(200, it.code) })
                assertEquals("GET /direct HTTP/1.1", received.get(5, TimeUnit.SECONDS))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        })
    }

    @Test fun cancellingPlaylistDownloadClosesActiveRequest(): Unit = runBlocking(block = {
        ServerSocket(0).use(block = { server ->
            server.soTimeout = 5000
            val requestReceived = CompletableFuture<Unit>()
            val disconnected = CompletableFuture.supplyAsync(java.util.function.Supplier {
                server.accept().use(block = { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) {
                        // Read the request headers before cancelling the call.
                    }
                    requestReceived.complete(Unit)
                    reader.read()
                })
            })
            val download = launch(context = kotlinx.coroutines.Dispatchers.IO, block = {
                PlaylistDownloader().download("http://127.0.0.1:${server.localPort}/slow")
            })
            try {
                requestReceived.get(5, TimeUnit.SECONDS)
                download.cancelAndJoin()
                assertEquals(-1, disconnected.get(5, TimeUnit.SECONDS))
            } finally {
                download.cancelAndJoin()
            }
        })
    })
}
