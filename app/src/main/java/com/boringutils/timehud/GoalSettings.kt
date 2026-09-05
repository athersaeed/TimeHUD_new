package com.boringutils.timehud

import android.content.Context
import androidx.core.content.edit
import java.text.Normalizer
import java.util.Locale

data class GoalConfiguration(
    val shortTermGoals: String,
    val longTermGoals: String
) {
    val shortTermItems: List<String>
        get() = CalendarGoalSection.removeFrom(shortTermGoals).toGoalItems()

    val longTermItems: List<String>
        get() = longTermGoals.toGoalItems()
}

enum class GoalMode(val storageName: String) {
    SHORT_TERM("short"),
    LONG_TERM("long")
}

object GoalSettings {
    private const val PREFS_NAME = "timehud_goals"
    private const val KEY_SHORT_TERM_GOALS = "short_term_goals"
    private const val KEY_LONG_TERM_GOALS = "long_term_goals"

    private const val DEFAULT_SHORT_TERM_GOALS = """Finish today's top task
Review calendar and messages
Spend 25 minutes on focused work"""

    private const val DEFAULT_LONG_TERM_GOALS = """Build better focus habits
Finish the TimeHUD project
Keep screen time aligned with career goals"""

    fun load(context: Context): GoalConfiguration {
        val prefs = prefs(context)
        return GoalConfiguration(
            shortTermGoals = prefs.getString(KEY_SHORT_TERM_GOALS, null) ?: DEFAULT_SHORT_TERM_GOALS,
            longTermGoals = prefs.getString(KEY_LONG_TERM_GOALS, null) ?: DEFAULT_LONG_TERM_GOALS
        )
    }

    fun save(context: Context, shortTermGoals: String, longTermGoals: String) {
        prefs(context).edit()
            .putString(KEY_SHORT_TERM_GOALS, shortTermGoals.trim())
            .putString(KEY_LONG_TERM_GOALS, longTermGoals.trim())
            .apply()
    }

    fun replaceFromBackup(context: Context, shortTermGoals: String, longTermGoals: String) {
        prefs(context).edit {
            putString(KEY_SHORT_TERM_GOALS, shortTermGoals)
            putString(KEY_LONG_TERM_GOALS, longTermGoals)
        }
    }

    fun removeGoal(context: Context, mode: GoalMode, goalText: String): GoalConfiguration {
        val current = load(context)
        val next = when (mode) {
            GoalMode.SHORT_TERM -> current.copy(
                shortTermGoals = GoalText.removeGoalLine(current.shortTermGoals, goalText)
            )
            GoalMode.LONG_TERM -> current.copy(
                longTermGoals = GoalText.removeGoalLine(current.longTermGoals, goalText)
            )
        }
        save(context, next.shortTermGoals, next.longTermGoals)
        return next
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

object GoalText {
    fun toGoalItems(rawGoals: String): List<String> =
        rawGoals.lineSequence()
            .map { cleanDisplayText(it) }
            .filter { it.isNotBlank() }
            .take(8)
            .toList()

    fun removeGoalLine(rawGoals: String, goalText: String): String {
        val target = normalizeForKey(goalText)
        var removed = false
        return rawGoals.lines()
            .filter { line ->
                if (removed || normalizeForKey(line) != target) {
                    true
                } else {
                    removed = true
                    false
                }
            }
            .joinToString(separator = "\n")
            .trim()
    }

    fun normalizeForKey(text: String): String {
        val withoutMarks = Normalizer.normalize(cleanDisplayText(text), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return withoutMarks
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun cleanDisplayText(text: String): String =
        text.trim()
            .trimStart('-', '*')
            .trim()
}

private fun String.toGoalItems(): List<String> = GoalText.toGoalItems(this)

/** Preserve draft edits while applying lines removed by the overlay since editing began. */
internal fun reconcileRemovedGoals(baseline: String, draft: String, persisted: String): String {
    if (draft == baseline) return persisted
    if (persisted == baseline) return draft
    val remaining = persisted.lines().groupingBy { it.trim() }.eachCount().toMutableMap()
    val removed = mutableMapOf<String, Int>()
    baseline.lines().forEach { line ->
        val key = line.trim()
        val count = remaining[key] ?: 0
        if (count > 0) remaining[key] = count - 1
        else if (key.isNotEmpty()) removed[key] = (removed[key] ?: 0) + 1
    }
    return draft.lines().filter { line ->
        val key = line.trim()
        val count = removed[key] ?: 0
        if (count > 0) {
            removed[key] = count - 1
            false
        } else true
    }.joinToString("\n")
}
