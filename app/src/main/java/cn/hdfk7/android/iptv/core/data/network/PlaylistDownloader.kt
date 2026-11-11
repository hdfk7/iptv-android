package cn.hdfk7.android.iptv.core.data.network

import java.io.IOException
import cn.hdfk7.android.iptv.core.model.ProxySettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class PlaylistDownloader(private val settings: () -> ProxySettings = { ProxySettings() }) {
    suspend fun download(address: String): String {
        val client = ProxyHttpClient.create(settings()).newBuilder()
            .followRedirects(false).followSslRedirects(false).callTimeout(45, TimeUnit.SECONDS).build()
        try {
            val call = client.newCall(Request.Builder().url(address).header("User-Agent", "iptv/1.0").build())
            return suspendCancellableCoroutine(block = { continuation ->
                continuation.invokeOnCancellation(handler = { call.cancel() })
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException): Unit {
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response): Unit {
                        continuation.resumeWith(runCatching(block = { response.use(block = ::read) }))
                    }
                })
            })
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun read(response: Response): String {
        if (response.code != 200) {
            throw IOException("Playlist HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty playlist")
        val source = body.source()
        source.request(MAX_BYTES + 1L)
        if (source.buffer.size > MAX_BYTES) {
            throw IOException("Playlist exceeds size limit")
        }
        return source.readUtf8()
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
