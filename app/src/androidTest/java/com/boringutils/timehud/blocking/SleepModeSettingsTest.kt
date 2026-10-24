package com.boringutils.timehud.blocking

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepModeSettingsTest {
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("sleep_mode_test_$name", mode)
    }

    @Before
    @After
    fun clearTestSettings() {
        context.getSharedPreferences("timehud_brick_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun five_app_cap_persists_and_removing_an_app_frees_a_slot() {
        for (index in 1..5) {
            assertTrue(BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "app.$index", true))
        }
        assertFalse(BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "app.6", true))
        assertEquals(5, BrickModeSettings.load(context).sleepAllowedPackages.size)
        assertTrue(BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "app.1", false))
        assertTrue(BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "app.6", true))
        assertEquals(setOf("app.2", "app.3", "app.4", "app.5", "app.6"), BrickModeSettings.load(context).sleepAllowedPackages)
        assertTrue(BrickModeSettings.load(context).brickAllowedPackages.isEmpty())
        assertTrue(BrickModeSettings.load(context).restrictedAllowedPackages.isEmpty())
    }

    @Test
    fun sleep_timer_persists_and_expires_without_erasing_chosen_apps() {
        BrickModeSettings.setPackageAllowed(context, UsageRestrictionMode.SLEEP, "app.audio", true)
        assertTrue(BrickModeSettings.startTimed(context, UsageRestrictionMode.SLEEP, 60, nowMs = 1_000L))
        val active = BrickModeSettings.load(context, nowMs = 2_000L)
        assertEquals(UsageRestrictionMode.SLEEP, active.mode)
        assertTrue(active.enabled)
        assertFalse(BrickModeSettings.load(context, nowMs = 3_601_000L).enabled)
        assertEquals(setOf("app.audio"), BrickModeSettings.load(context).sleepAllowedPackages)
    }
}
