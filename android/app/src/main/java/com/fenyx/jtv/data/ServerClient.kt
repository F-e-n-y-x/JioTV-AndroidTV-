package com.fenyx.jtv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the optional JTV companion/proxy server (see `server/`).
 *
 * In "Server mode" the app does not log in with an OTP itself; instead it pulls the shared, centrally
 * refreshed [JioApiClient.AuthData] from the server's `GET /api/credentials` endpoint (protected by a
 * bearer access token). Everything downstream — stream resolution, DRM, token refresh — is unchanged,
 * because the app still receives the same AuthData it would have gotten from a direct OTP login.
 */
object ServerClient {
    private const val TAG = "ServerClient"

    /**
     * The official "JTV Server" endpoints, tried in order: the LAN address first (fast when the TV is on
     * the home network) then the public HTTPS address (works from anywhere). Used by "jtv" setup mode so
     * the user only enters a code — no URL typing. People running their own box still use the manual
     * URL + code path ("server" mode).
     */
    val JTV_SERVER_URLS: List<String> = listOf(
        "http://192.168.10.10:8080",
        "http://192.168.10.10:5001",
        "https://jtv.ayushsoni.eu.org"
    )

    /** Trims whitespace and any trailing slash so we can safely append paths. */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    /** Base URLs to try for the current sign-in mode: the hardcoded JTV list for "jtv", the user's
     *  saved URL for a self-hosted "server", empty for phone/none. */
    fun candidateUrls(setupMode: String?, savedServerUrl: String): List<String> = when (setupMode) {
        "jtv" -> JTV_SERVER_URLS
        "server" -> listOf(savedServerUrl)
        else -> emptyList()
    }

    /** Fetches credentials trying several base URLs in order; returns the first success. */
    suspend fun fetchCredentials(urls: List<String>, token: String): Result<JioApiClient.AuthData> =
        firstSuccess(urls) { fetchCredentials(it, token) }

    /** Forces a refresh trying several base URLs in order; returns the first success. */
    suspend fun refreshCredentials(urls: List<String>, token: String): Result<JioApiClient.AuthData> =
        firstSuccess(urls) { refreshCredentials(it, token) }

    private suspend fun firstSuccess(
        urls: List<String>,
        op: suspend (String) -> Result<JioApiClient.AuthData>
    ): Result<JioApiClient.AuthData> {
        var last: Result<JioApiClient.AuthData>? = null
        for (u in urls) {
            if (u.isBlank()) continue
            val r = op(u)
            if (r.isSuccess) return r
            last = r
        }
        return last ?: Result.failure(Exception("No server URL configured"))
    }

    /**
     * Fetches the shared credentials from the server (GET /api/credentials). Used at setup and by the
     * TV's background credential sync.
     * @param baseUrl e.g. "http://192.168.1.10:8080" (or an https URL)
     * @param token   the server access token / code
     */
    suspend fun fetchCredentials(baseUrl: String, token: String): Result<JioApiClient.AuthData> =
        requestCredentials(baseUrl, token, "GET", "/api/credentials")

    /**
     * Forces the server to refresh the Jio token and returns the fresh credentials (POST /api/refresh).
     * Falls back to a plain GET /api/credentials if the server is older and doesn't have /api/refresh
     * (the server already auto-refreshes every 6h, so a plain pull is still an improvement).
     */
    suspend fun refreshCredentials(baseUrl: String, token: String): Result<JioApiClient.AuthData> {
        val res = requestCredentials(baseUrl, token, "POST", "/api/refresh")
        return if (res.isFailure && (res.exceptionOrNull()?.message?.contains("404") == true)) {
            fetchCredentials(baseUrl, token)
        } else res
    }

    private suspend fun requestCredentials(
        baseUrl: String, token: String, method: String, path: String
    ): Result<JioApiClient.AuthData> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${normalizeBaseUrl(baseUrl)}$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                // Short CONNECT timeout so an unreachable candidate (e.g. the JTV LAN box when the TV is
                // on a different subnet) fails fast and we fall through to the next URL, instead of
                // stalling ~10s per credential fetch. Read timeout stays generous for slow responses.
                connectTimeout = 4000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (method == "POST") { doOutput = true; outputStream.close() }
            }
            val code = conn.responseCode
            val isGzip = "gzip".equals(conn.contentEncoding, ignoreCase = true)
            if (code !in 200..299) {
                val rawErrStream = conn.errorStream ?: conn.inputStream
                val errStream = if (isGzip && rawErrStream != null) java.util.zip.GZIPInputStream(rawErrStream) else rawErrStream
                val err = errStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "$method $path failed: $code $err")
                return@withContext Result.failure(
                    Exception(
                        when (code) {
                            401, 403 -> "Access denied — check the server access token."
                            404 -> "Server endpoint not found (404). Update the server."
                            else -> "Server returned HTTP $code."
                        }
                    )
                )
            }
            val rawInputStream = conn.inputStream
            val inputStream = if (isGzip && rawInputStream != null) java.util.zip.GZIPInputStream(rawInputStream) else rawInputStream
            val json = JSONObject(inputStream?.bufferedReader()?.use { it.readText() } ?: "{}")
            val ssoToken = json.optString("ssoToken", "")
            if (ssoToken.isEmpty()) {
                return@withContext Result.failure(Exception("Server has no active login yet. Sign in on the server first."))
            }
            Result.success(
                JioApiClient.AuthData(
                    ssoToken = ssoToken,
                    authToken = json.optString("authToken", ""),
                    crmid = json.optString("crmid", ""),
                    uniqueId = json.optString("uniqueId", ""),
                    deviceId = json.optString("deviceId", ""),
                    userId = json.optString("userId", "")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception in $method $path", e)
            Result.failure(Exception("Couldn't reach the server. Check the URL and network."))
        }
    }
}
