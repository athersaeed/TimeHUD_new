package com.boringutils.timehud

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GoalCompletionKeys {
    fun taskKey(mode: GoalMode, goalText: String): String =
        "${mode.storageName}:${GoalText.normalizeForKey(goalText)}"

    fun isStoredDateCurrent(storedDate: String?, today: String): Boolean =
        storedDate == today
}

object GoalCompletionStore {
    private const val PREFS_NAME = "timehud_goal_completion"
    private const val KEY_COMPLETED_DATE = "completed_date"
    private const val KEY_COMPLETED_KEYS = "completed_keys"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun isCompleted(context: Context, mode: GoalMode, goalText: String): Boolean =
        completedKeys(context).contains(GoalCompletionKeys.taskKey(mode, goalText))

    fun markCompleted(context: Context, mode: GoalMode, goalText: String) {
        val next = completedKeys(context).toMutableSet()
        next += GoalCompletionKeys.taskKey(mode, goalText)
        saveCompletedKeys(context, next)
    }

    fun clearCompleted(context: Context, mode: GoalMode, goalText: String) {
        val next = completedKeys(context).toMutableSet()
        next -= GoalCompletionKeys.taskKey(mode, goalText)
        saveCompletedKeys(context, next)
    }

    fun completedKeys(context: Context, today: String = todayKey()): Set<String> {
        val prefs = prefs(context)
        val storedDate = prefs.getString(KEY_COMPLETED_DATE, null)
        if (!GoalCompletionKeys.isStoredDateCurrent(storedDate, today)) {
            return emptySet()
        }
        return prefs.getStringSet(KEY_COMPLETED_KEYS, emptySet()).orEmpty()
    }

    private fun saveCompletedKeys(context: Context, keys: Set<String>) {
        prefs(context).edit()
            .putString(KEY_COMPLETED_DATE, todayKey())
            .putStringSet(KEY_COMPLETED_KEYS, keys)
            .apply()
    }

    private fun todayKey(): String = dateFormat.format(Date())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
