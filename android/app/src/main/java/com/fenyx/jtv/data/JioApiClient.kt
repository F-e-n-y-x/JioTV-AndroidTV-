package com.fenyx.jtv.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.first

object JioApiClient {
    private const val TAG = "JioApiClient"

    // Default Headers
    private const val USER_AGENT = "okhttp/4.12.0"
    private const val APP_NAME = "RJIL_JioTV"
    private const val OS = "android"
    private const val DEVICE_TYPE = "phone"
    private const val HOST = "jiotvapi.media.jio.com"

    // Jio's own languageIdMapping (apis/v1.3/dictionary/dictionary). The old table had 10 ids wrong
    // (5/8/9/10/11/12/13/14/15/16), which labelled e.g. "Sony Yay Tamil" as Telugu and made language
    // grouping pick the wrong feed.
    private val JIO_LANG_MAP: Map<String, String> = mapOf(
        "1" to "Hindi", "2" to "Marathi", "3" to "Punjabi", "4" to "Urdu", "5" to "Bengali", "6" to "English",
        "7" to "Malayalam", "8" to "Tamil", "9" to "Gujarati", "10" to "Odia", "11" to "Telugu",
        "12" to "Bhojpuri", "13" to "Kannada", "14" to "Assamese", "15" to "Nepali", "16" to "French"
    )

    data class CatchupParams(
        val srno: String,
        val programId: String = "",
        val beginMs: Long,
        val endMs: Long,
        val showtime: String = ""
    )

    data class AuthData(
        val ssoToken: String,
        val authToken: String,
        val crmid: String,
        val uniqueId: String,
        val deviceId: String,
        val userId: String,
        // Distinct from authToken — captured at OTP login and required (as a body field) by the
        // refreshtoken endpoint. Empty for server-mode logins (the server refreshes centrally).
        val refreshToken: String = ""
    )

    /** Jio rejected our refresh (e.g. "refresh token not found"): only a new sign-in fixes it. */
    class SessionExpiredException(message: String) : Exception(message)

    /** Thrown inside [refreshToken] when Jio's token service answers 4xx (as opposed to a network error). */
    private class RefreshRejectedException(message: String) : Exception(message)

    /**
     * Only one credential refresh may run at a time. Jio's refresh token is fragile: firing several
     * refreshes at once (parallel 401s, a retry storm) can leave it "not found", after which nothing
     * but a new OTP sign-in works — the "streams stop after a while" bug (issue #3).
     */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Access token Jio last REJECTED a refresh for. Until the user signs in again (new token) we don't
     * ask Jio again: the session is gone and repeated attempts only spam the token service.
     */
    @Volatile private var rejectedAuthToken: String? = null

    /** Last ahead-of-time refresh attempt, so a failing refresh isn't retried on every stream request. */
    @Volatile private var lastProactiveRefreshMs = 0L
    private const val PROACTIVE_RETRY_GAP_MS = 10 * 60 * 1000L

    /** Refresh the 12 h access token once it has less than this left, while it's still valid. */
    private const val ACCESS_TOKEN_REFRESH_LEAD_SEC = 2 * 60 * 60L

    /** `exp` claim (epoch seconds) of a JWT, or 0 when it can't be read. */
    fun jwtExpirySec(token: String): Long = try {
        val part = token.split(".").getOrNull(1) ?: ""
        val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).optLong("exp", 0L)
    } catch (e: Exception) {
        0L
    }

    /** Jio refused this one channel (geturl 403 even with fresh credentials). Not a login problem. */
    class ChannelBlockedException(message: String) : Exception(message)

    /**
     * Jio refused several different channels in a row, even after a credential refresh: the account or
     * the network (VPN, IP outside India) is refused, not one channel (issue #4).
     */
    class AllChannelsRefusedException(message: String) : Exception(message)

    /** Live channels that got a geturl 403 since the last stream Jio handed out. */
    private val refusedInARow = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private const val REFUSED_CHANNELS_LIMIT = 4

    /** Jio handed back stream URLs, but every one of them is dead on its CDN (404 / not a manifest). */
    class ChannelUnavailableException(message: String) : Exception(message)

    /**
     * Per-channel choice made on first play this session: "hls" when the DASH (Widevine) URL is dead
     * but the non-DRM HLS works (several Zee regional channels), "mpd" when the DASH URL is fine.
     * Remembered so each channel only pays for the manifest check once.
     */
    private val streamModeCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** When each [streamModeCache] entry was learned (epoch ms), for the on-disk copy's expiry. */
    private val streamModeLearnedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var streamModesLoaded = false
    private const val STREAM_MODE_FILE = "stream_modes.tsv"
    /** A learned mode is trusted for a day, then re-probed (a dead DASH can come back, or die). */
    private const val STREAM_MODE_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * Loads the per-channel DASH/HLS choice saved by an earlier run, so the first zap after an app
     * restart doesn't redo the manifest probe. Lines are `channelId<TAB>mode<TAB>learnedAtMs`.
     */
    private fun ensureStreamModesLoaded(context: android.content.Context) {
        if (streamModesLoaded) return
        synchronized(streamModeCache) {
            if (streamModesLoaded) return
            try {
                val f = java.io.File(context.filesDir, STREAM_MODE_FILE)
                if (f.exists()) {
                    val now = System.currentTimeMillis()
                    f.forEachLine { line ->
                        val parts = line.split('\t')
                        val at = parts.getOrNull(2)?.toLongOrNull() ?: return@forEachLine
                        val mode = parts[1]
                        if ((mode == "mpd" || mode == "hls") && now - at in 0 until STREAM_MODE_TTL_MS) {
                            streamModeCache.putIfAbsent(parts[0], mode)
                            streamModeLearnedAt.putIfAbsent(parts[0], at)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read saved stream modes: ${e.message}")
            }
            streamModesLoaded = true
        }
    }

    /** Remembers [mode] for [channelId] in memory and on disk (atomic rewrite of a tiny file). */
    private fun rememberStreamMode(context: android.content.Context, channelId: String, mode: String) {
        streamModeCache[channelId] = mode
        streamModeLearnedAt[channelId] = System.currentTimeMillis()
        try {
            synchronized(streamModeCache) {
                val dir = context.filesDir
                val tmp = java.io.File(dir, "$STREAM_MODE_FILE.tmp")
                tmp.writeText(buildString {
                    streamModeCache.forEach { (id, m) ->
                        append(id).append('\t').append(m).append('\t')
                            .append(streamModeLearnedAt[id] ?: System.currentTimeMillis()).append('\n')
                    }
                })
                if (!tmp.renameTo(java.io.File(dir, STREAM_MODE_FILE))) tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save stream modes: ${e.message}")
        }
    }

    /**
     * Live stream URLs resolved ahead of a zap for the channels next to the one playing (see
     * [prefetchStreamUrl]). [getStreamUrl] hands a valid entry out once instead of calling geturl.
     */
    private val streamUrlCache = StreamUrlCache(maxEntries = 6, marginSec = 20)

    /**
     * Resolves [channelId]'s live stream URL in the background and keeps it in [streamUrlCache] for
     * the next zap. Network only (geturl + the one-off manifest probe). Never refreshes credentials: a
     * prefetch that hits an expired token just gives up and the real zap handles it. Returns true
     * when a usable entry is cached.
     */
    suspend fun prefetchStreamUrl(context: android.content.Context, channelId: String, authData: AuthData): Boolean {
        if (streamUrlCache.hasValid(channelId, authData.authToken)) return true
        val data = getStreamUrl(context, channelId, authData, allowRefreshRetry = false, useCache = false)
            .getOrNull() ?: return false
        return streamUrlCache.put(channelId, data, authData.authToken)
    }

    /** Drops [channelId]'s prefetched URL (e.g. its stream failed). */
    fun invalidateStreamUrl(channelId: String) = streamUrlCache.remove(channelId)

    /**
     * True when [url] answers 2xx with something that looks like an HLS/DASH manifest. A network error
     * counts as alive (let the player try and report it) so a flaky probe never blocks a good channel.
     */
    private fun manifestAlive(url: String, headers: Map<String, String>): Boolean {
        // On the shared OkHttp client: the connection this opens to the CDN (HTTP/2 where offered)
        // goes back to the pool and is reused by the player's own manifest request right after.
        // Only the first bytes are read; closing the response releases the connection.
        return try {
            val req = okhttp3.Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            Net.client.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    val head = resp.body?.source()?.let { src ->
                        src.request(256)
                        val buf = src.buffer
                        buf.readByteArray(minOf(256L, buf.size))
                    } ?: return false
                    if (head.isEmpty()) return false
                    val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    text.startsWith("#EXTM3U") || text.startsWith("<?xml") || text.startsWith("<MPD")
                }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Manifest probe failed (treating as alive): ${e.message}")
            true
        }
    }

    data class StreamData(
        val streamUrl: String,
        val licenseUrl: String,
        val isMpd: Boolean,
        val headers: Map<String, String>,
        val licenseHeaders: Map<String, String>
    )

    private fun readResponseBody(connection: HttpURLConnection, isError: Boolean = false): String {
        val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
        val rawStream = if (isError) (connection.errorStream ?: connection.inputStream) else connection.inputStream
        val stream = if (isGzip && rawStream != null) java.util.zip.GZIPInputStream(rawStream) else rawStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    suspend fun sendOTP(mobile: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val formattedMobile = if (!mobile.startsWith("+91")) "+91$mobile" else mobile
            val base64Mobile = Base64.encodeToString(formattedMobile.toByteArray(), Base64.NO_WRAP)
            
            val url = URL("https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/send")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("user-agent", USER_AGENT)
            connection.setRequestProperty("os", OS)
            connection.setRequestProperty("host", HOST)
            connection.setRequestProperty("devicetype", DEVICE_TYPE)
            connection.setRequestProperty("appname", APP_NAME)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = JSONObject().apply {
                put("number", base64Mobile)
            }.toString()

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode in 200..299 || responseCode == 204) {
                Result.success(Unit)
            } else {
                val errorText = readResponseBody(connection, isError = true)
                Log.e(TAG, "sendOTP failed: $responseCode - $errorText")
                Result.failure(Exception("Failed to send OTP: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendOTP", e)
            Result.failure(e)
        }
    }

    suspend fun verifyOTP(mobile: String, otp: String): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val formattedMobile = if (!mobile.startsWith("+91")) "+91$mobile" else mobile
            val base64Mobile = Base64.encodeToString(formattedMobile.toByteArray(), Base64.NO_WRAP)
            val androidId = UUID.randomUUID().toString()

            val url = URL("https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/verify")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("user-agent", USER_AGENT)
            connection.setRequestProperty("os", OS)
            connection.setRequestProperty("devicetype", DEVICE_TYPE)
            connection.setRequestProperty("appname", APP_NAME)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = JSONObject().apply {
                put("number", base64Mobile)
                put("otp", otp)
                put("deviceInfo", JSONObject().apply {
                    put("consumptionDeviceName", "unknown sdk_google_atv_x86")
                    put("info", JSONObject().apply {
                        put("type", "android")
                        put("platform", JSONObject().apply { put("name", "generic_x86") })
                        put("androidId", androidId)
                    })
                })
            }.toString()

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = readResponseBody(connection, isError = false)
                val json = JSONObject(responseText)
                
                if (json.has("ssoToken")) {
                    val sessionObj = json.optJSONObject("sessionAttributes")?.optJSONObject("user")
                    val authData = AuthData(
                        ssoToken = json.optString("ssoToken", ""),
                        authToken = json.optString("authToken", ""),
                        crmid = sessionObj?.optString("subscriberId", "") ?: "",
                        uniqueId = sessionObj?.optString("unique", "") ?: "",
                        deviceId = json.optString("deviceId", ""),
                        userId = sessionObj?.optString("uid", "") ?: "",
                        refreshToken = json.optString("refreshToken", "")
                    )
                    Result.success(authData)
                } else {
                    Result.failure(Exception(json.optString("message", "Unknown error in OTP verification")))
                }
            } else {
                val errorText = readResponseBody(connection, isError = true)
                Log.e(TAG, "verifyOTP failed: $responseCode - $errorText")
                Result.failure(Exception("Failed to verify OTP: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in verifyOTP", e)
            Result.failure(e)
        }
    }

    suspend fun refreshToken(context: android.content.Context): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val settingsManager = com.fenyx.jtv.data.SettingsManager(context)
            val authData = settingsManager.authDataFlow.first()
            if (authData == null || authData.ssoToken.isEmpty()) return@withContext Result.failure(Exception("No token"))
            if (authData.refreshToken.isEmpty()) {
                // Older logins never stored a refreshToken; the endpoint can't work without it.
                return@withContext Result.failure(Exception("No refresh token — sign out and sign in again to enable auto-refresh."))
            }

            val url = URL("https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("ssotoken", authData.ssoToken)
            // The CURRENT access token must ride along as a header, or Jio always answers "refresh token
            // has expired" even with a valid refreshToken (verified against the live API).
            connection.setRequestProperty("accesstoken", authData.authToken)
            connection.setRequestProperty("appName", APP_NAME)
            connection.setRequestProperty("os", OS)
            connection.setRequestProperty("devicetype", DEVICE_TYPE)
            connection.setRequestProperty("deviceId", authData.deviceId)
            connection.setRequestProperty("uniqueId", authData.uniqueId)
            connection.setRequestProperty("versionCode", "422")
            connection.setRequestProperty("user-agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = JSONObject().apply {
                put("appName", APP_NAME)
                put("deviceId", authData.deviceId)
                put("refreshToken", authData.refreshToken)
                put("uniqueId", authData.uniqueId)
            }.toString()
            OutputStreamWriter(connection.outputStream).use { it.write(body); it.flush() }

            if (connection.responseCode in 200..299) {
                val responseText = readResponseBody(connection, isError = false)
                val json = JSONObject(responseText)
                // The refresh service returns a fresh authToken (and sometimes a new ssoToken /
                // refreshToken). Update whichever are present, keeping the rest.
                val newSsoToken = json.optString("ssoToken", "")
                val newAuthToken = json.optString("authToken", "")
                val newRefreshToken = json.optString("refreshToken", "")
                if (newSsoToken.isNotEmpty() || newAuthToken.isNotEmpty()) {
                    settingsManager.saveAuthData(
                        authData.copy(
                            ssoToken = newSsoToken.ifEmpty { authData.ssoToken },
                            authToken = newAuthToken.ifEmpty { authData.authToken },
                            refreshToken = newRefreshToken.ifEmpty { authData.refreshToken }
                        )
                    )
                    Log.d(TAG, "Token refreshed successfully")
                    return@withContext Result.success(true)
                }
            }
            val code = connection.responseCode
            val errText = readResponseBody(connection, isError = true)
            Log.e(TAG, "Refresh failed $code: $errText")
            val jioMessage = runCatching { JSONObject(errText).optString("message") }.getOrNull().orEmpty()
            if (code in 400..499) Result.failure(RefreshRejectedException("Refresh rejected ($code): $jioMessage"))
            else Result.failure(Exception("Refresh failed with code $code"))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in refreshToken", e)
            Result.failure(e)
        }
    }

    /**
     * Refreshes the stored Jio credentials, choosing the right mechanism for the sign-in method:
     *  - **server mode**: re-pull the centrally-refreshed credentials from the proxy server (the app has
     *    no Jio refreshToken in this mode). This is what makes a server-mode TV self-heal instead of
     *    silently failing once the shared token rotates.
     *  - **phone mode**: use Jio's own refreshtoken endpoint ([refreshToken]).
     */
    suspend fun refreshCredentials(
        context: android.content.Context,
        failedAuthToken: String? = null
    ): Result<Boolean> = refreshMutex.withLock {
        val settingsManager = com.fenyx.jtv.data.SettingsManager(context)
        // Someone else refreshed while we waited for the lock: the token that failed is already
        // replaced, so don't spend (and risk) another refresh.
        if (failedAuthToken != null) {
            val current = settingsManager.authDataFlow.first()
            if (current != null && current.authToken != failedAuthToken) return@withLock Result.success(true)
        }
        val mode = settingsManager.setupModeFlow.first()
        return if (mode == "server" || mode == "jtv") {
            val urls = ServerClient.candidateUrls(mode, settingsManager.serverUrlFlow.first())
            val tok = settingsManager.serverTokenFlow.first()
            if (urls.all { it.isBlank() }) return Result.failure(Exception("No server configured"))
            ServerClient.refreshCredentials(urls, tok).fold(
                onSuccess = { auth -> settingsManager.saveAuthData(auth); Result.success(true) },
                onFailure = { Result.failure(it) }
            )
        } else {
            val current = settingsManager.authDataFlow.first()
            if (current != null && current.authToken == rejectedAuthToken) {
                return@withLock Result.failure(RefreshRejectedException("Jio already rejected this sign-in"))
            }
            val refreshed = refreshToken(context)
            if (refreshed.exceptionOrNull() is RefreshRejectedException && current != null) {
                // The refresh token is gone. Rebuild the session from the SSO token instead of making
                // the user sign in again; only if that fails too is the session really over.
                val mobile = settingsManager.authMobileFlow.first()
                val recovered = withContext(Dispatchers.IO) { recoverSession(current, mobile) }
                if (recovered != null) {
                    settingsManager.saveAuthData(recovered)
                    Log.i(TAG, "Session recovered without a new sign-in")
                    return@withLock Result.success(true)
                }
                rejectedAuthToken = current.authToken
            }
            refreshed
        }
    }

    /**
     * Rebuilds a session whose refresh token Jio rejected, without an OTP: refresh the SSO token (it
     * has no expiry and still refreshes when the refresh token is dead), then trade it for a new access
     * + refresh token pair via `loginotp/exchangetoken` (the call the JioTV apps make after sign-in).
     * Needs the sign-in mobile number, saved since v1.5.7. Returns null when Jio says no.
     */
    private fun recoverSession(auth: AuthData, mobile: String): AuthData? {
        if (mobile.isBlank() || auth.ssoToken.isBlank()) return null
        return try {
            var ssoToken = auth.ssoToken
            (URL("https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6").openConnection() as HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 10000
                setRequestProperty("devicetype", DEVICE_TYPE)
                setRequestProperty("versionCode", "422")
                setRequestProperty("os", OS)
                setRequestProperty("user-agent", USER_AGENT)
                setRequestProperty("ssoToken", auth.ssoToken)
                setRequestProperty("uniqueid", auth.uniqueId)
                setRequestProperty("deviceid", auth.deviceId)
                if (responseCode in 200..299) {
                    JSONObject(readResponseBody(this)).optString("ssoToken").takeIf { it.isNotEmpty() }?.let { ssoToken = it }
                }
                disconnect()
            }

            val formatted = if (mobile.startsWith("+91")) mobile else "+91$mobile"
            val conn = URL("https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/exchangetoken").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.setRequestProperty("ssotoken", ssoToken)
            conn.setRequestProperty("appname", APP_NAME)
            conn.setRequestProperty("deviceid", auth.deviceId)
            conn.setRequestProperty("devicetype", DEVICE_TYPE)
            conn.setRequestProperty("os", OS)
            conn.setRequestProperty("subscriberid", auth.crmid)
            conn.setRequestProperty("persistentRefreshToken", "true")
            conn.setRequestProperty("versionCode", "422")
            conn.setRequestProperty("user-agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().put("number", Base64.encodeToString(formatted.toByteArray(), Base64.NO_WRAP)).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Session recovery rejected: ${conn.responseCode} ${readResponseBody(conn, isError = true).take(120)}")
                conn.disconnect()
                return null
            }
            val json = JSONObject(readResponseBody(conn))
            conn.disconnect()
            val newAuth = json.optString("authToken")
            if (newAuth.isEmpty()) return null
            auth.copy(
                ssoToken = ssoToken,
                authToken = newAuth,
                refreshToken = json.optString("refreshToken").ifEmpty { auth.refreshToken },
                userId = json.optString("userId").ifEmpty { auth.userId },
                crmid = json.optString("subscriberId").ifEmpty { auth.crmid }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Session recovery failed: ${e.message}")
            null
        }
    }

    /**
     * Refreshes the access token BEFORE it expires (it lives 12 h). Refreshing only after Jio has
     * already answered 419 is what left long sessions stuck; this runs before every stream request and
     * from the background alarm, under the same single-refresh lock. Returns the newest credentials.
     */
    suspend fun ensureFreshAccessToken(context: android.content.Context): AuthData? {
        val settingsManager = com.fenyx.jtv.data.SettingsManager(context)
        val auth = settingsManager.authDataFlow.first() ?: return null
        val exp = jwtExpirySec(auth.authToken)
        val nowMs = System.currentTimeMillis()
        if (exp > 0 && exp - nowMs / 1000 < ACCESS_TOKEN_REFRESH_LEAD_SEC &&
            nowMs - lastProactiveRefreshMs > PROACTIVE_RETRY_GAP_MS) {
            lastProactiveRefreshMs = nowMs
            Log.d(TAG, "Access token expires soon, refreshing ahead of time")
            refreshCredentials(context, failedAuthToken = auth.authToken)
            return settingsManager.authDataFlow.first() ?: auth
        }
        return auth
    }

    private fun sessionExpiredMessage(mode: String?): String =
        if (mode == "server" || mode == "jtv")
            "The Jio sign-in on your JTV server has expired. Sign in again on the server's web page, then press OK."
        else
            "Your Jio sign-in has expired. Open Settings → Logout and sign in again with OTP."

    private const val CHANNEL_CACHE_FILE = "channels_cache.json"
    const val CHANNEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private fun channelCacheFile(context: android.content.Context): java.io.File {
        val cacheDir = context.getExternalFilesDir(null) ?: context.filesDir
        return java.io.File(cacheDir, CHANNEL_CACHE_FILE)
    }

    /** Reads the persisted channel list regardless of age. Returns null if there is no usable cache. */
    fun readChannelCache(context: android.content.Context): List<Channel>? {
        val cacheFile = channelCacheFile(context)
        if (!cacheFile.exists()) return null
        return try {
            val jsonArray = org.json.JSONArray(cacheFile.readText())
            val channels = mutableListOf<Channel>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                channels.add(Channel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    logoUrl = obj.getString("logoUrl"),
                    group = obj.getString("group"),
                    streamUrl = obj.optString("streamUrl", ""),
                    isDrm = obj.optBoolean("isDrm", false),
                    channelNumber = obj.optInt("channelNumber", 0),
                    licenseUrl = if (obj.has("licenseUrl")) obj.getString("licenseUrl") else null,
                    language = obj.optString("language", "English"),
                    isCatchup = obj.optBoolean("isCatchup", false),
                    stbNumber = obj.optInt("stbNumber", 0),
                    isPremium = obj.optBoolean("isPremium", false),
                    planType = obj.optString("planType", "")
                ))
            }
            channels.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read channel cache", e)
            null
        }
    }

    /** True if a cache exists and is younger than the TTL. */
    fun isChannelCacheFresh(context: android.content.Context): Boolean {
        val cacheFile = channelCacheFile(context)
        return cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CHANNEL_CACHE_TTL_MS
    }

    /**
     * @param forceNetwork when true, skip the fresh-cache shortcut and always fetch from the network
     *        (used for background revalidation after a fast cached load).
     */
    suspend fun getMobileChannelList(
        context: android.content.Context,
        forceNetwork: Boolean = false
    ): Result<List<Channel>> = withContext(Dispatchers.IO) {
        try {
            val cacheFile = channelCacheFile(context)
            if (!forceNetwork && isChannelCacheFresh(context)) {
                readChannelCache(context)?.let { return@withContext Result.success(it) }
            }

            // Fetch dictionary
            var categoryMap = mapOf<String, String>()
            try {
                val dictUrl = URL("https://jiotvapi.cdn.jio.com/apis/v1.3/dictionary/dictionary?langId=6")
                val dictConn = dictUrl.openConnection() as HttpURLConnection
                dictConn.requestMethod = "GET"
                dictConn.setRequestProperty("User-Agent", USER_AGENT)
                if (dictConn.responseCode in 200..299) {
                    val map = mutableMapOf<String, String>()
                    val reader = android.util.JsonReader(dictConn.inputStream.bufferedReader())
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key == "channelCategoryMapping") {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                map[reader.nextName()] = reader.nextString()
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    reader.close()
                    categoryMap = map
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch dictionary", e)
            }

            val finalChannelsMap = mutableMapOf<Int, Channel>()

            fun parseChannels(urlStr: String, into: MutableMap<Int, Channel>) {
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    if (conn.responseCode in 200..299) {
                        val reader = android.util.JsonReader(conn.inputStream.bufferedReader())
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "result") {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.beginObject()
                                    var channelId = 0
                                    var channelName = ""
                                    var logoUrl = ""
                                    var catId = ""
                                    var langId = ""
                                    var isDrm = false
                                    var isCatchup = false
                                    var stb = 0
                                    var isPremium = false
                                    var planType = ""
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "channel_id" -> channelId = try { reader.nextInt() } catch(e: Exception) { reader.nextString().toIntOrNull() ?: 0 }
                                            "channel_name" -> channelName = reader.nextString()
                                            "logoUrl" -> logoUrl = reader.nextString()
                                            "channelCategoryId" -> catId = try { reader.nextString() } catch(e: Exception) { reader.nextInt().toString() }
                                            "channelLanguageId" -> langId = try { reader.nextString() } catch(e: Exception) { reader.nextInt().toString() }
                                            "isDrm" -> isDrm = try { reader.nextBoolean() } catch(e: Exception) { reader.nextString().toBoolean() }
                                            "isCatchupAvailable" -> isCatchup = try { reader.nextBoolean() } catch(e: Exception) { reader.nextString().toBoolean() }
                                            "stbChannelNumber" -> stb = try { reader.nextInt() } catch(e: Exception) { reader.nextString().toIntOrNull() ?: 0 }
                                            "is_premium" -> isPremium = try { reader.nextBoolean() } catch(e: Exception) { reader.nextString().toBoolean() }
                                            "plan_type" -> planType = try { reader.nextString() } catch(e: Exception) { reader.skipValue(); "" }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    if (channelId > 0 && !into.containsKey(channelId)) {
                                        into[channelId] = Channel(
                                            id = channelId.toString(),
                                            name = channelName.ifEmpty { "Unknown" },
                                            logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/$logoUrl",
                                            group = categoryMap[catId] ?: "Other",
                                            isDrm = isDrm,
                                            channelNumber = channelId,
                                            streamUrl = "",
                                            language = JIO_LANG_MAP[langId] ?: "Other",
                                            isCatchup = isCatchup,
                                            stbNumber = stb.coerceAtLeast(0),
                                            isPremium = isPremium,
                                            planType = planType
                                        )
                                    }
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                        reader.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch channels from $urlStr", e)
                }
            }

            // Jio's current list is v3.1; v1.4 only contributes the few allow-listed ids that still play
            // (see ChannelSources). Both lists download + parse IN PARALLEL, then merge with v3.1 winning.
            val v14 = mutableMapOf<Int, Channel>()
            val v31 = mutableMapOf<Int, Channel>()
            kotlinx.coroutines.coroutineScope {
                launch(Dispatchers.IO) { parseChannels(ChannelSources.V31_URL, v31) }
                launch(Dispatchers.IO) { parseChannels(ChannelSources.V14_URL, v14) }
            }
            finalChannelsMap.putAll(ChannelSources.merge(v31, v14))

            if (finalChannelsMap.isEmpty()) {
                // Network produced nothing — fall back to whatever we have on disk (even if stale)
                // so a transient outage doesn't wipe the channel list.
                readChannelCache(context)?.let {
                    Log.w(TAG, "Channel fetch empty; serving stale cache (${it.size} channels)")
                    return@withContext Result.success(it)
                }
                return@withContext Result.failure(Exception("Failed to fetch channels from endpoints"))
            }

            val finalChannels = finalChannelsMap.values.sortedBy { it.channelNumber }

            // Save to cache
            try {
                val jsonArray = org.json.JSONArray()
                finalChannels.forEach { ch ->
                    val obj = JSONObject()
                    obj.put("id", ch.id)
                    obj.put("name", ch.name)
                    obj.put("logoUrl", ch.logoUrl)
                    obj.put("group", ch.group)
                    obj.put("streamUrl", ch.streamUrl)
                    obj.put("isDrm", ch.isDrm)
                    obj.put("channelNumber", ch.channelNumber)
                    obj.put("language", ch.language)
                    obj.put("isCatchup", ch.isCatchup)
                    if (ch.stbNumber > 0) obj.put("stbNumber", ch.stbNumber)
                    if (ch.isPremium) obj.put("isPremium", true)
                    if (ch.planType.isNotEmpty()) obj.put("planType", ch.planType)
                    ch.licenseUrl?.let { obj.put("licenseUrl", it) }
                    jsonArray.put(obj)
                }
                cacheFile.writeText(jsonArray.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write cache", e)
            }

            Result.success(finalChannels)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getMobileChannelList", e)
            Result.failure(e)
        }
    }

    /**
     * Returns the value of the `__hdnea__` query parameter (the Akamai token) from a Jio stream URL,
     * or "" if absent. Jio appends this token last, so everything after `__hdnea__=` is the token.
     */
    fun extractHdneaToken(url: String): String {
        val marker = "__hdnea__="
        val i = url.indexOf(marker)
        return if (i >= 0) url.substring(i + marker.length) else ""
    }

    /** Parses the `exp=` epoch-seconds out of an `__hdnea__` token; 0 if not found. */
    fun extractTokenExpiryEpochSec(token: String): Long {
        val m = Regex("exp=(\\d+)").find(token) ?: return 0
        return m.groupValues[1].toLongOrNull() ?: 0
    }

    /**
     * The form body of a geturl request. Live: `stream_type=Live&channel_id=…`. Catch-up: the same
     * fields, in the same order and encoding, as the companion server (server/src/jio/stream.ts) and
     * the Kodi plugin send: srno, programId (the guide's showId), begin/end as epoch MILLISECONDS
     * (Jio echoes them into the catch-up URL), and the guide's showtime.
     */
    internal fun geturlBody(channelId: String, catchup: CatchupParams?): String {
        val enc = ::encodeUriComponent
        return if (catchup != null) {
            "stream_type=Catchup&channel_id=${enc(channelId)}&srno=${enc(catchup.srno)}" +
                "&programId=${enc(catchup.programId)}&begin=${catchup.beginMs}&end=${catchup.endMs}" +
                "&showtime=${enc(catchup.showtime)}"
        } else {
            "stream_type=Live&channel_id=${enc(channelId)}"
        }
    }

    /** JavaScript's encodeURIComponent (what the server uses), so both send byte-identical bodies. */
    internal fun encodeUriComponent(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    suspend fun getStreamUrl(
        context: android.content.Context,
        channelId: String,
        authData: AuthData,
        allowRefreshRetry: Boolean = true,
        catchup: CatchupParams? = null,
        // false = always ask geturl (token refresh loop, prefetch). A cached URL is taken (removed) on
        // use, so a load that fails with it retries against the network.
        useCache: Boolean = true
    ): Result<StreamData> = withContext(Dispatchers.IO) {
        try {
            // Use the newest stored credentials (refreshed ahead of expiry if needed); the caller's copy
            // may be stale if a refresh happened since it read them.
            val authData = if (allowRefreshRetry) (ensureFreshAccessToken(context) ?: authData) else authData
            if (catchup == null && useCache) {
                streamUrlCache.take(channelId, authData.authToken)?.let {
                    Log.d(TAG, "Stream URL for $channelId from prefetch")
                    return@withContext Result.success(it)
                }
            }
            ensureStreamModesLoaded(context)
            val url = URL("https://jiotvapi.media.jio.com/playback/apis/v1.1/geturl")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            // Construct Sony Headers exactly like the Kodi plugin
            connection.setRequestProperty("Host", HOST)
            connection.setRequestProperty("Appkey", "NzNiMDhlYzQyNjJm")
            connection.setRequestProperty("Devicetype", DEVICE_TYPE)
            connection.setRequestProperty("Os", OS)
            connection.setRequestProperty("Deviceid", authData.deviceId)
            connection.setRequestProperty("Osversion", "13")
            connection.setRequestProperty("Dm", "Google Pixel 5")
            connection.setRequestProperty("Uniqueid", authData.deviceId) // Kodi sets this to deviceId
            connection.setRequestProperty("Usergroup", "tvYR7NSNn7rymo3F")
            connection.setRequestProperty("Languageid", "6")
            connection.setRequestProperty("Userid", authData.userId)
            connection.setRequestProperty("Sid", "892898ba-f9de-4572-b6c2-e717b0ad")
            connection.setRequestProperty("Crmid", authData.crmid)
            connection.setRequestProperty("Isott", "false")
            connection.setRequestProperty("Channel_id", channelId)
            connection.setRequestProperty("Langid", "6")
            connection.setRequestProperty("ssoToken", authData.ssoToken)
            connection.setRequestProperty("Accesstoken", authData.authToken)
            connection.setRequestProperty("Subscriberid", authData.crmid)
            connection.setRequestProperty("analyticsId", authData.deviceId)
            connection.setRequestProperty("Lbcookie", "1")
            connection.setRequestProperty("Versioncode", "422")
            connection.setRequestProperty("user-agent", USER_AGENT)
            connection.setRequestProperty("Connection", "keep-alive")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true

            val body = geturlBody(channelId, catchup)
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            
            val tokenExpired = responseCode == 401 || responseCode == 419
            if (tokenExpired && allowRefreshRetry) {
                // Access token expired: refresh exactly once (allowRefreshRetry = false on the recursive
                // call). In server mode this re-pulls credentials from the proxy; in phone mode it uses
                // Jio's refreshtoken endpoint. A 403 is NOT a token problem (see below), so it never
                // triggers a refresh — every Zee press used to fire one, which wore the token out.
                Log.d(TAG, "Token expired ($responseCode), attempting refresh...")
                val refreshResult = refreshCredentials(context, failedAuthToken = authData.authToken)
                val mode = com.fenyx.jtv.data.SettingsManager(context).setupModeFlow.first()
                if (refreshResult.isSuccess) {
                    val newAuthData = com.fenyx.jtv.data.SettingsManager(context).authDataFlow.first()
                    if (newAuthData != null) {
                        return@withContext getStreamUrl(context, channelId, newAuthData, allowRefreshRetry = false, catchup = catchup)
                    }
                } else if (refreshResult.exceptionOrNull() is RefreshRejectedException) {
                    return@withContext Result.failure(SessionExpiredException(sessionExpiredMessage(mode)))
                }
            } else if (tokenExpired) {
                val mode = com.fenyx.jtv.data.SettingsManager(context).setupModeFlow.first()
                return@withContext Result.failure(SessionExpiredException(sessionExpiredMessage(mode)))
            }

            // 403 from geturl is per channel: Jio refuses this one (e.g. most Zee Entertainment
            // channels since Jio dropped them). No refresh or retry can fix it.
            if (responseCode == 403) {
                if (catchup == null) refusedInARow.add(channelId)
                if (catchup == null && refusedInARow.size >= REFUSED_CHANNELS_LIMIT) {
                    if (allowRefreshRetry) {
                        Log.d(TAG, "geturl 403 on ${refusedInARow.size} channels in a row, refreshing credentials once")
                        if (refreshCredentials(context, failedAuthToken = authData.authToken).isSuccess) {
                            val newAuthData = com.fenyx.jtv.data.SettingsManager(context).authDataFlow.first()
                            if (newAuthData != null) {
                                return@withContext getStreamUrl(context, channelId, newAuthData, allowRefreshRetry = false, catchup = null)
                            }
                        }
                    }
                    return@withContext Result.failure(AllChannelsRefusedException(
                        "Jio refused ${refusedInARow.size} different channels in a row (HTTP 403)."
                    ))
                }
                return@withContext Result.failure(ChannelBlockedException(
                    "Jio isn't providing this channel right now (it refused the stream request). " +
                    "This is on Jio's side, not a login problem. Try another channel."
                ))
            }

            if (responseCode in 200..299) {
                refusedInARow.clear()
                val responseText = readResponseBody(connection, isError = false)
                val json = JSONObject(responseText)

                val hlsUrl = json.optString("result", "")
                val mpdObj = json.optJSONObject("mpd")
                val mpdUrl = mpdObj?.optString("result", "").orEmpty()
                val mpdKey = mpdObj?.optString("key", "").orEmpty()

                // Prefer the Widevine DASH (best quality). But for some channels Jio returns a DASH URL
                // whose CDN path 404s while the plain HLS still plays, so on first live play check the
                // DASH manifest and fall back to HLS if it's dead. Catch-up keeps the old behaviour.
                val probeHeaders = { u: String ->
                    buildMap {
                        put("User-Agent", "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7")
                        if (u.contains("__hdnea__")) put("Cookie", "__hdnea__" + u.split("__hdnea__")[1])
                    }
                }
                val hlsUsable = hlsUrl.startsWith("http")
                var useMpd = mpdUrl.isNotEmpty()
                // Catch-up: the clear VOD HLS first, like the companion server (whose replay path is
                // the one known to work); the DASH only when there's no real HLS.
                if (catchup != null && hlsUsable && !hlsUrl.contains("paywall", ignoreCase = true)) useMpd = false
                if (catchup == null && useMpd) {
                    val mode = streamModeCache[channelId]
                    if (mode == "hls" && hlsUsable) {
                        useMpd = false
                    } else if (mode == null) {
                        // Check DASH and HLS at the SAME time (was one after the other): the first open
                        // of a channel waits for one round trip instead of two.
                        val (mpdAlive, hlsAlive) = kotlinx.coroutines.coroutineScope {
                            val m = async(Dispatchers.IO) { manifestAlive(mpdUrl, probeHeaders(mpdUrl)) }
                            val h = async(Dispatchers.IO) { hlsUsable && manifestAlive(hlsUrl, probeHeaders(hlsUrl)) }
                            m.await() to h.await()
                        }
                        if (mpdAlive) {
                            rememberStreamMode(context, channelId, "mpd")
                        } else if (hlsAlive) {
                            Log.i(TAG, "DASH is dead for $channelId, using HLS")
                            rememberStreamMode(context, channelId, "hls")
                            useMpd = false
                        } else {
                            return@withContext Result.failure(ChannelUnavailableException(
                                "This channel is offline on Jio's servers right now (its stream isn't there). " +
                                "It's a Jio-side problem, not your login. Try again later or pick another channel."
                            ))
                        }
                    }
                } else if (catchup == null && hlsUsable && streamModeCache[channelId] == null) {
                    if (!manifestAlive(hlsUrl, probeHeaders(hlsUrl))) {
                        return@withContext Result.failure(ChannelUnavailableException(
                            "This channel is offline on Jio's servers right now (its stream isn't there). " +
                            "It's a Jio-side problem, not your login. Try again later or pick another channel."
                        ))
                    }
                    rememberStreamMode(context, channelId, "hls")
                }

                val isMpd = useMpd
                val streamUrl = if (isMpd) mpdUrl else hlsUrl
                val licenseUrl = if (isMpd) mpdKey else ""

                // Extract cookie from response or headers
                var cookieStr = ""
                if (streamUrl.contains("__hdnea__")) {
                    cookieStr = "__hdnea__" + streamUrl.split("__hdnea__")[1]
                }
                
                // Build Widevine license headers
                val licenseHeaders = mutableMapOf<String, String>()
                licenseHeaders["User-Agent"] = "PlayTV/1.0"
                licenseHeaders["appName"] = APP_NAME
                licenseHeaders["x-platform"] = OS
                licenseHeaders["os"] = OS
                licenseHeaders["devicetype"] = DEVICE_TYPE
                licenseHeaders["osVersion"] = "13"
                licenseHeaders["srno"] = UUID.randomUUID().toString()
                licenseHeaders["channelid"] = channelId
                licenseHeaders["usergroup"] = "tvYR7NSNn7rymo3F"
                licenseHeaders["versionCode"] = "422"
                licenseHeaders["Accept-Encoding"] = "gzip, deflate"
                licenseHeaders["Content-Type"] = "application/octet-stream"
                licenseHeaders["Accept"] = "*/*"
                licenseHeaders["ssoToken"] = authData.ssoToken
                licenseHeaders["Accesstoken"] = authData.authToken
                licenseHeaders["userId"] = authData.userId
                licenseHeaders["uniqueId"] = authData.uniqueId
                licenseHeaders["crmid"] = authData.crmid
                licenseHeaders["deviceid"] = authData.deviceId
                
                if (cookieStr.isNotEmpty()) {
                    licenseHeaders["Cookie"] = cookieStr
                }

                // Build stream headers
                val streamHeaders = mutableMapOf<String, String>()
                streamHeaders["User-Agent"] = "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7"
                streamHeaders["ssoToken"] = authData.ssoToken
                streamHeaders["userId"] = authData.userId
                streamHeaders["uniqueId"] = authData.uniqueId
                streamHeaders["crmid"] = authData.crmid
                streamHeaders["deviceid"] = authData.deviceId
                streamHeaders["devicetype"] = DEVICE_TYPE
                streamHeaders["os"] = "B2G"
                streamHeaders["osversion"] = "2.5"
                streamHeaders["versioncode"] = "353"
                if (cookieStr.isNotEmpty()) {
                    streamHeaders["Cookie"] = cookieStr
                }

                Result.success(StreamData(
                    streamUrl = streamUrl,
                    licenseUrl = licenseUrl,
                    isMpd = isMpd,
                    headers = streamHeaders,
                    licenseHeaders = licenseHeaders
                ))
            } else {
                Log.e(TAG, "getStreamUrl failed: $responseCode")
                Result.failure(Exception("Failed to get stream URL: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getStreamUrl", e)
            Result.failure(e)
        }
    }
}
