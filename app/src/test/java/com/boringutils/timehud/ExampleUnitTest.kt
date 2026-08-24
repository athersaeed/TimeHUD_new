package com.boringutils.timehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun calendar_import_appends_section_without_replacing_manual_goals() {
        val result = CalendarAgenda.appendCalendarSection(
            shortTermGoals = "Finish report",
            items = listOf(calendarItem(title = "Team standup"))
        )

        assertTrue(result.startsWith("Finish report"))
        assertTrue(result.contains(CalendarGoalSection.HEADER))
        assertTrue(result.contains("All day Team standup"))
    }

    @Test
    fun calendar_import_replaces_previous_calendar_section() {
        val previousGoals = """
            Finish report

            ${CalendarGoalSection.HEADER}
            All day Old meeting
        """.trimIndent()

        val result = CalendarAgenda.appendCalendarSection(
            shortTermGoals = previousGoals,
            items = listOf(calendarItem(title = "Planning review"))
        )

        assertTrue(result.contains("Finish report"))
        assertTrue(result.contains("All day Planning review"))
        assertFalse(result.contains("Old meeting"))
    }

    @Test
    fun empty_calendar_import_keeps_manual_goals() {
        val result = CalendarAgenda.appendCalendarSection(
            shortTermGoals = "Finish report",
            items = emptyList()
        )

        assertEquals("Finish report", result)
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
