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
    val stopMs: Long
)

enum class EpgSyncStatus {
    IDLE, DOWNLOADING, EXTRACTING, PARSING, COMPLETED, ERROR
}

class EpgRepository(private val context: Context) {
    private val TAG = "EpgRepository"
    private val cacheFileName = "epg_cache.xml"
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH)

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
            try {
                var redirectCount = 0
                var currentUrlStr = urlStr
                var connection: HttpURLConnection? = null
                
                while (redirectCount < 5) {
                    val url = URL(currentUrlStr)
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                        responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                        responseCode == 307 || responseCode == 308) {
                        currentUrlStr = connection.getHeaderField("Location") ?: break
                        redirectCount++
                    } else {
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
            }
        }

        if (!cacheFile.exists()) {
            if (_syncStatus.value != EpgSyncStatus.ERROR) _syncStatus.value = EpgSyncStatus.IDLE
            return@withContext emptyMap()
        }

        Log.d(TAG, "Parsing EPG data")
        _syncStatus.value = EpgSyncStatus.PARSING
        val epgMap = mutableMapOf<String, MutableList<EpgProgram>>()
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
                            
                            try {
                                currentStartMs = dateFormat.parse(startStr)?.time ?: 0
                                currentStopMs = dateFormat.parse(stopStr)?.time ?: 0
                            } catch (e: Exception) {
                                currentStartMs = 0
                                currentStopMs = 0
                            }
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

    suspend fun getNativeEpgForChannel(channelId: String): List<EpgProgram> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://jiotvapi.cdn.jio.com/apis/v1.3/getepg/get?offset=0&channel_id=$channelId&langId=6")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (connection.responseCode in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(text)
                val epgArray = json.optJSONArray("epg") ?: return@withContext emptyList()
                val programs = mutableListOf<EpgProgram>()
                for (i in 0 until epgArray.length()) {
                    val obj = epgArray.getJSONObject(i)
                    val title = obj.optString("showname", "")
                    val desc = obj.optString("description", "")
                    val startMs = obj.optLong("startEpoch", 0)
                    val stopMs = obj.optLong("endEpoch", 0)
                    if (title.isNotEmpty() && startMs > 0 && stopMs > 0) {
                        programs.add(EpgProgram(title, desc, startMs, stopMs))
                    }
                }
                return@withContext programs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch native EPG for $channelId", e)
        }
        return@withContext emptyList()
    }
}
