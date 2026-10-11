package com.boringutils.timehud.ui.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.boringutils.timehud.ScreenTimeDisplay

internal sealed interface AppUsageLoadResult {
    data class Success(
        val periodStartMs: Long,
        val screenTimeDurationMs: Long?,
        val entries: List<AppUsageEntry>
    ) : AppUsageLoadResult

    data object AccessDenied : AppUsageLoadResult
    data object Unavailable : AppUsageLoadResult
}

internal object AppUsageRepository {
    @Suppress("DEPRECATION")
    fun load(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        period: AppUsagePeriod = AppUsagePeriod.TODAY,
        loadIcons: Boolean = false
    ): AppUsageLoadResult {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return AppUsageLoadResult.Unavailable
        val range = appUsageRange(period, nowMs)
        val periodStartMs = range.startMs

        return try {
            if (period == AppUsagePeriod.PAST_WEEK) {
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, range.startMs, range.endMs
                ) ?: return AppUsageLoadResult.Unavailable
                val entries = stats.groupBy { it.packageName }.mapNotNull { (name, days) ->
                    val duration = days.sumOf { it.totalTimeInForeground }
                    if (duration <= 0L) null else entry(context, name, duration, loadIcons)
                }.sortedWith(compareByDescending<AppUsageEntry> { it.durationMs }
                    .thenBy { it.appName.lowercase() })
                val screenTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    usageStatsManager.queryEventStats(
                        UsageStatsManager.INTERVAL_DAILY, range.startMs, range.endMs
                    )?.filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }
                        ?.sumOf { it.totalTime }
                } else null
                return AppUsageLoadResult.Success(periodStartMs, screenTime, entries)
            }
            val usageEvents = usageStatsManager.queryEvents(periodStartMs, range.endMs)
                ?: return AppUsageLoadResult.Unavailable
            val events = buildList {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val packageName = event.packageName.orEmpty()
                    val type = when (event.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> AppUsageEventType.FOREGROUND
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> AppUsageEventType.BACKGROUND
                        // Screen-off and shutdown close open app intervals.
                        16, 26 -> AppUsageEventType.SCREEN_OFF
                        else -> continue
                    }
                    if (packageName.isBlank() && type != AppUsageEventType.SCREEN_OFF) continue
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
                periodEndMs = range.endMs
            ).map { (packageName, durationMs) ->
                entry(context, packageName, durationMs, loadIcons)
            }.sortedWith(
                compareByDescending<AppUsageEntry> { it.durationMs }
                    .thenBy { it.appName.lowercase() }
            )

            AppUsageLoadResult.Success(
                periodStartMs = periodStartMs,
                screenTimeDurationMs = ScreenTimeDisplay.queryRangeMs(
                    context, range.startMs, range.endMs,
                    includeCurrentState = period == AppUsagePeriod.TODAY
                ),
                entries = entries
            )
        } catch (_: SecurityException) {
            AppUsageLoadResult.AccessDenied
        } catch (_: RuntimeException) {
            AppUsageLoadResult.Unavailable
        }
    }

    private fun entry(context: Context, packageName: String, durationMs: Long, loadIcons: Boolean): AppUsageEntry =
        AppUsageEntry(
            packageName = packageName,
            appName = resolveAppName(context.packageManager, packageName),
            durationMs = durationMs,
            icon = if (!loadIcons) null else try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
        )

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
