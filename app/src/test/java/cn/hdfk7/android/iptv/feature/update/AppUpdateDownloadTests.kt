package cn.hdfk7.android.iptv.feature.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import cn.hdfk7.android.iptv.R
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppUpdateDownloadTests {
    @Test
    fun digestRejectsDifferentBytesEvenWhenFileNameAndSizeMatch(): Unit {
        val apk = Files.createTempFile("iptv-update-", ".apk").toFile()
        try {
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            apk.writeText("abc")
            assertTrue(apk.matchesUpdateDigest(expected.uppercase()))
            apk.writeText("abd")
            assertFalse(apk.matchesUpdateDigest(expected))
            assertTrue(apk.matchesUpdateDigest(null))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun redirectedManifestReportsTheFinalUrlForRelativeApks(): Unit = runBlocking(block = {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/latest.json", { exchange ->
            exchange.responseHeaders.add("Location", "/releases/latest.json")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        })
        val manifest = """{"apkUrl":"iptv.apk"}"""
        server.createContext("/releases/latest.json", { exchange ->
            val bytes = manifest.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use(block = { body -> body.write(bytes) })
        })
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val downloaded = downloadUpdateManifest("$base/latest.json")
            assertEquals(manifest, downloaded.body)
            assertEquals("$base/releases/iptv.apk", downloaded.url.resolve("iptv.apk").toString())
        } finally {
            server.stop(0)
        }
    })

    @Test
    fun reportsCompletionForKnownAndUnknownLengths() {
        val data = byteArrayOf(1, 2, 3)
        for (total in listOf(3L, -1L)) {
            val output = ByteArrayOutputStream()
            val updates = mutableListOf<AppDownloadProgress>()
            copyUpdateBody(data.inputStream(), output, total, isActive = { true }, onProgress = { progress ->
                updates.add(progress)
            })
            assertArrayEquals(data, output.toByteArray())
            assertEquals(AppDownloadProgress(0, total), updates.first())
            assertEquals(AppDownloadProgress(3, total), updates.last())
            if (total > 0) {
                assertEquals(1f, updates.last().fraction)
            } else {
                assertNull(updates.last().fraction)
            }
        }
    }

    @Test
    fun rejectsTruncatedDownloads() {
        assertThrows(IOException::class.java, {
            copyUpdateBody(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), 2L, isActive = { true }, onProgress = {})
        })
    }

    @Test
    fun cancellationStopsWriting() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java, {
            copyUpdateBody(byteArrayOf(1).inputStream(), output, 1L, isActive = { false }, onProgress = {})
        })
        assertEquals(0, output.size())
    }

    @Test
    fun oversizedDownloadsAreRejectedWithOrWithoutContentLength(): Unit {
        val limit = 256L * 1024 * 1024
        for (total in listOf(limit + 1, -1L)) {
            val input = object : InputStream() {
                override fun read(): Int = error("Expected buffered reads")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = length
            }
            val failure = assertThrows(AppUpdateDownloadException::class.java, {
                copyUpdateBody(input, OutputStream.nullOutputStream(), total, isActive = { true }, onProgress = {})
            })
            assertEquals(R.string.app_update_download_too_large, failure.messageRes)
        }
    }
}
