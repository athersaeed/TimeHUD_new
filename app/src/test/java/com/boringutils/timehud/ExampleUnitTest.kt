package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.boringutils.timehud.ui.navigation.TimeHudDestination

class ExampleUnitTest {
    @Test
    fun stopped_service_uses_start_action() {
        val state = OverlayServiceUiState()

        assertEquals(ServicePrimaryAction.START, state.primaryAction)
    }

    @Test
    fun running_service_uses_stop_action() {
        val state = OverlayServiceUiState(isRunning = true)

        assertEquals(ServicePrimaryAction.STOP, state.primaryAction)
    }

    @Test
    fun bubble_tap_check_in_can_close_immediately() {
        assertFalse(ActiveOverlayTrigger.BUBBLE_TAP.requiresCloseDelay)
    }

    @Test
    fun automatic_five_minute_check_in_keeps_close_delay() {
        assertTrue(ActiveOverlayTrigger.FIVE_MINUTE_BUCKET.requiresCloseDelay)
    }

    @Test
    fun blocked_app_check_in_waits_then_returns_home() {
        assertTrue(ActiveOverlayTrigger.APP_BLOCK.requiresCloseDelay)
        assertTrue(ActiveOverlayTrigger.APP_BLOCK.returnsHomeOnClose)
    }

    @Test
    fun overlay_destination_request_accepts_brick_mode_and_rejects_unknown_values() {
        assertEquals(
            TimeHudDestination.BRICK_MODE,
            parseTimeHudDestination(TimeHudDestination.BRICK_MODE.name)
        )
        assertEquals(null, parseTimeHudDestination("NOT_A_DESTINATION"))
        assertEquals(null, parseTimeHudDestination(null))
    }

    @Test
    fun shared_screen_time_display_formats_hours_and_minutes() {
        assertEquals("1h5m", ScreenTimeDisplay.format(65 * 60_000L))
    }

    @Test
    fun bubble_position_is_clamped_inside_the_screen() {
        assertEquals(
            BubblePosition(x = 0, y = 736),
            BubblePositioning.clamp(
                x = -40,
                y = 900,
                screenWidth = 400,
                screenHeight = 800,
                bubbleWidth = 64,
                bubbleHeight = 64
            )
        )
    }

    @Test
    fun bubble_position_is_unchanged_when_already_visible() {
        assertEquals(
            BubblePosition(x = 120, y = 240),
            BubblePositioning.clamp(
                x = 120,
                y = 240,
                screenWidth = 400,
                screenHeight = 800,
                bubbleWidth = 64,
                bubbleHeight = 64
            )
        )
    }

    @Test
    fun legacy_calendar_section_is_not_parsed_as_short_term_goals() {
        val configuration = GoalConfiguration(
            shortTermGoals = """
                Finish report

                ${CalendarGoalSection.HEADER}
                All day Team standup
            """.trimIndent(),
            longTermGoals = "Long goal"
        )

        assertEquals(listOf("Finish report"), configuration.shortTermItems)
    }

    @Test
    fun legacy_calendar_section_removal_keeps_manual_goals_before_it() {
        val result = CalendarGoalSection.removeFrom(
            """
                Finish report

                ${CalendarGoalSection.HEADER}
                All day Team standup
            """.trimIndent()
        )

        assertEquals("Finish report", result)
    }

    @Test
    fun all_day_calendar_item_is_formatted_for_the_agenda() {
        val result = CalendarAgenda.formatForAgenda(
            calendarItem(title = "Team standup")
        )

        assertEquals("All day Team standup", result)
    }

    @Test
    fun goal_key_normalizes_mode_and_task_text() {
        val key = GoalCompletionKeys.taskKey(
            GoalMode.SHORT_TERM,
            "  - Finish today's TOP task!  "
        )

        assertEquals("short:finish today s top task", key)
    }

    @Test
    fun stored_completion_date_only_counts_for_today() {
        assertTrue(GoalCompletionKeys.isStoredDateCurrent("2026-06-10", "2026-06-10"))
        assertFalse(GoalCompletionKeys.isStoredDateCurrent("2026-06-09", "2026-06-10"))
        assertFalse(GoalCompletionKeys.isStoredDateCurrent(null, "2026-06-10"))
    }

    @Test
    fun remove_goal_line_removes_first_matching_normalized_line() {
        val goals = """
            - Finish report
            * Review inbox
            Finish report
        """.trimIndent()

        val result = GoalText.removeGoalLine(goals, "finish report")

        assertEquals(
            """
            * Review inbox
            Finish report
            """.trimIndent(),
            result
        )
    }

    private fun calendarItem(title: String): TodayCalendarItem =
        TodayCalendarItem(
            eventId = 1L,
            calendarId = 2L,
            title = title,
            beginMillis = 0L,
            endMillis = 0L,
            allDay = true,
            calendarName = "Work",
            calendarColor = null
        )
}
