package com.boringutils.timehud.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrickModeTest {
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
}
