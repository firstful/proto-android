package com.reedsutliff.talaria

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.reedsutliff.talaria.BuildConfig
import com.reedsutliff.talaria.LogBuffer
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Application class for Talaria.
 *
 * Installs an uncaught exception handler that persists crash stack traces
 * to [filesDir]/crashes/ before the process dies. These crash files can
 * then be shared via the in-app "Share logs" feature.
 *
 * Requires: android:name=".TalariaApplication" in AndroidManifest.xml
 */
class TalariaApplication : Application() {

    companion object {
        private const val TAG = "TalariaApp"
        private const val CRASH_DIR = "crashes"
        private const val MAX_CRASH_FILES = 10
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TalariaApplication.onCreate()")
        LogBuffer.log("I", TAG, "TalariaApplication.onCreate()")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashReport(throwable, thread)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash report: ${e.message}")
                LogBuffer.log("E", TAG, "Failed to save crash report: ${e.message}")
            }
            // Still let the default handler run so Android shows the crash dialog
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "Crash handler installed ✓")
        LogBuffer.log("I", TAG, "Crash handler installed ✓")
        // Update check is now handled by TalariaViewModel with a user-facing
        // modal dialog — no auto-download in the background.
    }

    /**
     * Writes a crash report to [filesDir]/crashes/.
     * Each file is named crash-YYYYMMDD-HHmmss.txt.
     * Oldest files are pruned when MAX_CRASH_FILES is exceeded.
     */
    private fun saveCrashReport(throwable: Throwable, thread: Thread) {
        val crashDir = File(filesDir, CRASH_DIR)
        if (!crashDir.exists()) crashDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date())
        val crashFile = File(crashDir, "crash-$timestamp.txt")

        FileWriter(crashFile).use { fw ->
            PrintWriter(fw).use { pw ->
                pw.println("=== Talaria Crash Report ===")
                pw.println("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                pw.println("Thread: ${thread.name} (id=${thread.id})")
                pw.println("Thread group: ${thread.threadGroup?.name}")
                pw.println("Priority: ${thread.priority}")
                pw.println()
                pw.println("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println("SDK: ${android.os.Build.VERSION.SDK_INT}")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                pw.println("--- Stack Trace ---")
                throwable.printStackTrace(pw)

                // Also print cause chain
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 10) {
                    pw.println()
                    pw.println("--- Caused by (depth=$depth) ---")
                    cause.printStackTrace(pw)
                    cause = cause.cause
                    depth++
                }

                // Suppressed exceptions
                val suppressed = throwable.suppressed
                if (suppressed.isNotEmpty()) {
                    pw.println()
                    pw.println("--- Suppressed (${suppressed.size}) ---")
                    suppressed.forEach { it.printStackTrace(pw) }
                }
            }
        }

        Log.i(TAG, "Crash report saved: ${crashFile.absolutePath}")

        // Prune old crash files
        val files = crashDir.listFiles()?.filter { it.name.startsWith("crash-") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.size > MAX_CRASH_FILES) {
            val toDelete = files.size - MAX_CRASH_FILES
            files.take(toDelete).forEach { file ->
                file.delete()
                Log.d(TAG, "Pruned old crash file: ${file.name}")
            }
        }
    }

    /**
     * Returns the list of crash report files, most recent first.
     */
    fun getCrashFiles(): List<File> {
        val crashDir = File(filesDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles()
            ?.filter { it.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Generates a combined log report string containing:
     * 1. Recent in-memory ring buffer entries (from [LogBuffer])
     * 2. All saved crash report files
     *
     * The report begins with a header showing app version and device info.
     */
    fun generateCombinedLogReport(): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("=== Talaria Log Report ===")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine()

        // In-memory ring buffer
        sb.appendLine("━━━ Recent Logs ━━━")
        sb.appendLine(LogBuffer.dump())
        sb.appendLine()

        // Crash files
        val crashFiles = getCrashFiles()
        if (crashFiles.isEmpty()) {
            sb.appendLine("━━━ Crash Reports ━━━")
            sb.appendLine("(no crashes recorded)")
        } else {
            val currentBuild = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            for (crashFile in crashFiles) {
                try {
                    val crashContent = crashFile.readText()
                    // Extract build info from crash file to detect stale crashes
                    val buildMatch = Regex("Build: (.+)").find(crashContent)
                    val crashBuild = buildMatch?.groupValues?.get(1) ?: "unknown"
                    val isOldBuild = crashBuild != currentBuild
                    sb.appendLine("━━━ ${crashFile.name} ━━━")
                    if (isOldBuild) {
                        sb.appendLine("⚠️ OLD BUILD — crash: $crashBuild | current: $currentBuild")
                    }
                    sb.append(crashContent)
                } catch (e: Exception) {
                    sb.appendLine("━━━ ${crashFile.name} ━━━")
                    sb.appendLine("[Error reading crash file: ${e.message}]")
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    /**
     * Writes the combined log report to a temp file in cacheDir and
     * launches an ACTION_SEND intent via FileProvider so the user can
     * share it via Telegram, email, etc.
     *
     * @param context Any context (activity, service, etc.) used to start the intent.
     */
    fun shareLogs(context: Context) {
        val report = generateCombinedLogReport()

        // Write to a temp file in the cache directory
        val cacheDir = File(cacheDir, "log_reports")
        cacheDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val reportFile = File(cacheDir, "talaria-log-$timestamp.txt")
        reportFile.writeText(report)

        // Create content URI via FileProvider (declared in manifest)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile,
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Talaria Log Report ($timestamp)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Share Talaria Logs")
        )
    }
}
