package com.boringutils.timehud

import com.boringutils.timehud.ui.usage.AppUsageCalculator
import com.boringutils.timehud.ui.usage.AppUsageEntry
import com.boringutils.timehud.ui.usage.AppUsageEvent
import com.boringutils.timehud.ui.usage.AppUsageEventType
import com.boringutils.timehud.ui.usage.buildAppUsageSummary
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart
import com.boringutils.timehud.ui.usage.formatAppUsageDuration
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUsageCalculatorTest {
    @Test
    fun foreground_intervals_are_totaled_per_app() {
        val durations = AppUsageCalculator.calculateDurations(
            events = listOf(
                event("instagram", 1_000L, AppUsageEventType.FOREGROUND),
                event("instagram", 61_000L, AppUsageEventType.BACKGROUND),
                event("discord", 80_000L, AppUsageEventType.FOREGROUND),
                event("discord", 200_000L, AppUsageEventType.BACKGROUND),
                event("instagram", 220_000L, AppUsageEventType.FOREGROUND),
                event("instagram", 280_000L, AppUsageEventType.BACKGROUND)
            ),
            periodStartMs = 0L,
            periodEndMs = 300_000L
        )

        assertEquals(120_000L, durations["instagram"])
        assertEquals(120_000L, durations["discord"])
    }

    @Test
    fun first_background_event_counts_usage_from_period_boundary() {
        val durations = AppUsageCalculator.calculateDurations(
            events = listOf(event("whatsapp", 90_000L, AppUsageEventType.BACKGROUND)),
            periodStartMs = 30_000L,
            periodEndMs = 120_000L
        )

        assertEquals(60_000L, durations["whatsapp"])
    }

    @Test
    fun app_still_in_foreground_counts_until_now() {
        val durations = AppUsageCalculator.calculateDurations(
            events = listOf(event("discord", 40_000L, AppUsageEventType.FOREGROUND)),
            periodStartMs = 0L,
            periodEndMs = 100_000L
        )

        assertEquals(60_000L, durations["discord"])
    }

    @Test
    fun duplicate_background_event_is_not_double_counted() {
        val durations = AppUsageCalculator.calculateDurations(
            events = listOf(
                event("instagram", 60_000L, AppUsageEventType.BACKGROUND),
                event("instagram", 90_000L, AppUsageEventType.BACKGROUND)
            ),
            periodStartMs = 0L,
            periodEndMs = 100_000L
        )

        assertEquals(60_000L, durations["instagram"])
    }

    @Test
    fun dashboard_summary_uses_screen_time_instead_of_overlapping_app_sum() {
        val entries = listOf(
            AppUsageEntry("chatgpt", "ChatGPT", 4 * 60 * 60_000L),
            AppUsageEntry("firefox", "Firefox", 4 * 60 * 60_000L)
        )

        val summary = buildAppUsageSummary(
            screenTimeDurationMs = 7 * 60 * 60_000L,
            entries = entries
        )

        assertEquals(7 * 60 * 60_000L, summary.durationMs)
        assertEquals(2, summary.appCount)
    }

    @Test
    fun duration_uses_compact_hours_and_minutes() {
        assertEquals("6h21m", formatAppUsageDuration(22_860_000L))
        assertEquals("19m", formatAppUsageDuration(1_140_000L))
        assertEquals("<1m", formatAppUsageDuration(30_000L))
    }

    @Test
    fun period_before_three_am_starts_at_three_am_previous_day() {
        val timeZone = TimeZone.getTimeZone("America/Toronto")
        val now = Calendar.getInstance(timeZone).apply {
            set(2026, Calendar.SEPTEMBER, 1, 2, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val start = Calendar.getInstance(timeZone).apply {
            timeInMillis = currentUsagePeriodStart(now, timeZone)
        }

        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, start.get(Calendar.MONTH))
        assertEquals(31, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, start.get(Calendar.HOUR_OF_DAY))
    }

    private fun event(
        packageName: String,
        timestampMs: Long,
        type: AppUsageEventType
    ) = AppUsageEvent(packageName, timestampMs, type)
}
