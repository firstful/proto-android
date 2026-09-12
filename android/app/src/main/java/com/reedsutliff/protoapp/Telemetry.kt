package com.reedsutliff.protoapp

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fire-and-forget telemetry to the public ingest (no auth, 60/min per IP cap).
 * Never affects app behavior: errors are logged + swallowed.
 */
object Telemetry {
    private const val TAG = "ProtoTelemetry"
    private val lastSend = AtomicLong(0)

    fun send(body: String) {
        if (BuildConfig.TELEMETRY_URL.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastSend.get() < 2000) return  // 2s throttle, stays under cap
        lastSend.set(now)
        Thread {
            try {
                val conn = URL(BuildConfig.TELEMETRY_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.setRequestProperty("User-Agent", "proto-android/${BuildConfig.VERSION_NAME}")
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode  // fire
            } catch (e: Exception) {
                Log.d(TAG, "telemetry skipped: ${e.message}")
            }
        }.start()
    }
}
