package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BrickModeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val catalog = BrickModeCatalog(
        apps = listOf(
            BrickModeApp("com.android.settings", "Settings", alwaysAvailable = true),
            BrickModeApp("com.example.maps", "Maps", alwaysAvailable = false),
            BrickModeApp("com.example.social", "Social", alwaysAvailable = false)
        )
    )

    @Test
    fun disabled_mode_allows_every_app() {
        assertEquals(
            BlockDecision.Allow,
            BrickModeDecisionEngine.decide(
                BrickModeConfig(enabled = false),
                "com.example.social",
                catalog
            )
        )
    }

    @Test
    fun active_timer_blocks_an_unchosen_app_before_its_deadline() {
        assertEquals(
            BlockDecision.Block(BlockReason.BRICK_MODE),
            BrickModeDecisionEngine.decide(
                config = BrickModeConfig(enabled = true, endsAtEpochMs = 120_000L),
                packageName = "com.example.social",
                catalog = catalog,
                nowMs = 60_000L
            )
        )
    }

    @Test
    fun expired_timer_allows_an_unchosen_app() {
        assertEquals(
            BlockDecision.Allow,
            BrickModeDecisionEngine.decide(
                config = BrickModeConfig(enabled = true, endsAtEpochMs = 120_000L),
                packageName = "com.example.social",
                catalog = catalog,
                nowMs = 120_000L
            )
        )
    }

    @Test
    fun active_weekly_schedule_blocks_when_manual_mode_is_off() {
        val schedule = schedule(
            daysOfWeek = setOf(Calendar.MONDAY),
            startMinuteOfDay = 9 * 60,
            durationMinutes = 120
        )

        assertEquals(
            BlockDecision.Block(BlockReason.BRICK_MODE),
            BrickModeDecisionEngine.decide(
                config = BrickModeConfig(enabled = false),
                packageName = "com.example.social",
                catalog = catalog,
                scheduledActive = BrickModeSchedulePolicy.isAnyActive(
                    listOf(schedule),
                    utcMillis(2026, Calendar.SEPTEMBER, 7, 10, 0),
                    utc
                ),
                nowMs = utcMillis(2026, Calendar.SEPTEMBER, 7, 10, 0)
            )
        )
    }

    @Test
    fun weekly_schedule_is_inactive_at_its_end_boundary() {
        val schedule = schedule(
            daysOfWeek = setOf(Calendar.MONDAY),
            startMinuteOfDay = 9 * 60,
            durationMinutes = 120
        )

        assertTrue(
            BrickModeSchedulePolicy.isActive(
                schedule,
                utcMillis(2026, Calendar.SEPTEMBER, 7, 10, 59),
                utc
            )
        )
        assertTrue(
            !BrickModeSchedulePolicy.isActive(
                schedule,
                utcMillis(2026, Calendar.SEPTEMBER, 7, 11, 0),
                utc
            )
        )
    }

    @Test
    fun weekly_schedule_can_cross_midnight() {
        val schedule = schedule(
            daysOfWeek = setOf(Calendar.FRIDAY),
            startMinuteOfDay = 23 * 60 + 30,
            durationMinutes = 120
        )

        assertTrue(
            BrickModeSchedulePolicy.isActive(
                schedule,
                utcMillis(2026, Calendar.SEPTEMBER, 5, 0, 30),
                utc
            )
        )
    }

    @Test
    fun next_schedule_boundary_returns_the_next_start_or_end() {
        val schedule = schedule(
            daysOfWeek = setOf(Calendar.MONDAY),
            startMinuteOfDay = 9 * 60,
            durationMinutes = 120
        )

        assertEquals(
            utcMillis(2026, Calendar.SEPTEMBER, 7, 9, 0),
            BrickModeSchedulePolicy.nextBoundaryEpochMs(
                listOf(schedule),
                utcMillis(2026, Calendar.SEPTEMBER, 7, 8, 0),
                utc
            )
        )
        assertEquals(
            utcMillis(2026, Calendar.SEPTEMBER, 7, 11, 0),
            BrickModeSchedulePolicy.nextBoundaryEpochMs(
                listOf(schedule),
                utcMillis(2026, Calendar.SEPTEMBER, 7, 10, 0),
                utc
            )
        )
    }

    @Test
    fun schedule_codec_round_trips_valid_entries_and_skips_invalid_entries() {
        val schedules = listOf(
            schedule(
                id = "weekday-focus",
                daysOfWeek = setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
                startMinuteOfDay = 13 * 60 + 15,
                durationMinutes = 90
            )
        )

        assertEquals(schedules, BrickModeScheduleCodec.decode(BrickModeScheduleCodec.encode(schedules)))
        assertEquals(emptyList<BrickModeSchedule>(), BrickModeScheduleCodec.decode("broken|data"))
    }

    @Test
    fun timer_deadline_accepts_valid_duration_and_rejects_out_of_range_values() {
        assertEquals(3_660_000L, BrickModeTimer.endTimeEpochMs(60_000L, 60))
        assertEquals(null, BrickModeTimer.endTimeEpochMs(60_000L, 0))
        assertEquals(
            null,
            BrickModeTimer.endTimeEpochMs(60_000L, BrickModeTimer.MAX_DURATION_MINUTES + 1)
        )
    }

    @Test
    fun expired_timer_resolves_to_disabled_without_changing_allowed_apps() {
        val config = BrickModeConfig(
            enabled = true,
            allowedPackages = setOf("com.example.maps"),
            endsAtEpochMs = 120_000L
        )

        assertEquals(
            BrickModeConfig(
                enabled = false,
                allowedPackages = setOf("com.example.maps"),
                endsAtEpochMs = null
            ),
            BrickModeTimer.resolveExpired(config, nowMs = 120_000L)
        )
    }

    @Test
    fun background_only_package_is_never_blocked() {
        assertEquals(
            BlockDecision.Allow,
            BrickModeDecisionEngine.decide(
                BrickModeConfig(enabled = true),
                "com.example.background.service",
                catalog
            )
        )
    }

    @Test
    fun essential_app_is_always_allowed() {
        assertEquals(
            BlockDecision.Allow,
            BrickModeDecisionEngine.decide(
                BrickModeConfig(enabled = true),
                "com.android.settings",
                catalog
            )
        )
    }

    @Test
    fun chosen_app_is_allowed() {
        assertEquals(
            BlockDecision.Allow,
            BrickModeDecisionEngine.decide(
                BrickModeConfig(
                    enabled = true,
                    allowedPackages = setOf("com.example.maps")
                ),
                "com.example.maps",
                catalog
            )
        )
    }

    @Test
    fun unchosen_launchable_app_is_blocked() {
        assertEquals(
            BlockDecision.Block(BlockReason.BRICK_MODE),
            BrickModeDecisionEngine.decide(
                BrickModeConfig(enabled = true),
                "com.example.social",
                catalog
            )
        )
    }

    @Test
    fun brick_mode_block_takes_priority_over_an_app_limit_allow() {
        val brickDecision = BlockDecision.Block(BlockReason.BRICK_MODE)
        assertEquals(
            brickDecision,
            AppControlDecisionEngine.decide(
                brickModeDecision = brickDecision,
                appBlockRule = AppBlockRule(
                    packageName = "com.example.social",
                    dailyLimitMinutes = 60
                ),
                focusedUsageMs = 0L,
                surface = AppSurface.OTHER
            )
        )
    }

    @Test
    fun brick_mode_allow_does_not_bypass_an_app_limit() {
        assertEquals(
            BlockDecision.Block(BlockReason.DAILY_LIMIT),
            AppControlDecisionEngine.decide(
                brickModeDecision = BlockDecision.Allow,
                appBlockRule = AppBlockRule(
                    packageName = "com.example.social",
                    dailyLimitMinutes = 10
                ),
                focusedUsageMs = 10 * 60_000L,
                surface = AppSurface.OTHER
            )
        )
    }

    @Test
    fun every_reference_screenshot_name_is_always_available() {
        val names = listOf(
            "Clock",
            "Digital Detox",
            "Digital Wellbeing",
            "Google Play Store",
            "Google Wallet",
            "Lock My Phone",
            "Messages",
            "Niagara Launcher",
            "Outlook",
            "Phone",
            "Settings"
        )

        names.forEach { appName ->
            assertTrue(
                "$appName should be protected",
                EssentialAppPolicy.isAlwaysAvailable(
                    packageName = "unknown.${appName.replace(' ', '.').lowercase()}",
                    appName = appName
                )
            )
        }
    }

    @Test
    fun detected_home_launcher_is_always_available() {
        assertTrue(
            EssentialAppPolicy.isAlwaysAvailable(
                packageName = "com.example.launcher",
                appName = "My Home",
                dynamicallyProtectedPackages = setOf("com.example.launcher")
            )
        )
    }

    private fun schedule(
        id: String = "schedule-id",
        daysOfWeek: Set<Int>,
        startMinuteOfDay: Int,
        durationMinutes: Int
    ) = BrickModeSchedule(
        id = id,
        daysOfWeek = daysOfWeek,
        startMinuteOfDay = startMinuteOfDay,
        durationMinutes = durationMinutes
    )

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
