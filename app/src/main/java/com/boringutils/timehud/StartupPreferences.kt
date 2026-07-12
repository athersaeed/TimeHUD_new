package com.boringutils.timehud

import android.content.Context

object StartupPreferences {
    private const val PREFS_NAME = "timehud_startup"
    private const val KEY_HUD_ACTIVE = "hud_active"

    fun shouldRestartHud(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HUD_ACTIVE, false)

    fun markHudRunning(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_HUD_ACTIVE, true)
            .apply()
    }

    fun markHudStopped(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_HUD_ACTIVE, false)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
