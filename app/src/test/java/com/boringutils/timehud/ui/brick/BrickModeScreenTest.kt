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
}
