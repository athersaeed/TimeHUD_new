package com.boringutils.timehud.ui.brick

import org.junit.Assert.assertEquals
import org.junit.Test

class BrickModeScreenTest {
    private val apps = listOf(
        BrickModeAppUi(
            packageName = "com.android.settings",
            appName = "Settings",
            alwaysAvailable = true,
            allowed = true
        ),
        BrickModeAppUi(
            packageName = "com.spotify.music",
            appName = "Spotify",
            alwaysAvailable = false,
            allowed = false
        )
    )

    @Test
    fun blank_search_preserves_current_app_order() {
        assertEquals(apps, filterBrickModeApps(apps, "  "))
    }

    @Test
    fun search_matches_app_name_case_insensitively() {
        assertEquals(listOf(apps[1]), filterBrickModeApps(apps, "SPOT"))
    }

    @Test
    fun search_matches_package_name_case_insensitively() {
        assertEquals(listOf(apps[0]), filterBrickModeApps(apps, "ANDROID.SETTINGS"))
    }

    @Test
    fun search_returns_empty_when_no_app_matches() {
        assertEquals(emptyList<BrickModeAppUi>(), filterBrickModeApps(apps, "YouTube"))
    }

    @Test
    fun duration_accepts_minutes_within_the_supported_range() {
        assertEquals(90, parseBrickModeDurationMinutes(" 90 "))
        assertEquals(null, parseBrickModeDurationMinutes("0"))
        assertEquals(null, parseBrickModeDurationMinutes("10081"))
        assertEquals(null, parseBrickModeDurationMinutes("abc"))
    }

    @Test
    fun remaining_time_formats_days_hours_minutes_and_seconds() {
        assertEquals("0s", formatBrickModeRemaining(0L))
        assertEquals("1m 1s", formatBrickModeRemaining(60_001L))
        assertEquals("1h 1m 1s", formatBrickModeRemaining(3_660_001L))
        assertEquals("1d 1h 1m 1s", formatBrickModeRemaining(90_060_001L))
    }

    @Test
    fun schedule_start_accepts_24_hour_time_and_rejects_invalid_values() {
        assertEquals(9 * 60 + 30, parseBrickModeScheduleStart("09:30"))
        assertEquals(21 * 60, parseBrickModeScheduleStart("21:00"))
        assertEquals(null, parseBrickModeScheduleStart("24:00"))
        assertEquals(null, parseBrickModeScheduleStart("9:5"))
        assertEquals(null, parseBrickModeScheduleStart("morning"))
    }

    @Test
    fun schedule_duration_accepts_up_to_one_day() {
        assertEquals(90, parseBrickModeScheduleDuration("90"))
        assertEquals(24 * 60, parseBrickModeScheduleDuration("1440"))
        assertEquals(null, parseBrickModeScheduleDuration("0"))
        assertEquals(null, parseBrickModeScheduleDuration("1441"))
    }

    @Test
    fun schedule_duration_formats_hours_and_minutes() {
        assertEquals("45m", formatBrickModeScheduleDuration(45))
        assertEquals("2h", formatBrickModeScheduleDuration(120))
        assertEquals("2h 15m", formatBrickModeScheduleDuration(135))
    }
}
