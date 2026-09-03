package com.boringutils.timehud.blocking

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart

internal object AppBlockSettings {
    private const val PREFERENCES_NAME = "timehud_app_blocking"
    private const val RULE_PACKAGES_KEY = "rule_packages"
    private const val LIMIT_PREFIX = "limit_minutes:"
    private const val SURFACES_PREFIX = "blocked_surfaces:"
    // Read once for migration from the first Instagram-only implementation.
    private const val REELS_PREFIX = "block_reels:"
    private const val STORIES_PREFIX = "block_stories:"
    private const val MESSAGES_PREFIX = "allow_messages:"

    fun loadRules(context: Context): List<AppBlockRule> {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getStringSet(RULE_PACKAGES_KEY, emptySet()).orEmpty()
            .asSequence()
            .map { packageName ->
                val surfacesKey = SURFACES_PREFIX + packageName
                val blockedSurfaces = if (preferences.contains(surfacesKey)) {
                    preferences.getStringSet(surfacesKey, emptySet()).orEmpty()
                        .mapNotNull { storedName ->
                            AppSurface.entries.firstOrNull { it.name == storedName }
                        }
                        .toSet()
                } else {
                    buildSet {
                        if (preferences.getBoolean(REELS_PREFIX + packageName, false)) {
                            add(AppSurface.REELS)
                        }
                        if (preferences.getBoolean(STORIES_PREFIX + packageName, false)) {
                            add(AppSurface.STORIES)
                        }
                    }
                }
                AppBlockRule(
                    packageName = packageName,
                    dailyLimitMinutes = preferences.getInt(LIMIT_PREFIX + packageName, 0)
                        .takeIf { it > 0 },
                    blockedSurfaces = blockedSurfaces,
                    allowMessages = preferences.getBoolean(MESSAGES_PREFIX + packageName, true)
                )
            }
            .filter { it.isConfigured }
            .sortedBy { it.packageName }
            .toList()
    }

    fun ruleFor(context: Context, packageName: String): AppBlockRule? =
        loadRules(context).firstOrNull { it.packageName == packageName }

    fun saveRule(context: Context, rule: AppBlockRule) {
        if (!rule.isConfigured) {
            removeRule(context, rule.packageName)
            return
        }

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val packages = preferences.getStringSet(RULE_PACKAGES_KEY, emptySet()).orEmpty().toMutableSet()
        packages += rule.packageName
        preferences.edit {
            putStringSet(RULE_PACKAGES_KEY, packages)
            if (rule.dailyLimitMinutes != null) {
                putInt(LIMIT_PREFIX + rule.packageName, rule.dailyLimitMinutes)
            } else {
                remove(LIMIT_PREFIX + rule.packageName)
            }
            putStringSet(
                SURFACES_PREFIX + rule.packageName,
                rule.blockedSurfaces.mapTo(mutableSetOf()) { it.name }
            )
            putBoolean(MESSAGES_PREFIX + rule.packageName, rule.allowMessages)
            remove(REELS_PREFIX + rule.packageName)
            remove(STORIES_PREFIX + rule.packageName)
        }
    }

    fun removeRule(context: Context, packageName: String) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val packages = preferences.getStringSet(RULE_PACKAGES_KEY, emptySet()).orEmpty().toMutableSet()
        packages -= packageName
        preferences.edit {
            putStringSet(RULE_PACKAGES_KEY, packages)
            remove(LIMIT_PREFIX + packageName)
            remove(SURFACES_PREFIX + packageName)
            remove(REELS_PREFIX + packageName)
            remove(STORIES_PREFIX + packageName)
            remove(MESSAGES_PREFIX + packageName)
        }
    }
}

internal object AccessibilityServiceStatus {
    fun isEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, TimeHudAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { flattenedComponent ->
            ComponentName.unflattenFromString(flattenedComponent) == expectedComponent
        }
    }
}

internal object FocusedAppUsageStore {
    private const val PREFERENCES_NAME = "timehud_focused_app_usage"
    private const val PERIOD_START_KEY = "period_start"
    private const val SEEDED_PREFIX = "seeded:"
    private const val USAGE_PREFIX = "usage_ms:"

    fun seedIfNeeded(context: Context, packageName: String, usageMs: Long, nowMs: Long): Boolean {
        val preferences = preferencesForPeriod(context, nowMs)
        val seededKey = SEEDED_PREFIX + packageName
        if (preferences.getBoolean(seededKey, false)) return false
        val usageKey = USAGE_PREFIX + packageName
        preferences.edit {
            putBoolean(seededKey, true)
            putLong(
                usageKey,
                maxOf(preferences.getLong(usageKey, 0L), usageMs.coerceAtLeast(0L))
            )
        }
        return true
    }

    fun addFocusedUsage(context: Context, packageName: String, durationMs: Long, nowMs: Long) {
        if (durationMs <= 0L) return
        val preferences = preferencesForPeriod(context, nowMs)
        val key = USAGE_PREFIX + packageName
        preferences.edit {
            putLong(key, preferences.getLong(key, 0L) + durationMs)
        }
    }

    fun usageMs(context: Context, packageName: String, nowMs: Long): Long =
        preferencesForPeriod(context, nowMs).getLong(USAGE_PREFIX + packageName, 0L)

    fun isSeeded(context: Context, packageName: String, nowMs: Long): Boolean =
        preferencesForPeriod(context, nowMs).getBoolean(SEEDED_PREFIX + packageName, false)

    private fun preferencesForPeriod(context: Context, nowMs: Long) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).also { preferences ->
            val expectedPeriodStart = currentUsagePeriodStart(nowMs)
            if (preferences.getLong(PERIOD_START_KEY, Long.MIN_VALUE) != expectedPeriodStart) {
                preferences.edit {
                    clear()
                    putLong(PERIOD_START_KEY, expectedPeriodStart)
                }
            }
        }
}
