package com.boringutils.timehud

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

internal data class HudRuntimeSample(
    val totalScreenTimeMs: Long?,
    val drivingAppActive: Boolean?
)

internal object DrivingAppPolicy {
    private val knownNavigationPackages = setOf(
        "app.organicmaps",
        "com.generalmagic.magicearth",
        "com.google.android.apps.maps",
        "com.google.android.apps.mapslite",
        "com.google.android.projection.gearhead",
        "com.here.app.maps",
        "com.mapfactor.navigator",
        "com.mapquest.android.ace",
        "com.sygic.aura",
        "com.tomtom.gplay.navapp",
        "com.tomtom.gplay.navapp.nds",
        "com.waze",
        "net.osmand",
        "net.osmand.plus"
    )

    fun shouldSuppressFiveMinuteOverlay(
        packageName: String?,
        isMapsCategory: Boolean
    ): Boolean = packageName != null &&
        (packageName in knownNavigationPackages || isMapsCategory)
}

internal data class FiveMinuteBucketDecision(
    val observedBucket: Long,
    val shouldShowCheckIn: Boolean
)

internal object FiveMinuteOverlayPolicy {
    private const val FIVE_MINUTES_MS = 5 * 60 * 1_000L

    fun evaluate(
        totalScreenTimeMs: Long,
        lastObservedBucket: Long,
        drivingAppActive: Boolean
    ): FiveMinuteBucketDecision {
        val currentBucket = totalScreenTimeMs / FIVE_MINUTES_MS
        val isNewBucket = currentBucket > 0L && currentBucket != lastObservedBucket
        return FiveMinuteBucketDecision(
            observedBucket = if (isNewBucket) currentBucket else lastObservedBucket,
            shouldShowCheckIn = isNewBucket && !drivingAppActive
        )
    }
}

internal object DrivingAppSignalPolicy {
    fun resolve(
        usageAccessSignal: Boolean,
        accessibilityWindowSignal: Boolean?
    ): Boolean = accessibilityWindowSignal ?: usageAccessSignal
}

internal class ForegroundPackageTracker {
    companion object {
        internal const val MOVE_TO_FOREGROUND = 1
        internal const val ACTIVITY_RESUMED = 23
    }

    var packageName: String? = null
        private set

    fun record(packageName: String, eventType: Int) {
        // A package can resume one activity before pausing another. The newest
        // foreground/resume event is authoritative; a same-package pause must
        // not erase it. A different app's resume replaces it naturally.
        if (eventType == MOVE_TO_FOREGROUND || eventType == ACTIVITY_RESUMED) {
            this.packageName = packageName
        }
    }
}

/**
 * Tracks the most recently foregrounded package from Usage Access events.
 * Calls must stay on the service's serial worker dispatcher.
 */
internal class ForegroundAppMonitor(context: Context) {
    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1_000L
        private const val QUERY_OVERLAP_MS = 1_000L
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: throw IllegalStateException("Usage service unavailable")
    private val packageManager = context.packageManager
    private val mapsCategoryCache = ConcurrentHashMap<String, Boolean>()
    private val foregroundPackageTracker = ForegroundPackageTracker()

    private var lastQueryEndMs = 0L

    fun isDrivingAppActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        refreshForegroundPackage(nowMs)
        return isDrivingPackage(foregroundPackageTracker.packageName)
    }

    fun isDrivingPackage(packageName: String?): Boolean {
        return DrivingAppPolicy.shouldSuppressFiveMinuteOverlay(
            packageName = packageName,
            isMapsCategory = packageName?.let(::isMapsCategory) == true
        )
    }

    private fun refreshForegroundPackage(nowMs: Long) {
        val initialStartMs = (nowMs - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        val queryStartMs = if (lastQueryEndMs in 1..nowMs) {
            (lastQueryEndMs - QUERY_OVERLAP_MS).coerceAtLeast(initialStartMs)
        } else {
            initialStartMs
        }
        val events = usageStatsManager.queryEvents(queryStartMs, nowMs)
            ?: throw IllegalStateException("Usage history unavailable")
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            foregroundPackageTracker.record(packageName, event.eventType)
        }
        lastQueryEndMs = nowMs
    }

    private fun isMapsCategory(packageName: String): Boolean =
        mapsCategoryCache.getOrPut(packageName) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                false
            } else {
                try {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0).category ==
                        ApplicationInfo.CATEGORY_MAPS
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }
}
