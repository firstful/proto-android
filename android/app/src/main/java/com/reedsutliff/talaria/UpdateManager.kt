package com.reedsutliff.talaria

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.reedsutliff.talaria.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of an update check against install.ffilt.us.
 *
 * @property versionCode The server-side version code (integer, from X-Update-Version).
 * @property versionName Human-readable version label.
 * @property contentLength Size of the APK in bytes, or -1 if unknown.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val contentLength: Long,
)

/**
 * Manages OTA self-updates for Talaria via install.ffilt.us.
 *
 * Flow:
 *   1. [checkForUpdate] — GET /update?app=...&ver=... → null (up to date) or [UpdateInfo].
 *   2. [downloadApk]    — fetches the APK to cacheDir.
 *   3. [installApk]     — launches system package installer via FileProvider.
 *
 * The UI layer decides when to call each step, showing a modal prompt
 * between check and download so the user explicitly opts in.
 */
object UpdateManager {

    private const val UPDATE_URL = "https://install.ffilt.us/update"
    private const val APP_ID = "com.reedsutliff.talaria"
    private const val NOTIFICATION_CHANNEL = "talaria_updates"
    private const val NOTIFICATION_ID = 901

    /**
     * Check whether a newer version is available on the install server.
     *
     * @return [UpdateInfo] if a newer version exists, or `null` if up to date.
     */
    suspend fun checkForUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        cfClientId: String = BuildConfig.CF_ACCESS_CLIENT_ID,
        cfClientSecret: String = BuildConfig.CF_ACCESS_CLIENT_SECRET,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            // Warm CF Access cookie jar — prevents 302 rejection on cold start
            if (cfClientId.isNotBlank()) {
                try {
                    val preAuthUrl = URL("$UPDATE_URL?app=$APP_ID&ver=$currentVersionCode")
                    val preConn = preAuthUrl.openConnection() as HttpURLConnection
                    preConn.connectTimeout = 5_000
                    preConn.readTimeout = 5_000
                    preConn.setRequestProperty("CF-Access-Client-Id", cfClientId)
                    preConn.setRequestProperty("CF-Access-Client-Secret", cfClientSecret)
                    // FOLLOW redirect so CF sets the CF_AppSession cookie
                    preConn.instanceFollowRedirects = true
                    try { preConn.inputStream.close() } catch (_: Exception) {}
                    preConn.disconnect()
                } catch (_: Exception) { /* pre-auth is best-effort */ }
            }

            val url = URL("$UPDATE_URL?app=$APP_ID&ver=$currentVersionCode")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false

            if (cfClientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", cfClientId)
            }
            if (cfClientSecret.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Secret", cfClientSecret)
            }

            val result = when (conn.responseCode) {
                202 -> {
                    LogBuffer.log("I", "UpdateManager",
                        "Up to date ($currentVersionCode)")
                    null
                }
                200 -> {
                    val versionStr = conn.getHeaderField("X-Update-Version")
                    val versionCode = versionStr?.toIntOrNull() ?: (currentVersionCode + 1)
                    val contentLength = conn.contentLength.toLong()
                    LogBuffer.log("I", "UpdateManager",
                        "Update available: v$currentVersionCode → v$versionCode ($contentLength bytes)")
                    UpdateInfo(versionCode, versionStr ?: "v$versionCode", contentLength)
                }
                else -> {
                    LogBuffer.log("W", "UpdateManager",
                        "Unexpected response: ${conn.responseCode} ${conn.responseMessage}")
                    null
                }
            }
            conn.disconnect()
            result
        } catch (e: Exception) {
            LogBuffer.log("E", "UpdateManager",
                "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download the latest APK to [context.cacheDir]/talaria-update.apk.
     *
     * @return The downloaded [File], ready for [installApk].
     */
    suspend fun downloadApk(
        context: Context,
        cfClientId: String = BuildConfig.CF_ACCESS_CLIENT_ID,
        cfClientSecret: String = BuildConfig.CF_ACCESS_CLIENT_SECRET,
    ): File = withContext(Dispatchers.IO) {
        val url = URL("$UPDATE_URL?app=$APP_ID&ver=0")  // ver=0 always triggers 200
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = false

        if (cfClientId.isNotBlank()) {
            conn.setRequestProperty("CF-Access-Client-Id", cfClientId)
        }
        if (cfClientSecret.isNotBlank()) {
            conn.setRequestProperty("CF-Access-Client-Secret", cfClientSecret)
        }

        val contentLength = conn.contentLength
        LogBuffer.log("I", "UpdateManager",
            "Downloading APK ($contentLength bytes)")

        val apkFile = File(context.cacheDir, "talaria-update.apk")
        conn.inputStream.use { input ->
            FileOutputStream(apkFile).use { output ->
                val buf = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                }
            }
        }
        conn.disconnect()

        LogBuffer.log("I", "UpdateManager",
            "Downloaded ${apkFile.length()} bytes to ${apkFile.absolutePath}")
        apkFile
    }

    /**
     * Launch the system package installer for the given APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LogBuffer.log("I", "UpdateManager", "Installer launched")
        } catch (e: Exception) {
            LogBuffer.log("E", "UpdateManager",
                "Failed to launch installer: ${e.message}")
        }
    }
}
