package cn.hdfk7.android.iptv.core.data.network

import java.io.IOException
import cn.hdfk7.android.iptv.core.model.ProxySettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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

    private suspend fun download(address: String, maxBytes: Int, guide: Boolean): String {
        val client = ProxyHttpClient.create(settings()).newBuilder()
            .followRedirects(guide).followSslRedirects(guide).callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor(interceptor = { chain ->
                val request = chain.request()
                if (request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) {
                    throw IOException("URL credentials are unsupported")
                }
                chain.proceed(request)
            }).build()
        try {
            val call = client.newCall(Request.Builder().url(address).header("User-Agent", "iptv/1.0").build())
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
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
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
