package com.boringutils.timehud.blocking

import org.junit.Assert.*
import org.junit.Test

class WindowEvaluationScheduleTest {
    @Test fun continuous_feed_events_cannot_postpone_the_first_check() {
        val schedule = WindowEvaluationSchedule()
        assertTrue(schedule.request(0L, 100L))
        for (now in 10L..100L step 10L) assertFalse(schedule.request(now, 100L))
        schedule.clear() // The pending callback starts evaluating the current window.
        assertTrue(schedule.request(110L, 100L))
    }

    @Test fun immediate_boundary_can_advance_a_pending_check() {
        val schedule = WindowEvaluationSchedule()
        assertTrue(schedule.request(0L, 100L))
        assertTrue(schedule.request(20L, 0L))
        assertFalse(schedule.request(21L, 100L))
    }

    @Test fun screen_suspension_cancels_the_previous_deadline() {
        val schedule = WindowEvaluationSchedule()
        assertTrue(schedule.request(0L, 100L))
        schedule.clear()
        assertTrue(schedule.request(1000L, 0L))
    }
}
