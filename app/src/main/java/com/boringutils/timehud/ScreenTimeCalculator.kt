package com.boringutils.timehud

/** Streaming aggregation of chronological screen events within one usage day. */
internal class ScreenTimeCalculator(
    private val startMs: Long,
    private val endMs: Long
) {
    private var activeSince: Long? = null
    private var sawEvent = false
    private var durationMs = 0L

    fun record(timestampMs: Long, interactive: Boolean) {
        if (endMs <= startMs || timestampMs !in startMs..endMs) return
        if (!sawEvent && !interactive) activeSince = startMs
        sawEvent = true
        if (interactive) {
            // Duplicate screen-on events must not discard an open interval.
            if (activeSince == null) activeSince = timestampMs
        } else {
            activeSince?.let { durationMs += (timestampMs - it).coerceAtLeast(0L) }
            activeSince = null
        }
    }

    fun total(currentlyInteractive: Boolean): Long {
        if (endMs <= startMs) return 0L
        val openInterval = activeSince?.let { endMs - it } ?: 0L
        return if (!sawEvent && currentlyInteractive) endMs - startMs
        else (durationMs + openInterval).coerceIn(0L, endMs - startMs)
    }
}
