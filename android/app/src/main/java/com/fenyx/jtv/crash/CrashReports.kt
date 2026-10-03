package com.fenyx.jtv.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Self-hosted crash and ANR reports, no third-party library.
 *
 *  - [install]: an uncaught-exception handler writes a compact report, then calls the previous handler
 *    (so Android still shows / kills the app as usual).
 *  - [collectExitReasons]: on API 30+, at startup, ANRs and native crashes since the last run are read
 *    from `ActivityManager.getHistoricalProcessExitReasons` (ANR traces truncated to ~64 KB).
 *  - Reports live in `filesDir/crash/` as `<epochMs>-<kind>.txt` (newest [MAX_REPORTS] kept). A report
 *    already uploaded gets an empty `<name>.sent` marker next to it.
 *  - [uploadPending] POSTs new reports to a JTV server (`POST /api/reports`, access code as bearer);
 *    [latestReport] feeds Settings → About → "Send problem report" (Android share sheet).
 *
 * Every report is passed through [Redact.secrets] when written and again before it leaves the device.
 */
object CrashReports {

    private const val TAG = "JtvCrash"
    const val MAX_REPORTS = 5
    private const val MAX_TRACE_BYTES = 64 * 1024
    private const val LOG_LINES = 50
    private const val PREFS = "crash_reports"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    data class Report(val file: File, val kind: String, val at: Long, val text: String)

    fun dir(context: Context): File = File(context.filesDir, "crash").apply { mkdirs() }

    // ── Writing ──

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val body = buildString {
                    append("thread: ").append(thread.name).append('\n').append('\n')
                    append(stackTrace(error))
                    val log = recentLog()
                    if (log.isNotBlank()) append("\n--- recent log ---\n").append(log)
                }
                write(app, "crash", System.currentTimeMillis(), body)
            } catch (_: Throwable) {
                // Never let the report get in the way of the real crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Header with app / device / Android, then [body]; redacted, saved, older reports pruned. */
    fun write(context: Context, kind: String, at: Long, body: String): File {
        val text = Redact.secrets(header(context, kind, at) + "\n" + body)
        val d = dir(context)
        val f = File(d, "$at-$kind.txt")
        f.writeText(text)
        prune(d)
        return f
    }

    fun header(context: Context, kind: String, at: Long): String {
        val (name, code) = appVersion(context)
        return buildString {
            append("JTV problem report\n")
            append("kind: ").append(kind).append('\n')
            append("time: ").append(iso(at)).append('\n')
            append("app: ").append(name).append(" (").append(code).append(")\n")
            append("device: ").append(device()).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        }
    }

    private fun appVersion(context: Context): Pair<String, Long> = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        (pi.versionName ?: "?") to code
    } catch (_: Exception) { "?" to 0L }

    private fun device(): String {
        val m = Build.MODEL ?: ""
        val b = Build.MANUFACTURER ?: ""
        return if (m.startsWith(b, ignoreCase = true)) m else "$b $m".trim()
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /**
     * The last [LOG_LINES] lines this process logged (`logcat --pid`, an app may read its own logs).
     * Bounded to ~1.5 s so a slow logcat can't hold up the crash.
     */
    private fun recentLog(): String {
        return try {
            val p = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start()
            var out = ""
            val reader = Thread {
                out = try { p.inputStream.bufferedReader().readLines().takeLast(LOG_LINES).joinToString("\n") } catch (_: Exception) { "" }
            }.apply { isDaemon = true; start() }
            reader.join(1500)
            if (!p.waitFor(100, TimeUnit.MILLISECONDS)) p.destroy()
            out
        } catch (_: Throwable) { "" }
    }

    private fun reportFiles(d: File): List<File> =
        (d.listFiles { f -> f.isFile && NAME.matches(f.name) } ?: emptyArray()).sortedBy { it.name.substringBefore('-').toLongOrNull() ?: 0L }

    private val NAME = Regex("^\\d+-[a-z]+\\.txt$")

    private fun prune(d: File) {
        val all = reportFiles(d)
        all.dropLast(MAX_REPORTS).forEach { it.delete(); sentMarker(it).delete() }
        // Markers whose report is gone.
        d.listFiles { f -> f.name.endsWith(".sent") }?.forEach { m ->
            if (!File(d, m.name.removeSuffix(".sent")).exists()) m.delete()
        }
    }

    private fun sentMarker(f: File) = File(f.parentFile, f.name + ".sent")

    // ── ANR / native crash history (API 30+) ──

    /** Stores ANRs and native crashes the system recorded since the last run. Call off the main thread. */
    fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_EXIT, 0L)
            // First run of this feature: only look back a day.
            val since = if (last > 0) last else System.currentTimeMillis() - 24 * 3600_000L
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            var newest = last
            for (info in infos) {
                if (info.timestamp <= since) continue
                newest = maxOf(newest, info.timestamp)
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                    else -> null
                } ?: continue
                val body = buildString {
                    append("process: ").append(info.processName).append('\n')
                    append("description: ").append(info.description ?: "").append('\n')
                    append("importance: ").append(info.importance).append(", pss: ").append(info.pss).append(" KB\n\n")
                    // ANR traces are text; native ones are a binary tombstone (API 31+), so skip those.
                    if (kind == "anr") {
                        val trace = try { info.traceInputStream?.use { readCapped(it, MAX_TRACE_BYTES) } } catch (_: Exception) { null }
                        if (!trace.isNullOrBlank()) append(trace)
                    }
                }
                write(context, kind, info.timestamp, body)
            }
            if (newest > last) prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read exit reasons: ${e.message}")
        }
    }

    internal fun readCapped(input: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        val truncated = n == max && input.read() >= 0
        return String(buf, 0, n, Charsets.UTF_8) + if (truncated) "\n… (trace truncated at ${max / 1024} KB)" else ""
    }

    // ── Reading / sending ──

    fun reports(context: Context): List<Report> = reportFiles(dir(context)).reversed().mapNotNull { f ->
        val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
        Report(f, f.name.substringAfter('-').removeSuffix(".txt"), f.name.substringBefore('-').toLongOrNull() ?: 0L, text)
    }

    /** Newest report's text (redacted), or null when there is none. */
    fun latestReport(context: Context): String? = reports(context).firstOrNull()?.let { Redact.secrets(it.text) }

    /**
     * Uploads reports not sent yet to the first of [baseUrls] that accepts them. Blocking (call on IO).
     * Returns how many were sent.
     */
    fun uploadPending(context: Context, baseUrls: List<String>, token: String): Int {
        if (token.isBlank() || baseUrls.none { it.isNotBlank() }) return 0
        var sent = 0
        for (r in reports(context).reversed()) {
            val marker = sentMarker(r.file)
            if (marker.exists()) continue
            val json = org.json.JSONObject()
                .put("kind", r.kind)
                .put("at", r.at)
                .put("appVersion", appVersion(context).first)
                .put("device", device())
                .put("android", Build.VERSION.RELEASE ?: "")
                .put("text", Redact.secrets(r.text))
                .toString()
            if (baseUrls.any { it.isNotBlank() && post(it.trim().trimEnd('/') + "/api/reports", token, json) }) {
                runCatching { marker.createNewFile() }
                sent++
            } else {
                break // server unreachable / too old: try again next launch
            }
        }
        return sent
    }

    private fun post(url: String, token: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Report upload failed: ${e.javaClass.simpleName}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
