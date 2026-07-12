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
