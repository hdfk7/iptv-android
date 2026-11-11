package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.data.network.PlaylistParser
import cn.hdfk7.android.iptv.core.model.PlaylistSnapshot
import cn.hdfk7.android.iptv.core.model.RefreshResult
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface PlaylistStore {
    fun read(): PlaylistSnapshot?
    fun write(snapshot: PlaylistSnapshot)
}

class RefreshEngine(private val store: PlaylistStore, private val download: suspend (String) -> String, private val clock: () -> Long) {
    suspend fun refresh(sourceUrl: String = SOURCE_URL, readResponseTime: Boolean = false): RefreshResult {
        val cached = store.read()
        try {
            val channels = PlaylistParser().parse(download(sourceUrl), sourceUrl, readResponseTime)
            if (channels.isEmpty()) {
                throw IOException("Empty channel catalog")
            }
            val snapshot = PlaylistSnapshot(channels, clock())
            currentCoroutineContext().ensureActive()
            store.write(snapshot)
            return RefreshResult(snapshot, failed = false)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return RefreshResult(cached, failed = true)
        }
    }

    companion object {
        const val SOURCE_URL = LibraryRules.DEFAULT_URL
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        fun isStale(snapshot: PlaylistSnapshot, now: Long): Boolean {
            return now < snapshot.updatedAt || now - snapshot.updatedAt >= DAY_MILLIS
        }
    }
}
