package cn.hdfk7.android.iptv.core.data.network

import java.io.IOException
import cn.hdfk7.android.iptv.core.model.ProxySettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.GzipSource
import okio.buffer
import java.util.concurrent.TimeUnit

class PlaylistDownloader(private val settings: () -> ProxySettings = { ProxySettings() }) {
    suspend fun download(address: String): String {
        return download(address, MAX_BYTES, guide = false)
    }

    internal suspend fun downloadGuide(address: String): String {
        return download(address, 24 * 1024 * 1024, guide = true)
    }

    private data class Clients(val settings: ProxySettings, val playlist: OkHttpClient, val guide: OkHttpClient)

    private var clients: Clients? = null

    @Synchronized
    private fun clientFor(guide: Boolean): OkHttpClient {
        val currentSettings = settings()
        val existing = clients
        if (existing != null && existing.settings == currentSettings) {
            return if (guide) existing.guide else existing.playlist
        }
        val playlist = ProxyHttpClient.create(currentSettings).newBuilder()
            .followRedirects(false).followSslRedirects(false).callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor(interceptor = { chain ->
                val request = chain.request()
                if (request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) {
                    throw IOException("URL credentials are unsupported")
                }
                chain.proceed(request)
            }).build()
        val programmeGuide = playlist.newBuilder().followRedirects(true).followSslRedirects(true).build()
        clients = Clients(currentSettings, playlist, programmeGuide)
        // Running calls retain their own client and can finish or be cancelled normally.
        existing?.playlist?.connectionPool?.evictAll()
        return if (guide) programmeGuide else playlist
    }

    private suspend fun download(address: String, maxBytes: Int, guide: Boolean): String {
        val call = clientFor(guide).newCall(Request.Builder().url(address).header("User-Agent", "iptv/1.0").build())
        return suspendCancellableCoroutine(block = { continuation ->
            continuation.invokeOnCancellation(handler = { call.cancel() })
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException): Unit {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response): Unit {
                    continuation.resumeWith(runCatching(block = { response.use(block = { received -> read(received, maxBytes, gzip = guide) }) }))
                }
            })
        })
    }

    private fun read(response: Response, maxBytes: Int, gzip: Boolean): String {
        if (response.code != 200) {
            throw IOException("Playlist HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty playlist")
        val raw = body.source()
        val source = if (gzip && raw.request(2L) && raw.buffer[0L] == 0x1f.toByte() &&
            raw.buffer[1L] == 0x8b.toByte()) {
            GzipSource(raw).buffer()
        } else {
            raw
        }
        source.request(maxBytes + 1L)
        if (source.buffer.size > maxBytes) {
            throw IOException("Response exceeds size limit")
        }
        return source.readUtf8()
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
