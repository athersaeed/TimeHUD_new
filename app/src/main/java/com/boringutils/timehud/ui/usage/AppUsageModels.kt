package com.boringutils.timehud.ui.usage

import android.graphics.Bitmap
import java.util.Calendar
import java.util.TimeZone

internal data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val icon: Bitmap? = null
)

internal data class AppUsageSummary(
    val durationMs: Long,
    val appCount: Int
)

internal fun buildAppUsageSummary(
    screenTimeDurationMs: Long,
    entries: List<AppUsageEntry>
): AppUsageSummary = AppUsageSummary(
    durationMs = screenTimeDurationMs.coerceAtLeast(0L),
    appCount = entries.size
)

internal data class AppUsageEvent(
    val packageName: String,
    val timestampMs: Long,
    val type: AppUsageEventType
)

internal enum class AppUsageEventType {
    FOREGROUND,
    BACKGROUND,
    SCREEN_OFF
}

internal object AppUsageCalculator {
    fun calculateDurations(
        events: List<AppUsageEvent>,
        periodStartMs: Long,
        periodEndMs: Long
    ): Map<String, Long> {
        if (periodEndMs <= periodStartMs) return emptyMap()

        val durations = mutableMapOf<String, Long>()
        val activeSince = mutableMapOf<String, Long>()
        val seenPackages = mutableSetOf<String>()
        var canInferBoundaryUsage = true

        events.asSequence()
            .filter { it.timestampMs in periodStartMs..periodEndMs }
            .sortedBy { it.timestampMs }
            .forEach { event ->
                when (event.type) {
                    AppUsageEventType.SCREEN_OFF -> {
                        activeSince.forEach { (packageName, start) ->
                            durations[packageName] = durations.getOrDefault(packageName, 0L) +
                                (event.timestampMs - start).coerceAtLeast(0L)
                        }
                        activeSince.clear()
                        canInferBoundaryUsage = false
                    }
                    AppUsageEventType.FOREGROUND -> {
                        seenPackages += event.packageName
                        activeSince.putIfAbsent(event.packageName, event.timestampMs)
                    }

                    AppUsageEventType.BACKGROUND -> {
                        val activeStart = activeSince.remove(event.packageName)
                        val firstKnownEvent = seenPackages.add(event.packageName)
                        val intervalStart = activeStart ?: if (firstKnownEvent && canInferBoundaryUsage) periodStartMs else null
                        if (intervalStart != null && event.timestampMs > intervalStart) {
                            durations[event.packageName] =
                                durations.getOrDefault(event.packageName, 0L) +
                                    (event.timestampMs - intervalStart)
                        }
                    }
                }
            }

        activeSince.forEach { (packageName, intervalStart) ->
            if (periodEndMs > intervalStart) {
                durations[packageName] =
                    durations.getOrDefault(packageName, 0L) + (periodEndMs - intervalStart)
            }
        }

        return durations.filterValues { it > 0L }
    }
}

internal fun currentUsagePeriodStart(
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): Long = Calendar.getInstance(timeZone).run {
    timeInMillis = nowMs
    if (get(Calendar.HOUR_OF_DAY) < USAGE_DAY_START_HOUR) {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    set(Calendar.HOUR_OF_DAY, USAGE_DAY_START_HOUR)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

internal enum class AppUsagePeriod { TODAY, YESTERDAY, PAST_WEEK }

internal data class AppUsageRange(val startMs: Long, val endMs: Long)

internal fun appUsageRange(
    period: AppUsagePeriod,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): AppUsageRange {
    val todayStart = currentUsagePeriodStart(nowMs, timeZone)
    fun shift(start: Long, days: Int): Long = Calendar.getInstance(timeZone).run {
        timeInMillis = start
        add(Calendar.DAY_OF_YEAR, days)
        timeInMillis
    }
    return when (period) {
        AppUsagePeriod.TODAY -> AppUsageRange(todayStart, nowMs)
        AppUsagePeriod.YESTERDAY -> AppUsageRange(shift(todayStart, -1), todayStart)
        AppUsagePeriod.PAST_WEEK -> {
            // Android retains daily summaries longer than detailed events. Use
            // calendar days for this range instead of implying exact 3 AM slices.
            val midnight = Calendar.getInstance(timeZone).run {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
            AppUsageRange(shift(midnight, -6), nowMs)
        }
    }
}

internal fun formatAppUsageDuration(durationMs: Long): String {
    if (durationMs in 1 until MILLIS_PER_MINUTE) return "<1m"

    val totalMinutes = durationMs.coerceAtLeast(0L) / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
}

private const val USAGE_DAY_START_HOUR = 3
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
