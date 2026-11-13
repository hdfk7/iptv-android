package cn.hdfk7.android.iptv.core.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal class StandbyFrameCache(private val directory: File) {
    private companion object {
        const val MAX_FRAMES = 12
        const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L
    }

    @Synchronized
    fun read(key: String): Bitmap? {
        val file = file(key)
        val age = System.currentTimeMillis() - file.baseFile.lastModified()
        if (age !in 0..MAX_AGE_MS) {
            file.delete()
            return null
        }
        return try {
            file.openRead().use(block = { input -> BitmapFactory.decodeStream(input) })
        } catch (exception: IOException) {
            null
        }
    }

    @Synchronized
    fun write(key: String, frame: Bitmap): Unit {
        val file = file(key)
        val output = try {
            file.startWrite()
        } catch (exception: IOException) {
            return
        }
        try {
            if (frame.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                file.finishWrite(output)
            } else {
                file.failWrite(output)
                return
            }
        } catch (exception: IOException) {
            file.failWrite(output)
            return
        }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        directory.listFiles()?.filter(predicate = { entry -> entry.extension == "jpg" })
            ?.sortedByDescending(selector = File::lastModified)
            ?.forEachIndexed(action = { index, entry ->
                if (index >= MAX_FRAMES || entry.lastModified() < cutoff) {
                    AtomicFile(entry).delete()
                }
            })
    }

    private fun file(key: String): AtomicFile {
        val hash = cacheKey(key)
        return AtomicFile(File(directory, "$hash.jpg"))
    }
}
