package com.boringutils.timehud

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart

internal object ScreenTimeDisplay {
    fun current(context: Context): String = format(queryMs(context))

    fun format(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000
        return "${totalMinutes / 60}h${totalMinutes % 60}m"
    }

    // Provider access must run on a worker dispatcher, never while attaching a window.
    fun queryMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: throw IllegalStateException("Usage service unavailable")
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: throw IllegalStateException("Power service unavailable")
        val start = currentUsagePeriodStart(nowMs)
        val events = usage.queryEvents(start, nowMs)
            ?: throw IllegalStateException("Usage history unavailable")
        val calculator = ScreenTimeCalculator(start, nowMs)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // These event IDs exist on older Android releases too; their public
            // named constants were only introduced in API 28.
            when (event.eventType) {
                15 -> calculator.record(event.timeStamp, interactive = true)
                16 -> calculator.record(event.timeStamp, interactive = false)
            }
        }
        return calculator.total(power.isInteractive)
    }
}
