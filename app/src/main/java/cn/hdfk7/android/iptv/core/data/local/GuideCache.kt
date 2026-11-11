package cn.hdfk7.android.iptv.core.data.local

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class GuideCache(private val directory: File) {
    @Synchronized
    fun read(address: String, now: Long): String? {
        val file = file(address)
        if (now - file.baseFile.lastModified() !in 0..MAX_AGE_MS || file.baseFile.length() > MAX_BYTES) {
            file.delete()
            return null
        }
        return try {
            file.openRead().bufferedReader().use(block = { reader -> reader.readText() })
        } catch (exception: IOException) {
            null
        }
    }

    @Synchronized
    fun write(address: String, text: String): Unit {
        val data = text.toByteArray()
        if (data.size > MAX_BYTES) {
            return
        }
        val file = file(address)
        val output = try {
            file.startWrite()
        } catch (exception: IOException) {
            return
        }
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (exception: IOException) {
            file.failWrite(output)
            return
        }
        directory.listFiles()?.filter(predicate = { entry -> entry.extension == "xml" })
            ?.sortedByDescending(selector = File::lastModified)
            ?.drop(4)?.forEach(action = { entry -> AtomicFile(entry).delete() })
    }

    private fun file(address: String): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString(separator = "", transform = { byte -> "%02x".format(byte) })
        return AtomicFile(File(directory, "$hash.xml"))
    }

    private companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}
