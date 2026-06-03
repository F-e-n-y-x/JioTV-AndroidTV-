package com.fenyx.jtv.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.first

object JioApiClient {
    private const val TAG = "JioApiClient"

    // Default Headers
    private const val USER_AGENT = "okhttp/4.2.2"
    private const val APP_NAME = "RJIL_JioTV"
    private const val OS = "android"
    private const val DEVICE_TYPE = "phone"
    private const val HOST = "jiotvapi.media.jio.com"

    data class AuthData(
        val ssoToken: String,
        val authToken: String,
        val crmid: String,
        val uniqueId: String,
        val deviceId: String,
        val userId: String
    )

    data class StreamData(
        val streamUrl: String,
        val licenseUrl: String,
        val isMpd: Boolean,
        val headers: Map<String, String>,
        val licenseHeaders: Map<String, String>
    )

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
            if (responseCode == 204) {
                Result.success(Unit)
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorText = errorStream.bufferedReader().use { it.readText() }
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
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                
                if (json.has("ssoToken")) {
                    val sessionObj = json.optJSONObject("sessionAttributes")?.optJSONObject("user")
                    val authData = AuthData(
                        ssoToken = json.optString("ssoToken", ""),
                        authToken = json.optString("authToken", ""),
                        crmid = sessionObj?.optString("subscriberId", "") ?: "",
                        uniqueId = sessionObj?.optString("unique", "") ?: "",
                        deviceId = json.optString("deviceId", ""),
                        userId = sessionObj?.optString("uid", "") ?: ""
                    )
                    Result.success(authData)
                } else {
                    Result.failure(Exception(json.optString("message", "Unknown error in OTP verification")))
                }
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorText = errorStream.bufferedReader().use { it.readText() }
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

            val url = URL("https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("ssotoken", authData.ssoToken)
            connection.setRequestProperty("appname", APP_NAME)
            connection.setRequestProperty("os", OS)
            connection.setRequestProperty("devicetype", DEVICE_TYPE)
            connection.doOutput = true
            
            if (connection.responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                if (json.has("ssoToken")) {
                    val newSsoToken = json.optString("ssoToken", "")
                    settingsManager.saveAuthData(authData.copy(ssoToken = newSsoToken))
                    Log.d(TAG, "Token refreshed successfully")
                    return@withContext Result.success(true)
                }
            }
            Result.failure(Exception("Refresh failed with code ${connection.responseCode}"))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in refreshToken", e)
            Result.failure(e)
        }
    }

    suspend fun getMobileChannelList(context: android.content.Context): Result<List<Channel>> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = context.getExternalFilesDir(null) ?: context.filesDir
            val cacheFile = java.io.File(cacheDir, "channels_cache.json")
            val cacheValidTime = 24 * 60 * 60 * 1000L // 24 hours
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < cacheValidTime) {
                try {
                    val cacheContent = cacheFile.readText()
                    val jsonArray = org.json.JSONArray(cacheContent)
                    val channels = mutableListOf<Channel>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        channels.add(Channel(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            logoUrl = obj.getString("logoUrl"),
                            group = obj.getString("group"),
                            streamUrl = obj.getString("streamUrl"),
                            isDrm = obj.getBoolean("isDrm"),
                            channelNumber = obj.getInt("channelNumber"),
                            licenseUrl = if (obj.has("licenseUrl")) obj.getString("licenseUrl") else null
                        ))
                    }
                    if (channels.isNotEmpty()) {
                        return@withContext Result.success(channels)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read cache", e)
                }
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

            fun parseChannels(urlStr: String) {
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
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "channel_id" -> channelId = try { reader.nextInt() } catch(e: Exception) { reader.nextString().toIntOrNull() ?: 0 }
                                            "channel_name" -> channelName = reader.nextString()
                                            "logoUrl" -> logoUrl = reader.nextString()
                                            "channelCategoryId" -> catId = try { reader.nextString() } catch(e: Exception) { reader.nextInt().toString() }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    if (channelId > 0 && !finalChannelsMap.containsKey(channelId)) {
                                        finalChannelsMap[channelId] = Channel(
                                            id = channelId.toString(),
                                            name = channelName.ifEmpty { "Unknown" },
                                            logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/$logoUrl",
                                            group = categoryMap[catId] ?: "Other",
                                            isDrm = true,
                                            channelNumber = channelId,
                                            streamUrl = ""
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

            // Fetch v1.4 (Sony/Zee) and v3.1 (Star/Disney)
            parseChannels("https://jiotvapi.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?langId=6&devicetype=phone&os=android&usertype=JIO&version=396")
            parseChannels("https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=389")

            if (finalChannelsMap.isEmpty()) {
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

    suspend fun getStreamUrl(context: android.content.Context, channelId: String, authData: AuthData): Result<StreamData> = withContext(Dispatchers.IO) {
        try {
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
            connection.setRequestProperty("Versioncode", "389")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br")
            connection.setRequestProperty("user-agent", "okhttp/4.2.2")
            connection.setRequestProperty("Connection", "keep-alive")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true

            val body = "stream_type=Live&channel_id=$channelId"
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            
            if (responseCode == 401 || responseCode == 403 || responseCode == 419) {
                // Token might be expired, try to refresh once
                Log.d(TAG, "Token expired, attempting refresh...")
                val refreshResult = refreshToken(context)
                if (refreshResult.isSuccess) {
                    val settingsManager = com.fenyx.jtv.data.SettingsManager(context)
                    val newAuthData = settingsManager.authDataFlow.first()
                    if (newAuthData != null) {
                        return@withContext getStreamUrl(context, channelId, newAuthData)
                    }
                }
            }

            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                
                var streamUrl = json.optString("result", "")
                val mpdObj = json.optJSONObject("mpd")
                val isMpd = mpdObj != null && mpdObj.has("result")
                var licenseUrl = ""
                
                if (isMpd) {
                    streamUrl = mpdObj.optString("result", "")
                    licenseUrl = mpdObj.optString("key", "")
                }
                
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
                licenseHeaders["versionCode"] = "389"
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
                streamHeaders["User-Agent"] = "plaYtv/7.1.5 (Linux;Android 9) ExoPlayerLib/2.11.7"
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
