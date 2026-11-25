package com.boringutils.timehud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/** What tapping Grant on the notification card should do. */
internal enum class NotificationGrantAction {
    REQUEST_RUNTIME_PERMISSION,
    OPEN_SETTINGS
}

internal object NotificationGrantPolicy {
    /**
     * Android 13+ shows a runtime prompt until the user declines it twice. Before
     * Android 13, or once the permission is held but notifications are switched
     * off, only the app's notification settings can turn them back on.
     */
    fun actionFor(sdkInt: Int, runtimePermissionGranted: Boolean): NotificationGrantAction =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted) {
            NotificationGrantAction.REQUEST_RUNTIME_PERMISSION
        } else {
            NotificationGrantAction.OPEN_SETTINGS
        }

    /** A denied prompt with no rationale means Android will not show it again. */
    fun shouldOpenSettingsAfterDenial(granted: Boolean, shouldShowRationale: Boolean): Boolean =
        !granted && !shouldShowRationale

    /** The first HUD start asks once; later starts never prompt again. */
    fun shouldPromptOnStart(
        sdkInt: Int,
        runtimePermissionGranted: Boolean,
        alreadyPromptedOnStart: Boolean
    ): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        !runtimePermissionGranted &&
        !alreadyPromptedOnStart
}

internal object NotificationAccess {
    private const val PREFS_NAME = "timehud_notifications"
    private const val KEY_START_PROMPT_SHOWN = "start_prompt_shown"

    /** True when the HUD's ongoing notification can be shown. */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun hasRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun grantAction(context: Context): NotificationGrantAction =
        NotificationGrantPolicy.actionFor(Build.VERSION.SDK_INT, hasRuntimePermission(context))

    fun shouldPromptOnStart(context: Context): Boolean =
        NotificationGrantPolicy.shouldPromptOnStart(
            sdkInt = Build.VERSION.SDK_INT,
            runtimePermissionGranted = hasRuntimePermission(context),
            alreadyPromptedOnStart = prefs(context).getBoolean(KEY_START_PROMPT_SHOWN, false)
        )

    fun markPromptedOnStart(context: Context) {
        prefs(context).edit { putBoolean(KEY_START_PROMPT_SHOWN, true) }
    }

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: RuntimeException) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
