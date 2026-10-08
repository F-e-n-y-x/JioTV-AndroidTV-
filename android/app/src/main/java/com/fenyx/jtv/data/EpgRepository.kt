package com.fenyx.jtv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class EpgProgram(
    val title: String,
    val description: String,
    val startMs: Long,
    val stopMs: Long,
    val srno: String? = null,
    val showId: String? = null,
    val showtime: String? = null,
    val catchup: Boolean = false,
    // Native-guide extras (null for XMLTV). Kept small: cast is trimmed, the poster is a short path.
    /** Episode synopsis, only when it differs from [description]. */
    val episodeDesc: String? = null,
    /** "Series", "Movie", "Sports", "News"... */
    val category: String? = null,
    /** First genre only, e.g. "Sitcom". */
    val genre: String? = null,
    /** Age rating: "U", "UA" or "A". */
    val rating: String? = null,
    /** Up to four names, roles stripped, joined with ", ". */
    val cast: String? = null,
    val director: String? = null,
    /** Episode number, null when the guide has none. */
    val episodeNum: Int? = null,
    /** Show picture path from Jio's guide ("epgdata/….jpg"); see [posterUrl]. */
    val poster: String? = null,
) {
    /** The show picture (16:9, served at 1920×1080: always load it with a small decode size). */
    val posterUrl: String? get() = poster?.let { "https://jiotvimages.cdn.jio.com/dare_images/shows/$it" }
}

enum class EpgSyncStatus {
    IDLE, DOWNLOADING, EXTRACTING, PARSING, COMPLETED, ERROR
}

class EpgRepository(private val context: Context) {

    companion object {
        /**
         * Parses an XMLTV `start`/`stop` timestamp ("yyyyMMddHHmmss Z", e.g. "20260711183000 +0530")
         * to epoch millis, returning 0 for a null/blank/malformed value. The [SimpleDateFormat] is
         * passed in (not created here) because it is not thread-safe and the parse loop reuses a single
         * instance — so this stays allocation-free on the hot path while remaining unit-testable.
         */
        /** A trimmed string field, or null when it is missing, blank or JSON null. */
        internal fun jsonText(obj: org.json.JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }

        /** `showGenre` is an array (["Sitcom"]); a string is accepted too. First entry only. */
        internal fun firstGenre(obj: org.json.JSONObject): String? {
            val arr = obj.optJSONArray("showGenre")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val g = arr.optString(i, "").trim()
                    if (g.isNotEmpty() && g != "null") return g
                }
                return null
            }
            return jsonText(obj, "showGenre")?.trim('[', ']', ' ')?.split(',')?.firstOrNull()
                ?.trim(' ', '\'', '"')?.takeIf { it.isNotEmpty() }
        }

        private val ROLE = Regex("\\([^)]*\\)")

        /** "Name (Role),Name (Role),..." -> "Name, Name, Name, Name" (first four, roles dropped). */
        internal fun shortCast(raw: String?): String? = shortNames(raw?.replace(ROLE, ""), 4)

        internal fun shortNames(raw: String?, max: Int): String? {
            if (raw.isNullOrBlank()) return null
            return raw.split(',').asSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .distinct().take(max).joinToString(", ").takeIf { it.isNotEmpty() }
        }

        internal fun parseXmltvMillis(value: String?, fmt: SimpleDateFormat): Long {
            if (value.isNullOrBlank()) return 0
            return try { fmt.parse(value)?.time ?: 0 } catch (e: Exception) { 0 }
        }
    }

    private val TAG = "EpgRepository"
    private val cacheFileName = "epg_cache.xml"
    // NOTE: SimpleDateFormat is NOT thread-safe. getEpgData can run concurrently (auto-fetch when EPG
    // mode is enabled + a manual "Refresh EPG" from Settings), so each parse creates its own formatter
    // instead of sharing one instance, which previously could corrupt parsed times or throw.

    private val _syncStatus = MutableStateFlow(EpgSyncStatus.IDLE)
    val syncStatus: StateFlow<EpgSyncStatus> = _syncStatus

    /**
     * Downloads EPG if missing, older than 12 hours, or forceRefresh is true, then parses it.
     * Returns a map of channel_id to a list of EpgProgram.
     */
    suspend fun getEpgData(urlStr: String, forceRefresh: Boolean = false): Map<String, List<EpgProgram>> = withContext(Dispatchers.IO) {
        val cacheDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val cacheFile = File(cacheDir, cacheFileName)
        val twelveHoursMs = 12 * 60 * 60 * 1000L

        if (forceRefresh || !cacheFile.exists() || (System.currentTimeMillis() - cacheFile.lastModified() > twelveHoursMs)) {
            Log.d(TAG, "Downloading EPG from $urlStr")
            _syncStatus.value = EpgSyncStatus.DOWNLOADING
            var connection: HttpURLConnection? = null
            try {
                var redirectCount = 0
                var currentUrlStr = urlStr

                while (redirectCount < 5) {
                    val url = URL(currentUrlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                    val responseCode = conn.responseCode
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == 307 || responseCode == 308) {
                        val location = conn.getHeaderField("Location")
                        // Release the intermediate connection before following the redirect, otherwise
                        // each hop leaks a socket (real cost on a low-RAM TV over a chain of shorteners).
                        conn.disconnect()
                        if (location == null) break
                        currentUrlStr = location
                        redirectCount++
                    } else {
                        connection = conn
                        break
                    }
                }

                if (connection != null && connection.responseCode in 200..299) {
                    // Detect gzip by the actual content (magic bytes 0x1f 0x8b) rather than the URL
                    // suffix. URL shorteners / redirects (e.g. the default short.gy link) often resolve
                    // to a path that doesn't end in ".gz", which previously left the gzip bytes stored
                    // raw and made parsing silently fail.
                    val buffered = java.io.BufferedInputStream(connection.inputStream)
                    buffered.mark(2)
                    val b1 = buffered.read()
                    val b2 = buffered.read()
                    buffered.reset()
                    val isGzip = b1 == 0x1f && b2 == 0x8b
                    val inputStream: java.io.InputStream = if (isGzip) {
                        _syncStatus.value = EpgSyncStatus.EXTRACTING
                        GZIPInputStream(buffered)
                    } else {
                        buffered
                    }
                    val outputStream = FileOutputStream(cacheFile)
                    inputStream.copyTo(outputStream)
                    outputStream.close()
                    inputStream.close()
                    Log.d(TAG, "EPG downloaded and saved (gzip=$isGzip)")
                } else {
                    Log.e(TAG, "Failed to download EPG: ${connection?.responseCode}")
                    _syncStatus.value = EpgSyncStatus.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading EPG", e)
                _syncStatus.value = EpgSyncStatus.ERROR
            } finally {
                connection?.disconnect()
            }
        }

        if (!cacheFile.exists()) {
            if (_syncStatus.value != EpgSyncStatus.ERROR) _syncStatus.value = EpgSyncStatus.IDLE
            return@withContext emptyMap()
        }

        Log.d(TAG, "Parsing EPG data")
        _syncStatus.value = EpgSyncStatus.PARSING
        val epgMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()
            val fis = FileInputStream(cacheFile)
            xpp.setInput(fis, null)

            var eventType = xpp.eventType
            var currentChannel: String? = null
            var currentStartMs: Long = 0
            var currentStopMs: Long = 0
            var currentTitle = ""
            var currentDesc = ""
            var currentTag = ""

            val nowMs = System.currentTimeMillis()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = xpp.name
                        if (xpp.name == "programme") {
                            currentChannel = xpp.getAttributeValue(null, "channel")
                            val startStr = xpp.getAttributeValue(null, "start")
                            val stopStr = xpp.getAttributeValue(null, "stop")
                            
                            currentStartMs = parseXmltvMillis(startStr, dateFormat)
                            currentStopMs = parseXmltvMillis(stopStr, dateFormat)
                            currentTitle = ""
                            currentDesc = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = xpp.text.trim()
                        if (text.isNotEmpty()) {
                            if (currentTag == "title") {
                                currentTitle = text
                            } else if (currentTag == "desc") {
                                currentDesc = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (xpp.name == "programme") {
                            // Only keep programs within a ±window to save memory on low-end devices
                            // Past: ended within last 2 hours. Future: starts within next 12 hours.
                            val pastCutoff = nowMs - 2 * 60 * 60 * 1000L
                            val futureCutoff = nowMs + 12 * 60 * 60 * 1000L
                            val ch = currentChannel
                            if (ch != null && currentStopMs > pastCutoff && currentStartMs < futureCutoff) {
                                val program = EpgProgram(currentTitle, currentDesc, currentStartMs, currentStopMs)
                                epgMap.getOrPut(ch) { mutableListOf() }.add(program)
                            }
                            currentChannel = null
                            currentTag = ""
                        }
                    }
                }
                eventType = xpp.next()
            }
            fis.close()
            Log.d(TAG, "EPG parsing completed. Channels mapped: ${epgMap.size}")
            
            // Sort programs by start time
            epgMap.values.forEach { list ->
                list.sortBy { it.startMs }
            }
            _syncStatus.value = EpgSyncStatus.COMPLETED
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPG", e)
            _syncStatus.value = EpgSyncStatus.ERROR
        }

        return@withContext epgMap
    }

    suspend fun getNativeEpgForChannel(channelId: String, offset: Int = 0): List<EpgProgram> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://jiotvapi.cdn.jio.com/apis/v1.3/getepg/get?offset=$offset&channel_id=$channelId&langId=6")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "okhttp/4.12.0")
            connection.setRequestProperty("appname", "RJIL_JioTV")
            connection.setRequestProperty("os", "android")
            connection.setRequestProperty("devicetype", "phone")
            if (connection.responseCode in 200..299) {
                val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
                val rawStream = connection.inputStream
                val stream = if (isGzip && rawStream != null) GZIPInputStream(rawStream) else rawStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = org.json.JSONObject(text)
                val epgArray = json.optJSONArray("epg") ?: return@withContext emptyList()
                val programs = mutableListOf<EpgProgram>()
                // The same category/genre/cast/director repeats on every slot of a channel: share one
                // String instance per distinct value so a day of programmes stays light.
                val pool = HashMap<String, String>()
                fun shared(v: String?): String? = v?.let { pool.getOrPut(it) { it } }
                for (i in 0 until epgArray.length()) {
                    val obj = epgArray.getJSONObject(i)
                    val title = obj.optString("showname", "")
                    val desc = obj.optString("description", "")
                    val startMs = obj.optLong("startEpoch", 0)
                    val stopMs = obj.optLong("endEpoch", 0)
                    val srno = if (obj.has("srno")) obj.optString("srno") else null
                    val showId = if (obj.has("showId")) obj.optString("showId") else null
                    val showtime = if (obj.has("showtime")) obj.optString("showtime") else null
                    val catchup = obj.optBoolean("isCatchupAvailable", false)
                    if (title.isNotEmpty() && startMs > 0 && stopMs > 0) {
                        programs.add(
                            EpgProgram(
                                title, desc, startMs, stopMs, srno, showId, showtime, catchup,
                                episodeDesc = jsonText(obj, "episode_desc")?.takeIf { it != desc.trim() },
                                category = shared(jsonText(obj, "showCategory")),
                                genre = shared(firstGenre(obj)),
                                rating = shared(jsonText(obj, "pcr")),
                                cast = shared(shortCast(jsonText(obj, "starCast"))),
                                director = shared(shortNames(jsonText(obj, "director"), 3)),
                                episodeNum = obj.opt("episode_num")?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                                poster = jsonText(obj, "episodePoster") ?: jsonText(obj, "episodeThumbnail"),
                            )
                        )
                    }
                }
                return@withContext programs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch native EPG for $channelId", e)
        } finally {
            connection?.disconnect()
        }
        return@withContext emptyList()
    }
}
