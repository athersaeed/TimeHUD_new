package com.boringutils.timehud

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject

data class GoalBackup(
    val exportedAtEpochMs: Long,
    val shortTermGoalText: String,
    val longTermGoalText: String
) {
    val shortTermGoalCount: Int
        get() = shortTermGoalText.lineSequence().count { it.isNotBlank() }

    val longTermGoalCount: Int
        get() = longTermGoalText.lineSequence().count { it.isNotBlank() }

    companion object {
        fun forExport(
            shortTermGoalText: String,
            longTermGoalText: String,
            exportedAtEpochMs: Long = System.currentTimeMillis()
        ): GoalBackup = GoalBackup(
            exportedAtEpochMs = exportedAtEpochMs,
            shortTermGoalText = CalendarGoalSection.removeFrom(shortTermGoalText),
            longTermGoalText = longTermGoalText
        )
    }
}

object GoalBackupFormat {
    const val IDENTIFIER = "timehud-goals"
    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"

    private const val FIELD_FORMAT = "format"
    private const val FIELD_SCHEMA_VERSION = "schemaVersion"
    private const val FIELD_EXPORTED_AT = "exportedAtEpochMs"
    private const val FIELD_SHORT_TERM = "shortTermGoalText"
    private const val FIELD_LONG_TERM = "longTermGoalText"

    fun serialize(backup: GoalBackup): String = JSONObject()
        .put(FIELD_FORMAT, IDENTIFIER)
        .put(FIELD_SCHEMA_VERSION, SCHEMA_VERSION)
        .put(FIELD_EXPORTED_AT, backup.exportedAtEpochMs)
        .put(FIELD_SHORT_TERM, backup.shortTermGoalText)
        .put(FIELD_LONG_TERM, backup.longTermGoalText)
        .toString(2)

    fun parse(jsonText: String): GoalBackupParseResult {
        if (jsonText.isBlank()) return GoalBackupParseResult.Invalid

        val json = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return GoalBackupParseResult.Invalid
        }

        val format = json.requiredString(FIELD_FORMAT)
            ?: return GoalBackupParseResult.Invalid
        if (format != IDENTIFIER) return GoalBackupParseResult.Invalid

        val schemaVersion = json.requiredInt(FIELD_SCHEMA_VERSION)
            ?: return GoalBackupParseResult.Invalid
        if (schemaVersion != SCHEMA_VERSION) return GoalBackupParseResult.UnsupportedSchema

        val exportedAtEpochMs = json.requiredLong(FIELD_EXPORTED_AT)
            ?: return GoalBackupParseResult.Invalid
        val shortTermGoalText = json.requiredString(FIELD_SHORT_TERM)
            ?: return GoalBackupParseResult.Invalid
        val longTermGoalText = json.requiredString(FIELD_LONG_TERM)
            ?: return GoalBackupParseResult.Invalid

        return GoalBackupParseResult.Success(
            GoalBackup(
                exportedAtEpochMs = exportedAtEpochMs,
                shortTermGoalText = CalendarGoalSection.removeFrom(shortTermGoalText),
                longTermGoalText = longTermGoalText
            )
        )
    }

    private fun JSONObject.requiredString(field: String): String? {
        if (!has(field) || isNull(field)) return null
        return get(field) as? String
    }

    private fun JSONObject.requiredInt(field: String): Int? {
        if (!has(field) || isNull(field)) return null
        return when (val value = get(field)) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
    }

    private fun JSONObject.requiredLong(field: String): Long? {
        if (!has(field) || isNull(field)) return null
        return when (val value = get(field)) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }
}

sealed interface GoalBackupParseResult {
    data class Success(val backup: GoalBackup) : GoalBackupParseResult
    data object Invalid : GoalBackupParseResult
    data object UnsupportedSchema : GoalBackupParseResult
}

sealed interface GoalBackupReadResult {
    data class Success(val backup: GoalBackup) : GoalBackupReadResult
    data object Unreadable : GoalBackupReadResult
    data object Invalid : GoalBackupReadResult
    data object UnsupportedSchema : GoalBackupReadResult
}

enum class GoalBackupWriteResult {
    SUCCESS,
    FAILURE
}

object GoalBackupStorage {
    fun write(
        contentResolver: ContentResolver,
        uri: Uri,
        backup: GoalBackup
    ): GoalBackupWriteResult {
        return try {
            val outputStream = contentResolver.openOutputStream(uri)
                ?: return GoalBackupWriteResult.FAILURE
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(GoalBackupFormat.serialize(backup))
            }
            GoalBackupWriteResult.SUCCESS
        } catch (_: Exception) {
            GoalBackupWriteResult.FAILURE
        }
    }

    fun read(contentResolver: ContentResolver, uri: Uri): GoalBackupReadResult {
        val text = try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: return GoalBackupReadResult.Unreadable
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        } catch (_: Exception) {
            return GoalBackupReadResult.Unreadable
        }

        return when (val result = GoalBackupFormat.parse(text)) {
            is GoalBackupParseResult.Success -> GoalBackupReadResult.Success(result.backup)
            GoalBackupParseResult.Invalid -> GoalBackupReadResult.Invalid
            GoalBackupParseResult.UnsupportedSchema -> GoalBackupReadResult.UnsupportedSchema
        }
    }
}
