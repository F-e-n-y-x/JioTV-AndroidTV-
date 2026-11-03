package com.fenyx.jtv.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/** Calls another JTV device's [MiniHttpServer]. Short timeouts: an unreachable device is skipped fast. */
object SyncClient {

    class Reply(val status: Int, val json: JSONObject?) {
        val ok get() = status in 200..299
    }

    /** Never throws: a network failure comes back as status -1. */
    suspend fun call(
        host: InetAddress,
        port: Int,
        method: String,
        path: String,
        key: String? = null,
        body: JSONObject? = null,
    ): Reply = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val h = if (host is Inet6Address) "[${host.hostAddress?.substringBefore('%')}]" else host.hostAddress
            conn = (URL("http", h, port, path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                if (key != null) setRequestProperty("Authorization", "Bearer $key")
            }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MiniHttpServer.MAX_BODY) break
                }
                out.toString("UTF-8")
            }
            Reply(status, text?.let { runCatching { JSONObject(it) }.getOrNull() })
        } catch (_: Exception) {
            Reply(-1, null)
        } finally {
            conn?.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 4_000
}
