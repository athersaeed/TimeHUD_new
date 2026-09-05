package com.boringutils.timehud.ui.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.boringutils.timehud.ScreenTimeDisplay

internal sealed interface AppUsageLoadResult {
    data class Success(
        val periodStartMs: Long,
        val screenTimeDurationMs: Long,
        val entries: List<AppUsageEntry>
    ) : AppUsageLoadResult

    data object AccessDenied : AppUsageLoadResult
    data object Unavailable : AppUsageLoadResult
}

internal object AppUsageRepository {
    @Suppress("DEPRECATION")
    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): AppUsageLoadResult {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return AppUsageLoadResult.Unavailable
        val periodStartMs = currentUsagePeriodStart(nowMs)

        return try {
            val usageEvents = usageStatsManager.queryEvents(periodStartMs, nowMs)
                ?: return AppUsageLoadResult.Unavailable
            val events = buildList {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val packageName = event.packageName?.takeIf { it.isNotBlank() } ?: continue
                    val type = when (event.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> AppUsageEventType.FOREGROUND
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> AppUsageEventType.BACKGROUND
                        else -> continue
                    }
                    add(
                        AppUsageEvent(
                            packageName = packageName,
                            timestampMs = event.timeStamp,
                            type = type
                        )
                    )
                }
            }

            val entries = AppUsageCalculator.calculateDurations(
                events = events,
                periodStartMs = periodStartMs,
                periodEndMs = nowMs
            ).map { (packageName, durationMs) ->
                AppUsageEntry(
                    packageName = packageName,
                    appName = resolveAppName(context.packageManager, packageName),
                    durationMs = durationMs
                )
            }.sortedWith(
                compareByDescending<AppUsageEntry> { it.durationMs }
                    .thenBy { it.appName.lowercase() }
            )

            AppUsageLoadResult.Success(
                periodStartMs = periodStartMs,
                screenTimeDurationMs = ScreenTimeDisplay.queryMs(context, nowMs),
                entries = entries
            )
        } catch (_: SecurityException) {
            AppUsageLoadResult.AccessDenied
        } catch (_: RuntimeException) {
            AppUsageLoadResult.Unavailable
        }
    }

    private fun resolveAppName(packageManager: PackageManager, packageName: String): String =
        try {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(applicationInfo).toString()
                .takeIf { it.isNotBlank() } ?: packageName
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: SecurityException) {
            packageName
        }
}
