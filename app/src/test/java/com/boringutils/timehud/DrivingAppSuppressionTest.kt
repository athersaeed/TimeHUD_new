package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingAppSuppressionTest {
    @Test fun known_navigation_apps_suppress_the_five_minute_overlay() {
        assertTrue(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay("com.google.android.apps.maps", false))
        assertTrue(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay("com.waze", false))
        assertTrue(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay("com.google.android.projection.gearhead", false))
    }

    @Test fun maps_category_covers_other_navigation_apps() {
        assertTrue(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay("example.navigation", true))
    }

    @Test fun unrelated_or_unknown_apps_do_not_suppress_the_overlay() {
        assertFalse(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay("com.example.social", false))
        assertFalse(DrivingAppPolicy.shouldSuppressFiveMinuteOverlay(null, true))
    }

    @Test fun driving_app_consumes_bucket_without_showing_a_delayed_check_in() {
        val suppressed = FiveMinuteOverlayPolicy.evaluate(
            totalScreenTimeMs = 5 * 60 * 1_000L,
            lastObservedBucket = 0L,
            drivingAppActive = true
        )
        assertEquals(1L, suppressed.observedBucket)
        assertFalse(suppressed.shouldShowCheckIn)

        val afterLeavingNavigation = FiveMinuteOverlayPolicy.evaluate(
            totalScreenTimeMs = 5 * 60 * 1_000L,
            lastObservedBucket = suppressed.observedBucket,
            drivingAppActive = false
        )
        assertFalse(afterLeavingNavigation.shouldShowCheckIn)
    }

    @Test fun next_new_bucket_shows_after_navigation_is_closed() {
        val decision = FiveMinuteOverlayPolicy.evaluate(
            totalScreenTimeMs = 10 * 60 * 1_000L,
            lastObservedBucket = 1L,
            drivingAppActive = false
        )
        assertEquals(2L, decision.observedBucket)
        assertTrue(decision.shouldShowCheckIn)
    }
}
