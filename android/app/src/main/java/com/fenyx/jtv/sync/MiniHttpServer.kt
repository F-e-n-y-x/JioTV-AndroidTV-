package com.fenyx.jtv.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A deliberately tiny HTTP/1.1 server for LAN sync: one request per connection (Connection: close),
 * JSON bodies only, 8 KB of headers, 64 KB of body, 5 s socket timeouts, at most 8 connections at once.
 * Connections from non-private addresses are closed without a reply.
 */
class MiniHttpServer(private val handler: suspend (Request) -> Response) {

    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val remote: InetAddress,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(val status: Int, val body: String = "{}")

    private class HttpError(val status: Int) : Exception()

    private var server: ServerSocket? = null
    private var job: Job? = null
    private val slots = Semaphore(MAX_CONNECTIONS)

    /** Starts listening on an ephemeral port (all interfaces; LAN-only is enforced per connection). */
    @Synchronized
    fun start(scope: CoroutineScope): Int {
        server?.let { return it.localPort }
        val ss = ServerSocket(0)
        server = ss
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                if (!LanAddress.isPrivate(s.inetAddress) || !slots.tryAcquire()) {
                    runCatching { s.close() }
                    continue
                }
                launch {
                    try { serve(s) } finally { slots.release(); runCatching { s.close() } }
                }
            }
        }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }

    private suspend fun serve(s: Socket) {
        s.soTimeout = SOCKET_TIMEOUT_MS
        val response = try {
            val req = readRequest(s)
            withTimeout(HANDLER_TIMEOUT_MS) { handler(req) }
        } catch (e: HttpError) {
            Response(e.status, """{"error":"bad request"}""")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Response(503, """{"error":"busy"}""")
        } catch (e: java.net.SocketTimeoutException) {
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
            Response(500, """{"error":"server"}""")
        }
        runCatching { write(s, response) }
    }

    private fun readRequest(s: Socket): Request {
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input)
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400)
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpError(411)
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0) throw HttpError(400)
        if (length > MAX_BODY) throw HttpError(413)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw HttpError(400)
            read += n
        }
        return Request(method, path, headers, String(body, Charsets.UTF_8), s.inetAddress)
    }

    /** Reads up to the blank line that ends the headers. */
    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream(512)
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw HttpError(400)
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (out.size() > MAX_HEAD) throw HttpError(431)
        }
        return String(out.toByteArray(), 0, out.size() - 4, Charsets.ISO_8859_1)
    }

    private fun write(s: Socket, r: Response) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"; 413 -> "Payload Too Large"
            429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${r.status} $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val os = s.getOutputStream()
        os.write(head.toByteArray(Charsets.ISO_8859_1))
        os.write(body)
        os.flush()
    }

    companion object {
        private const val TAG = "LanSync"
        const val MAX_BODY = 64 * 1024
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_CONNECTIONS = 8
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val HANDLER_TIMEOUT_MS = 5_000L
    }
}
