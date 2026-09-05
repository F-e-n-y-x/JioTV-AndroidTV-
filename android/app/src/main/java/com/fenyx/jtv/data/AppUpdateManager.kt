package com.fenyx.jtv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/F-e-n-y-x/JioTV-AndroidTV-/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val tagName: String,
        val changelog: String,
        val downloadUrl: String,
        val apkSize: Long,
        val isUpdateAvailable: Boolean
    )

    fun getCurrentVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    /**
     * Parses semantic version string (e.g., "1.5.3" or "v1.5.3") into a comparable integer list.
     */
    private fun parseVersion(v: String): List<Int> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        return clean.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseVersion(remoteVersion)
        val currentParts = parseVersion(currentVersion)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkForUpdate(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_LATEST_RELEASE_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "JTV-AndroidTV/${getCurrentVersionName(context)}")
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val tagName = json.optString("tag_name", "")
                val remoteVersionName = tagName.removePrefix("v").removePrefix("V")
                val changelog = json.optString("body", "No changelog provided.")

                var apkDownloadUrl = ""
                var apkSize = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (apkDownloadUrl.isEmpty()) {
                    Log.w(TAG, "No APK asset found in latest release $tagName")
                    return@withContext Result.success(null)
                }

                val currentVersion = getCurrentVersionName(context)
                val isAvailable = isNewerVersion(remoteVersionName, currentVersion)

                val updateInfo = UpdateInfo(
                    versionName = remoteVersionName,
                    tagName = tagName,
                    changelog = changelog,
                    downloadUrl = apkDownloadUrl,
                    apkSize = apkSize,
                    isUpdateAvailable = isAvailable
                )
                return@withContext Result.success(updateInfo)
            } else {
                Log.e(TAG, "Failed to check update: HTTP ${connection.responseCode}")
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode} while checking for update"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val targetFile = File(targetDir, "JTV_update.apk")
        if (targetFile.exists()) targetFile.delete()

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var redirectCount = 0
            var currentUrl = downloadUrl
            var conn: HttpURLConnection

            while (true) {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "JTV-AndroidTV/${getCurrentVersionName(context)}")

                val status = conn.responseCode
                if (status in 300..399) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl == null || redirectCount++ > 8) {
                        return@withContext Result.failure(Exception("Too many redirects downloading APK"))
                    }
                    currentUrl = newUrl
                } else if (status in 200..299) {
                    connection = conn
                    break
                } else {
                    return@withContext Result.failure(Exception("Failed to download APK: HTTP $status"))
                }
            }

            val totalBytes = connection.contentLength.toLong()
            inputStream = connection.inputStream
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(32 * 1024)
            var bytesDownloaded = 0L
            var read: Int

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesDownloaded += read
                val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                onProgress(bytesDownloaded, totalBytes, progress)
            }
            outputStream.flush()
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            if (targetFile.exists()) targetFile.delete()
            Result.failure(e)
        } finally {
            runCatching { outputStream?.close() }
            runCatching { inputStream?.close() }
            connection?.disconnect()
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) return Result.failure(Exception("APK file does not exist"))

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer", e)
            Result.failure(e)
        }
    }
}
