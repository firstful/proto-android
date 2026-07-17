package com.reedsutliff.talaria

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe ring buffer that captures the last N log entries in memory.
 *
 * Every call to [log] adds a timestamped entry. The oldest entry is dropped
 * when the buffer exceeds [maxEntries]. Used by [TalariaWebSocketClient.log]
 * and the "Share logs" feature to provide recent log context even without
 * ADB or logcat access.
 */
object LogBuffer {

    /** Maximum number of entries kept in memory. */
    private const val maxEntries = 500

    /** Entries backed by an indexed list for fast iteration. */
    private val entries = mutableListOf<String>()

    private val lock = Any()

    /**
     * Thread-safe append. Drops oldest if at capacity.
     */
    fun log(level: String, tag: String, msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            .format(Date())
        val entry = "$timestamp [$level] [$tag] $msg"

        synchronized(lock) {
            if (entries.size >= maxEntries) {
                entries.removeAt(0)
            }
            entries.add(entry)
        }
    }

    /**
     * Returns a snapshot of all buffered entries, oldest first.
     */
    fun snapshot(): List<String> = synchronized(lock) {
        entries.toList()
    }

    /**
     * Returns all entries joined by newline.
     */
    fun dump(): String = snapshot().joinToString("\n")

    /**
     * Clears the buffer.
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
