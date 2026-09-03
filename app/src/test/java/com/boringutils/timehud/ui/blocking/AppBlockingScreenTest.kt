package com.boringutils.timehud.ui.blocking

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBlockingScreenTest {
    private val apps = listOf(
        BlockableAppUi(
            packageName = "com.instagram.android",
            appName = "Instagram",
            usageMs = 12_000L,
            rule = null
        ),
        BlockableAppUi(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            usageMs = 8_000L,
            rule = null
        )
    )

    @Test
    fun blank_search_preserves_current_app_order() {
        assertEquals(apps, filterBlockableApps(apps, "  "))
    }

    @Test
    fun search_matches_app_name_case_insensitively() {
        assertEquals(
            listOf(apps[0]),
            filterBlockableApps(apps, "InStA")
        )
    }

    @Test
    fun search_matches_package_name_case_insensitively() {
        assertEquals(
            listOf(apps[1]),
            filterBlockableApps(apps, "GOOGLE.ANDROID")
        )
    }

    @Test
    fun search_returns_empty_when_no_app_matches() {
        assertEquals(emptyList<BlockableAppUi>(), filterBlockableApps(apps, "Spotify"))
    }
}
