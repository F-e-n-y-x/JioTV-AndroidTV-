package com.fenyx.jtv.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * The app's single OkHttp client, shared by the player (manifests, segments, AES keys via
 * OkHttpDataSource), the zap prefetch's manifest probe and Coil (channel logos), so they all reuse
 * the same pooled HTTP/2 connections instead of each opening their own.
 *
 * The Jio auth / geturl calls stay on HttpURLConnection on purpose: those endpoints are picky about
 * header spelling and an HTTP/2 connection lowercases every header name.
 */
object Net {
    /** What Jio's own Android app sends; pinned so an OkHttp bump can't change it silently. */
    const val USER_AGENT = "okhttp/4.12.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                // Per-request headers (e.g. the stream's plaYtv User-Agent) win; this is the fallback.
                if (req.header("User-Agent") == null) b.header("User-Agent", USER_AGENT)
                // A caller-set Accept-Encoding (the AES key headers carry "gzip, deflate") turns off
                // OkHttp's transparent gunzip, and unlike DefaultHttpDataSource, OkHttpDataSource
                // does not unzip by itself, so a gzipped key would reach the decrypter compressed.
                // Drop it and let OkHttp ask for gzip and unzip the response itself.
                if (req.header("Accept-Encoding") != null) b.removeHeader("Accept-Encoding")
                chain.proceed(b.build())
            }
            .build()
    }

    /**
     * Opens (and pools) connections to Jio's video CDNs in the background at app start, so the first
     * channel doesn't also pay for DNS + TCP + TLS (often 300–800 ms on a cold box). Fire-and-forget.
     */
    fun warmUp() {
        val hosts = listOf("https://jiotvbpkmob.cdn.jio.com/", "https://jiotvmblive.cdn.jio.com/", "https://jiotvimages.cdn.jio.com/")
        Thread {
            hosts.forEach { url ->
                runCatching {
                    client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().close()
                }
            }
        }.apply { isDaemon = true; name = "jtv-net-warmup" }.start()
    }
}
