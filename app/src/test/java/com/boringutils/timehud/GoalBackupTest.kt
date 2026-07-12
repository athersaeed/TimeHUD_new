package com.boringutils.timehud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalBackupTest {
    @Test
    fun round_trip_preserves_multiline_goal_text() {
        val backup = GoalBackup.forExport(
            shortTermGoalText = "First goal\nSecond goal",
            longTermGoalText = "Long-term goal\nAnother goal",
            exportedAtEpochMs = 1234L
        )

        val restored = parseSuccess(GoalBackupFormat.serialize(backup))

        assertEquals(backup, restored)
    }

    @Test
    fun empty_goal_groups_survive_round_trip() {
        val backup = GoalBackup.forExport("", "", exportedAtEpochMs = 5L)

        val restored = parseSuccess(GoalBackupFormat.serialize(backup))

        assertEquals("", restored.shortTermGoalText)
        assertEquals("", restored.longTermGoalText)
        assertEquals(0, restored.shortTermGoalCount)
        assertEquals(0, restored.longTermGoalCount)
    }

    @Test
    fun special_characters_survive_round_trip() {
        val shortTerm = "Quote: \"focus\"\nPath: C:\\Goals\\Today\nUnicode: café 你好 ✅"
        val longTerm = "Punctuation: !@#$%^&*()[]{};:'?,./\nSecond line"
        val backup = GoalBackup.forExport(shortTerm, longTerm, exportedAtEpochMs = 9L)

        val restored = parseSuccess(GoalBackupFormat.serialize(backup))

        assertEquals(shortTerm, restored.shortTermGoalText)
        assertEquals(longTerm, restored.longTermGoalText)
    }

    @Test
    fun export_excludes_calendar_header_and_calendar_lines() {
        val backup = GoalBackup.forExport(
            shortTermGoalText = "Manual goal\n\n${CalendarGoalSection.HEADER}\n09:00-10:00 Standup",
            longTermGoalText = "Long goal",
            exportedAtEpochMs = 1L
        )

        assertEquals("Manual goal", backup.shortTermGoalText)
    }

    @Test
    fun calendar_section_removal_preserves_manual_goal_order() {
        val goals = "First manual goal\nSecond manual goal\n\n${CalendarGoalSection.HEADER}\nAll day Event"

        val result = CalendarGoalSection.removeFrom(goals)

        assertEquals("First manual goal\nSecond manual goal", result)
    }

    @Test
    fun invalid_json_is_rejected() {
        assertEquals(GoalBackupParseResult.Invalid, GoalBackupFormat.parse("{not-json"))
    }

    @Test
    fun empty_file_is_rejected() {
        assertEquals(GoalBackupParseResult.Invalid, GoalBackupFormat.parse("  \n"))
    }

    @Test
    fun incorrect_format_identifier_is_rejected() {
        val json = validJson().put("format", "another-app")

        assertEquals(GoalBackupParseResult.Invalid, GoalBackupFormat.parse(json.toString()))
    }

    @Test
    fun unsupported_schema_version_is_rejected() {
        val json = validJson().put("schemaVersion", 2)

        assertEquals(
            GoalBackupParseResult.UnsupportedSchema,
            GoalBackupFormat.parse(json.toString())
        )
    }

    @Test
    fun missing_required_fields_are_rejected() {
        val requiredFields = listOf(
            "format",
            "schemaVersion",
            "exportedAtEpochMs",
            "shortTermGoalText",
            "longTermGoalText"
        )

        requiredFields.forEach { field ->
            val json = validJson().apply { remove(field) }
            assertEquals(
                "Expected missing $field to be rejected",
                GoalBackupParseResult.Invalid,
                GoalBackupFormat.parse(json.toString())
            )
        }
    }

    @Test
    fun incorrect_json_field_types_are_rejected() {
        val incorrectValues = mapOf(
            "format" to 1,
            "schemaVersion" to "1",
            "exportedAtEpochMs" to "0",
            "shortTermGoalText" to true,
            "longTermGoalText" to JSONObject()
        )

        incorrectValues.forEach { (field, value) ->
            val json = validJson().put(field, value)
            assertEquals(
                "Expected incorrect type for $field to be rejected",
                GoalBackupParseResult.Invalid,
                GoalBackupFormat.parse(json.toString())
            )
        }
    }

    @Test
    fun unknown_additional_fields_are_ignored() {
        val json = validJson().put("futureMetadata", JSONObject().put("value", 1))

        val restored = parseSuccess(json.toString())

        assertEquals("Short", restored.shortTermGoalText)
        assertEquals("Long", restored.longTermGoalText)
    }

    @Test
    fun parsing_failure_does_not_return_partial_goal_data() {
        val result = GoalBackupFormat.parse(
            """{"format":"timehud-goals","schemaVersion":1,"shortTermGoalText":"Partial""""
        )

        assertEquals(GoalBackupParseResult.Invalid, result)
    }

    @Test
    fun import_excludes_calendar_section_from_selected_backup() {
        val json = validJson().put(
            "shortTermGoalText",
            "Manual\n\n${CalendarGoalSection.HEADER}\nAll day Imported event"
        )

        val restored = parseSuccess(json.toString())

        assertEquals("Manual", restored.shortTermGoalText)
    }

    private fun validJson(): JSONObject = JSONObject()
        .put("format", GoalBackupFormat.IDENTIFIER)
        .put("schemaVersion", GoalBackupFormat.SCHEMA_VERSION)
        .put("exportedAtEpochMs", 0L)
        .put("shortTermGoalText", "Short")
        .put("longTermGoalText", "Long")

    private fun parseSuccess(json: String): GoalBackup {
        val result = GoalBackupFormat.parse(json)
        assertTrue("Expected a successful parse but got $result", result is GoalBackupParseResult.Success)
        return (result as GoalBackupParseResult.Success).backup
    }
}
