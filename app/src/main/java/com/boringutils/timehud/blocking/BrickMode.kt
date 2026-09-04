package com.boringutils.timehud.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit

internal data class BrickModeConfig(
    val enabled: Boolean = false,
    val allowedPackages: Set<String> = emptySet(),
    val endsAtEpochMs: Long? = null
) {
    fun isActive(nowMs: Long): Boolean =
        enabled && (endsAtEpochMs == null || endsAtEpochMs > nowMs)

    fun remainingMs(nowMs: Long): Long? = endsAtEpochMs
        ?.takeIf { enabled }
        ?.let { (it - nowMs).coerceAtLeast(0L) }
}

internal object BrickModeTimer {
    const val MAX_DURATION_MINUTES = 7 * 24 * 60

    fun endTimeEpochMs(nowMs: Long, durationMinutes: Int): Long? = durationMinutes
        .takeIf { it in 1..MAX_DURATION_MINUTES }
        ?.let { nowMs + it * 60_000L }

    fun resolveExpired(config: BrickModeConfig, nowMs: Long): BrickModeConfig =
        if (config.enabled && config.endsAtEpochMs != null && config.endsAtEpochMs <= nowMs) {
            config.copy(enabled = false, endsAtEpochMs = null)
        } else {
            config
        }
}

internal object BrickModeSettings {
    private const val PREFERENCES_NAME = "timehud_brick_mode"
    private const val ENABLED_KEY = "enabled"
    private const val ALLOWED_PACKAGES_KEY = "allowed_packages"
    private const val ENDS_AT_EPOCH_MS_KEY = "ends_at_epoch_ms"

    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): BrickModeConfig {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val storedConfig = BrickModeConfig(
            enabled = preferences.getBoolean(ENABLED_KEY, false),
            allowedPackages = preferences.getStringSet(ALLOWED_PACKAGES_KEY, emptySet())
                .orEmpty()
                .filterTo(mutableSetOf()) { it.isNotBlank() },
            endsAtEpochMs = preferences.getLong(ENDS_AT_EPOCH_MS_KEY, 0L)
                .takeIf { it > 0L }
        )
        val resolvedConfig = BrickModeTimer.resolveExpired(storedConfig, nowMs)
        if (storedConfig.enabled && !resolvedConfig.enabled) {
            preferences.edit {
                putBoolean(ENABLED_KEY, false)
                remove(ENDS_AT_EPOCH_MS_KEY)
            }
        }
        return resolvedConfig
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED_KEY, enabled)
            remove(ENDS_AT_EPOCH_MS_KEY)
        }
    }

    fun startTimed(
        context: Context,
        durationMinutes: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val endsAtEpochMs = BrickModeTimer.endTimeEpochMs(nowMs, durationMinutes) ?: return false
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED_KEY, true)
            putLong(ENDS_AT_EPOCH_MS_KEY, endsAtEpochMs)
        }
        return true
    }

    fun setPackageAllowed(context: Context, packageName: String, allowed: Boolean) {
        if (packageName.isBlank()) return
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val packages = preferences.getStringSet(ALLOWED_PACKAGES_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        if (allowed) packages += packageName else packages -= packageName
        preferences.edit { putStringSet(ALLOWED_PACKAGES_KEY, packages) }
    }
}

internal data class BrickModeApp(
    val packageName: String,
    val appName: String,
    val alwaysAvailable: Boolean
)

internal data class BrickModeCatalog(
    val apps: List<BrickModeApp> = emptyList()
) {
    val launchablePackages: Set<String> = apps.mapTo(mutableSetOf()) { it.packageName }
    val alwaysAvailablePackages: Set<String> = apps.asSequence()
        .filter(BrickModeApp::alwaysAvailable)
        .mapTo(mutableSetOf()) { it.packageName }
}

internal object BrickModeCatalogLoader {
    fun load(context: Context): BrickModeCatalog {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackages = packageManager.queryIntentActivitiesCompat(homeIntent)
            .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
        val settingsPackage = packageManager.resolveActivityCompat(Intent(Settings.ACTION_SETTINGS))
            ?.activityInfo
            ?.packageName
        val dynamicallyProtected = buildSet {
            add(context.packageName)
            addAll(homePackages)
            settingsPackage?.let(::add)
        }

        val apps = packageManager.queryIntentActivitiesCompat(launcherIntent)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName
                    ?.takeIf { it != context.packageName }
                    ?: return@mapNotNull null
                val appName = resolveInfo.loadLabel(packageManager).toString()
                    .takeIf { it.isNotBlank() }
                    ?: packageName
                BrickModeApp(
                    packageName = packageName,
                    appName = appName,
                    alwaysAvailable = EssentialAppPolicy.isAlwaysAvailable(
                        packageName = packageName,
                        appName = appName,
                        dynamicallyProtectedPackages = dynamicallyProtected
                    )
                )
            }
            .distinctBy(BrickModeApp::packageName)
            .sortedWith(
                compareByDescending<BrickModeApp>(BrickModeApp::alwaysAvailable)
                    .thenBy { it.appName.lowercase() }
            )
            .toList()
        return BrickModeCatalog(apps)
    }
}

internal object BrickModeDecisionEngine {
    fun decide(
        config: BrickModeConfig,
        packageName: String,
        catalog: BrickModeCatalog,
        nowMs: Long = System.currentTimeMillis()
    ): BlockDecision = when {
        !config.isActive(nowMs) -> BlockDecision.Allow
        packageName !in catalog.launchablePackages -> BlockDecision.Allow
        packageName in catalog.alwaysAvailablePackages -> BlockDecision.Allow
        packageName in config.allowedPackages -> BlockDecision.Allow
        else -> BlockDecision.Block(BlockReason.BRICK_MODE)
    }
}

internal object AppControlDecisionEngine {
    fun decide(
        brickModeDecision: BlockDecision,
        appBlockRule: AppBlockRule?,
        focusedUsageMs: Long,
        surface: AppSurface
    ): BlockDecision {
        if (brickModeDecision is BlockDecision.Block) return brickModeDecision
        return appBlockRule?.let { rule ->
            AppBlockDecisionEngine.decide(rule, focusedUsageMs, surface)
        } ?: BlockDecision.Allow
    }
}

internal object EssentialAppPolicy {
    private val screenshotPackageNames = setOf(
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.google.android.apps.wellbeing",
        "com.samsung.android.forest",
        "com.android.vending",
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "bitpit.launcher",
        "com.microsoft.office.outlook",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.settings"
    )

    private val screenshotAppNames = setOf(
        "clock",
        "digital detox",
        "digital wellbeing",
        "google play store",
        "google wallet",
        "lock my phone",
        "messages",
        "niagara launcher",
        "outlook",
        "phone",
        "settings"
    )

    fun isAlwaysAvailable(
        packageName: String,
        appName: String,
        dynamicallyProtectedPackages: Set<String> = emptySet()
    ): Boolean = packageName in dynamicallyProtectedPackages ||
        packageName in screenshotPackageNames ||
        appName.trim().lowercase() in screenshotAppNames
}

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }

private fun PackageManager.resolveActivityCompat(intent: Intent): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, 0)
    }
