package com.boringutils.timehud.blocking

import android.content.Context
import androidx.core.content.edit
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart

internal val APP_LIMIT_GRACE_MINUTES = listOf(3, 5, 10, 15, 20, 25, 30)
internal const val APP_LIMIT_EMERGENCY_GRACE_MINUTES = 3
internal const val APP_LIMIT_GRACE_COOLDOWN_MINUTES = 30
internal const val APP_LIMIT_EMERGENCY_GRACE_COOLDOWN_MINUTES = 30

internal fun formatGraceCooldownDuration(minutes: Int): String {
    val clamped = minutes.coerceAtLeast(1)
    val hours = clamped / 60
    val remainingMinutes = clamped % 60
    return when {
        hours > 0 && remainingMinutes > 0 -> "${hours}h ${remainingMinutes}m"
        hours > 0 -> "${hours}h"
        else -> "${remainingMinutes}m"
    }
}

internal object AppLimitGraceSettings {
    const val MIN_COOLDOWN_MINUTES = 30
    const val MAX_COOLDOWN_MINUTES = 12 * 60
    const val COOLDOWN_STEP_MINUTES = 15
    const val DEFAULT_COOLDOWN_MINUTES = APP_LIMIT_GRACE_COOLDOWN_MINUTES
    val COOLDOWN_OPTIONS_MINUTES: List<Int> =
        (MIN_COOLDOWN_MINUTES..MAX_COOLDOWN_MINUTES step COOLDOWN_STEP_MINUTES).toList()

    private const val PREFERENCES_NAME = "timehud_app_limit_grace_settings"
    private const val KEY_COOLDOWN_MINUTES = "cooldown_minutes"

    fun normalizeCooldownMinutes(cooldownMinutes: Int): Int =
        if (
            cooldownMinutes in MIN_COOLDOWN_MINUTES..MAX_COOLDOWN_MINUTES &&
            (cooldownMinutes - MIN_COOLDOWN_MINUTES) % COOLDOWN_STEP_MINUTES == 0
        ) {
            cooldownMinutes
        } else {
            DEFAULT_COOLDOWN_MINUTES
        }

    fun loadCooldownMinutes(context: Context): Int = normalizeCooldownMinutes(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES)
    )

    fun saveCooldownMinutes(context: Context, cooldownMinutes: Int) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_COOLDOWN_MINUTES, normalizeCooldownMinutes(cooldownMinutes))
        }
    }
}

internal data class AppLimitGraceState(
    val usagePeriodStartMs: Long,
    val normalGraceEndMs: Long = 0L,
    val cooldownEndMs: Long = 0L,
    val emergencyGraceEndMs: Long = 0L,
    val emergencyGraceUsed: Boolean = false,
    val emergencyCooldownEndMs: Long = 0L
)

internal enum class AppLimitGraceKind {
    NORMAL,
    EMERGENCY
}

internal sealed interface AppLimitGraceStatus {
    data object NormalAvailable : AppLimitGraceStatus

    data class Active(
        val kind: AppLimitGraceKind,
        val endMs: Long
    ) : AppLimitGraceStatus

    data class Cooldown(
        val endMs: Long,
        val emergencyAvailable: Boolean,
        val nextEmergencyAtMs: Long? = null
    ) : AppLimitGraceStatus
}

internal object AppLimitGracePolicy {
    private const val MINUTE_MS = 60_000L

    fun status(state: AppLimitGraceState, nowMs: Long): AppLimitGraceStatus = when {
        state.normalGraceEndMs > nowMs -> AppLimitGraceStatus.Active(
            kind = AppLimitGraceKind.NORMAL,
            endMs = state.normalGraceEndMs
        )
        state.emergencyGraceEndMs > nowMs -> AppLimitGraceStatus.Active(
            kind = AppLimitGraceKind.EMERGENCY,
            endMs = state.emergencyGraceEndMs
        )
        state.cooldownEndMs > nowMs -> {
            val nextEmergencyAvailableMs = when {
                state.emergencyCooldownEndMs > 0L -> state.emergencyCooldownEndMs
                state.emergencyGraceUsed -> state.cooldownEndMs
                else -> 0L
            }
            val emergencyAvailable = nextEmergencyAvailableMs <= nowMs
            AppLimitGraceStatus.Cooldown(
                endMs = state.cooldownEndMs,
                emergencyAvailable = emergencyAvailable,
                nextEmergencyAtMs = nextEmergencyAvailableMs
                    .takeIf { !emergencyAvailable && it < state.cooldownEndMs }
            )
        }
        else -> AppLimitGraceStatus.NormalAvailable
    }

    fun startNormal(
        usagePeriodStartMs: Long,
        durationMinutes: Int,
        nowMs: Long,
        cooldownMinutes: Int = APP_LIMIT_GRACE_COOLDOWN_MINUTES
    ): AppLimitGraceState {
        require(durationMinutes in APP_LIMIT_GRACE_MINUTES)
        val effectiveCooldownMinutes = AppLimitGraceSettings.normalizeCooldownMinutes(cooldownMinutes)
        val graceEndMs = nowMs + durationMinutes * MINUTE_MS
        return AppLimitGraceState(
            usagePeriodStartMs = usagePeriodStartMs,
            normalGraceEndMs = graceEndMs,
            cooldownEndMs = graceEndMs + effectiveCooldownMinutes * MINUTE_MS
        )
    }

    fun startEmergency(state: AppLimitGraceState, nowMs: Long): AppLimitGraceState {
        val currentStatus = status(state, nowMs)
        require(currentStatus is AppLimitGraceStatus.Cooldown)
        require(currentStatus.emergencyAvailable)
        val emergencyEndMs = nowMs + APP_LIMIT_EMERGENCY_GRACE_MINUTES * MINUTE_MS
        return state.copy(
            emergencyGraceEndMs = emergencyEndMs,
            emergencyGraceUsed = true,
            emergencyCooldownEndMs = emergencyEndMs + APP_LIMIT_EMERGENCY_GRACE_COOLDOWN_MINUTES * MINUTE_MS
        )
    }

    fun endActiveGrace(
        state: AppLimitGraceState,
        nowMs: Long,
        cooldownMinutes: Int = APP_LIMIT_GRACE_COOLDOWN_MINUTES
    ): AppLimitGraceState {
        val effectiveCooldownMinutes = AppLimitGraceSettings.normalizeCooldownMinutes(cooldownMinutes)
        return when (val currentStatus = status(state, nowMs)) {
            is AppLimitGraceStatus.Active -> when (currentStatus.kind) {
                AppLimitGraceKind.NORMAL -> state.copy(
                    normalGraceEndMs = nowMs,
                    cooldownEndMs = nowMs + effectiveCooldownMinutes * MINUTE_MS
                )
                AppLimitGraceKind.EMERGENCY -> state.copy(
                    emergencyGraceEndMs = nowMs,
                    emergencyCooldownEndMs = nowMs + APP_LIMIT_EMERGENCY_GRACE_COOLDOWN_MINUTES * MINUTE_MS
                )
            }
            AppLimitGraceStatus.NormalAvailable,
            is AppLimitGraceStatus.Cooldown -> state
        }
    }

    fun applyTo(decision: BlockDecision, status: AppLimitGraceStatus): BlockDecision = when {
        status !is AppLimitGraceStatus.Active -> decision
        decision !is BlockDecision.Block -> decision
        decision.reason == BlockReason.BRICK_MODE -> decision
        else -> BlockDecision.Allow
    }
}

internal object AppLimitGraceStore {
    private const val PREFERENCES_NAME = "timehud_app_limit_grace"
    private const val PERIOD_START_KEY = "usage_period_start"
    private const val NORMAL_END_KEY = "normal_grace_end"
    private const val COOLDOWN_END_KEY = "cooldown_end"
    private const val EMERGENCY_END_KEY = "emergency_grace_end"
    private const val EMERGENCY_USED_KEY = "emergency_grace_used"
    private const val EMERGENCY_COOLDOWN_END_KEY = "emergency_cooldown_end"

    fun load(context: Context, nowMs: Long): AppLimitGraceState {
        val usagePeriodStartMs = currentUsagePeriodStart(nowMs)
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getLong(PERIOD_START_KEY, Long.MIN_VALUE) != usagePeriodStartMs) {
            preferences.edit {
                clear()
                putLong(PERIOD_START_KEY, usagePeriodStartMs)
            }
            return AppLimitGraceState(usagePeriodStartMs)
        }
        val normalGraceEndMs = preferences.getLong(NORMAL_END_KEY, 0L)
        val cooldownEndMs = preferences.getLong(COOLDOWN_END_KEY, 0L)
        val emergencyGraceEndMs = preferences.getLong(EMERGENCY_END_KEY, 0L)
        val emergencyGraceUsed = preferences.getBoolean(EMERGENCY_USED_KEY, false)
        val emergencyCooldownEndMs = when {
            preferences.contains(EMERGENCY_COOLDOWN_END_KEY) ->
                preferences.getLong(EMERGENCY_COOLDOWN_END_KEY, 0L)
            emergencyGraceUsed && emergencyGraceEndMs > 0L ->
                emergencyGraceEndMs + APP_LIMIT_EMERGENCY_GRACE_COOLDOWN_MINUTES * 60_000L
            emergencyGraceUsed -> cooldownEndMs
            else -> 0L
        }
        return AppLimitGraceState(
            usagePeriodStartMs = usagePeriodStartMs,
            normalGraceEndMs = normalGraceEndMs,
            cooldownEndMs = cooldownEndMs,
            emergencyGraceEndMs = emergencyGraceEndMs,
            emergencyGraceUsed = emergencyGraceUsed,
            emergencyCooldownEndMs = emergencyCooldownEndMs
        )
    }

    fun startNormal(context: Context, durationMinutes: Int, nowMs: Long): AppLimitGraceState {
        val state = load(context, nowMs)
        require(AppLimitGracePolicy.status(state, nowMs) == AppLimitGraceStatus.NormalAvailable)
        val cooldownMinutes = AppLimitGraceSettings.loadCooldownMinutes(context)
        return AppLimitGracePolicy.startNormal(
            usagePeriodStartMs = state.usagePeriodStartMs,
            durationMinutes = durationMinutes,
            nowMs = nowMs,
            cooldownMinutes = cooldownMinutes
        ).also { save(context, it) }
    }

    fun startEmergency(context: Context, nowMs: Long): AppLimitGraceState =
        AppLimitGracePolicy.startEmergency(load(context, nowMs), nowMs)
            .also { save(context, it) }

    fun endActiveGrace(context: Context, nowMs: Long): AppLimitGraceState {
        val cooldownMinutes = AppLimitGraceSettings.loadCooldownMinutes(context)
        return AppLimitGracePolicy.endActiveGrace(
            state = load(context, nowMs),
            nowMs = nowMs,
            cooldownMinutes = cooldownMinutes
        ).also { save(context, it) }
    }

    private fun save(context: Context, state: AppLimitGraceState) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            putLong(PERIOD_START_KEY, state.usagePeriodStartMs)
            putLong(NORMAL_END_KEY, state.normalGraceEndMs)
            putLong(COOLDOWN_END_KEY, state.cooldownEndMs)
            putLong(EMERGENCY_END_KEY, state.emergencyGraceEndMs)
            putBoolean(EMERGENCY_USED_KEY, state.emergencyGraceUsed)
            putLong(EMERGENCY_COOLDOWN_END_KEY, state.emergencyCooldownEndMs)
        }
    }
}
