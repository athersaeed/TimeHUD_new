package com.boringutils.timehud

import android.content.Context
import androidx.core.content.edit

object CheckInSettings {
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 60
    const val DEFAULT_INTERVAL_MINUTES = 5

    private const val PREFS_NAME = "timehud_check_in"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"

    fun loadIntervalMinutes(context: Context): Int = normalizeIntervalMinutes(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
    )

    fun saveIntervalMinutes(context: Context, intervalMinutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_INTERVAL_MINUTES, normalizeIntervalMinutes(intervalMinutes))
        }
    }

    internal fun normalizeIntervalMinutes(intervalMinutes: Int): Int =
        intervalMinutes.takeIf { it in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES }
            ?: DEFAULT_INTERVAL_MINUTES
}
