package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInBucketRestoreTest {
    private val periodStart = 1_700_000_000_000L
    private val fiveMinutesMs = 5 * 60 * 1_000L

    @Test fun service_restart_does_not_repeat_an_already_shown_check_in() {
        val restored = CheckInBucketRestorePolicy.restore(
            record = CheckInBucketRecord(periodStart, intervalMinutes = 5, bucket = 24L),
            periodStartMs = periodStart,
            intervalMinutes = 5,
            currentBucket = 24L
        )
        assertEquals(24L, restored)

        val decision = CheckInOverlayPolicy.evaluate(
            totalScreenTimeMs = 24 * fiveMinutesMs + 30_000L,
            lastObservedBucket = restored,
            drivingAppActive = false,
            intervalMinutes = 5
        )
        assertFalse(decision.shouldShowCheckIn)
    }

    @Test fun bucket_crossed_while_service_was_stopped_still_shows_once() {
        val restored = CheckInBucketRestorePolicy.restore(
            record = CheckInBucketRecord(periodStart, intervalMinutes = 5, bucket = 24L),
            periodStartMs = periodStart,
            intervalMinutes = 5,
            currentBucket = 25L
        )
        val decision = CheckInOverlayPolicy.evaluate(
            totalScreenTimeMs = 25 * fiveMinutesMs,
            lastObservedBucket = restored,
            drivingAppActive = false,
            intervalMinutes = 5
        )
        assertTrue(decision.shouldShowCheckIn)
        assertEquals(25L, decision.observedBucket)
    }

    @Test fun record_from_previous_usage_day_is_ignored() {
        val restored = CheckInBucketRestorePolicy.restore(
            record = CheckInBucketRecord(
                periodStartMs = periodStart - 24 * 60 * 60 * 1_000L,
                intervalMinutes = 5,
                bucket = 40L
            ),
            periodStartMs = periodStart,
            intervalMinutes = 5,
            currentBucket = 2L
        )
        assertEquals(CheckInBucketRestorePolicy.NO_OBSERVED_BUCKET, restored)
    }

    @Test fun interval_changed_while_stopped_uses_current_bucket_as_baseline() {
        val restored = CheckInBucketRestorePolicy.restore(
            record = CheckInBucketRecord(periodStart, intervalMinutes = 5, bucket = 24L),
            periodStartMs = periodStart,
            intervalMinutes = 15,
            currentBucket = 8L
        )
        assertEquals(8L, restored)
    }

    @Test fun missing_or_invalid_record_keeps_fresh_start_behavior() {
        assertEquals(
            CheckInBucketRestorePolicy.NO_OBSERVED_BUCKET,
            CheckInBucketRestorePolicy.restore(null, periodStart, 5, currentBucket = 3L)
        )
        assertEquals(
            CheckInBucketRestorePolicy.NO_OBSERVED_BUCKET,
            CheckInBucketRestorePolicy.restore(
                CheckInBucketRecord(periodStart, intervalMinutes = 5, bucket = -1L),
                periodStart,
                5,
                currentBucket = 3L
            )
        )
    }
}
