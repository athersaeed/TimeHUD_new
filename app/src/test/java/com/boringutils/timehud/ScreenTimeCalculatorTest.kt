package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeCalculatorTest {
    @Test fun duplicate_screen_on_does_not_lose_elapsed_time() {
        val calculator = ScreenTimeCalculator(0, 100)
        calculator.record(10, true)
        calculator.record(30, true)
        calculator.record(60, false)
        assertEquals(50L, calculator.total(false))
    }

    @Test fun first_screen_off_counts_from_daily_boundary_only_once() {
        val calculator = ScreenTimeCalculator(10, 100)
        calculator.record(40, false)
        calculator.record(60, false)
        assertEquals(30L, calculator.total(false))
    }

    @Test fun open_interval_at_zero_is_counted_and_total_is_repeatable() {
        val calculator = ScreenTimeCalculator(0, 100)
        calculator.record(0, true)
        assertEquals(100L, calculator.total(true))
        assertEquals(100L, calculator.total(true))
    }

    @Test fun multiple_intervals_include_the_current_open_interval() {
        val calculator = ScreenTimeCalculator(0, 100)
        calculator.record(10, true)
        calculator.record(30, false)
        calculator.record(60, true)
        assertEquals(60L, calculator.total(true))
    }

    @Test fun empty_history_uses_current_interactive_state() {
        val calculator = ScreenTimeCalculator(10, 100)
        assertEquals(90L, calculator.total(true))
        assertEquals(0L, calculator.total(false))
    }

    @Test fun events_outside_the_usage_day_are_ignored() {
        val calculator = ScreenTimeCalculator(10, 100)
        calculator.record(0, true)
        calculator.record(110, false)
        assertEquals(0L, calculator.total(false))
    }

    @Test fun empty_or_reversed_period_has_no_usage() {
        assertEquals(0L, ScreenTimeCalculator(100, 100).total(true))
        assertEquals(0L, ScreenTimeCalculator(100, 10).total(true))
    }

    @Test fun screen_off_at_end_closes_interval_without_double_counting() {
        val calculator = ScreenTimeCalculator(10, 100)
        calculator.record(10, true)
        calculator.record(100, false)
        assertEquals(90L, calculator.total(false))
    }
}
