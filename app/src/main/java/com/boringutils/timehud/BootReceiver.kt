package com.boringutils.timehud

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TimeHUD"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (!StartupPreferences.shouldRestartHud(context)) {
                Log.d(TAG, "Skipping boot restart because HUD was not active")
                return
            }

            // Only start the service if the user has already granted the required permissions.
            // Otherwise, starting it might crash the app when trying to display the overlay.
            if (hasOverlayPermission(context) &&
                hasUsagePermission(context)
            ) {
                val serviceIntent = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    private fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    private fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
