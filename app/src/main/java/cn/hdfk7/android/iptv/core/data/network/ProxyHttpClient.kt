package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.ProxySettings
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ProxyHttpClient {
    fun create(settings: ProxySettings, connectMs: Int = 15000, readMs: Int = 15000): OkHttpClient {
        require(settings.valid())
        val builder = OkHttpClient.Builder().connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
        builder.proxy(if (settings.enabled) Proxy(Proxy.Type.HTTP, InetSocketAddress(settings.host, settings.port.toInt())) else Proxy.NO_PROXY)
        if (settings.enabled && settings.username.isNotEmpty()) {
            builder.addNetworkInterceptor(interceptor = { chain ->
                val request = chain.request()
                // A redirect from HTTP can carry proxy headers into the HTTPS tunnel.
                if (request.isHttps && request.header("Proxy-Authorization") != null) {
                    chain.proceed(request.newBuilder().removeHeader("Proxy-Authorization").build())
                } else {
                    chain.proceed(request)
                }
            })
            builder.proxyAuthenticator(proxyAuthenticator = { _, response ->
                val request = response.request
                if (request.header("Proxy-Authorization") != null || (request.isHttps && request.method != "CONNECT")) {
                    null
                } else {
                    request.newBuilder().header("Proxy-Authorization", Credentials.basic(settings.username, settings.password)).build()
                }
            })
        }
        return builder.build()
    }
}
