package com.boringutils.timehud.blocking

/** Coalesces events without letting a busy video/feed postpone evaluation forever. */
internal class WindowEvaluationSchedule {
    private var deadlineMs: Long? = null

    fun request(nowUptimeMs: Long, delayMs: Long): Boolean {
        val requestedDeadline = nowUptimeMs + delayMs.coerceAtLeast(0L)
        if (deadlineMs?.let { it <= requestedDeadline } == true) return false
        deadlineMs = requestedDeadline
        return true
    }

    fun clear() {
        deadlineMs = null
    }
}
