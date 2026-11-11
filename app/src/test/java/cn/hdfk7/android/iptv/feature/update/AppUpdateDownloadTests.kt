package cn.hdfk7.android.iptv.feature.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import cn.hdfk7.android.iptv.R
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppUpdateDownloadTests {
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
