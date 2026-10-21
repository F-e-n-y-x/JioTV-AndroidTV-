package com.fenyx.jtv.data

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Jio's time: the box clock corrected by the offset learned from Jio's HTTP `Date` header. Cheap TV
 * boxes often run minutes off, and the 2-minute stream tokens (`__hdnea__` exp) and the access-token
 * expiry are Jio times: compared with a wrong box clock they looked expired (a geturl every 5 s, no
 * prefetch) or never expiring (no refresh ahead of time).
 */
object JioClock {
    @Volatile private var offsetMs = 0L
    fun nowMs(): Long = System.currentTimeMillis() + offsetMs
    /** [serverDateMs] = HttpURLConnection.date (0 when the header is missing). */
    fun learn(serverDateMs: Long) { if (serverDateMs > 0) offsetMs = serverDateMs - System.currentTimeMillis() }
}

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
            // IPv4 first: OkHttp 4 has no fast fallback, and a broken IPv6 route (common on Jio
            // hotspots / JioFiber) stalled each new connection until connectTimeout.
            .dns(object : Dns {
                override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).sortedBy { it !is Inet4Address }
            })
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
            // geturl runs on HttpURLConnection (its own pool), so warm that host there; its Date header
            // also sets JioClock before the first stream token is checked.
            runCatching {
                val c = java.net.URL("https://jiotvapi.media.jio.com/").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 5000; c.readTimeout = 5000
                c.responseCode
                JioClock.learn(c.date)
                c.inputStream.close()
            }
            hosts.forEach { url ->
                runCatching {
                    client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().close()
                }
            }
        }.apply { isDaemon = true; name = "jtv-net-warmup" }.start()
    }
}

/**
 * Writes via a temp file + rename so a crash or power cut mid-write never leaves a truncated file
 * (a half-written cache used to be read back as garbage on the next start).
 */
internal fun java.io.File.writeAtomically(write: (java.io.OutputStream) -> Unit) {
    val tmp = java.io.File(parentFile, "$name.tmp")
    try {
        tmp.outputStream().use(write)
        if (!tmp.renameTo(this)) throw java.io.IOException("rename to $name failed")
    } finally {
        tmp.delete() // no-op after a successful rename
    }
}

internal fun java.io.File.writeTextAtomically(text: String) = writeAtomically { it.write(text.toByteArray()) }
