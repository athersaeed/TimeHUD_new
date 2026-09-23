package com.boringutils.timehud.blocking

import android.content.Context
import androidx.core.content.edit
import com.boringutils.timehud.ui.usage.currentUsagePeriodStart

internal val APP_LIMIT_GRACE_MINUTES = listOf(3, 5, 10, 15, 20, 25, 30)
internal const val APP_LIMIT_EMERGENCY_GRACE_MINUTES = 3
internal const val APP_LIMIT_GRACE_COOLDOWN_MINUTES = 30

internal data class AppLimitGraceState(
    val usagePeriodStartMs: Long,
    val normalGraceEndMs: Long = 0L,
    val cooldownEndMs: Long = 0L,
    val emergencyGraceEndMs: Long = 0L,
    val emergencyGraceUsed: Boolean = false
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
        val emergencyAvailable: Boolean
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
        state.cooldownEndMs > nowMs -> AppLimitGraceStatus.Cooldown(
            endMs = state.cooldownEndMs,
            emergencyAvailable = !state.emergencyGraceUsed
        )
        else -> AppLimitGraceStatus.NormalAvailable
    }

    fun startNormal(
        usagePeriodStartMs: Long,
        durationMinutes: Int,
        nowMs: Long
    ): AppLimitGraceState {
        require(durationMinutes in APP_LIMIT_GRACE_MINUTES)
        val graceEndMs = nowMs + durationMinutes * MINUTE_MS
        return AppLimitGraceState(
            usagePeriodStartMs = usagePeriodStartMs,
            normalGraceEndMs = graceEndMs,
            cooldownEndMs = graceEndMs + APP_LIMIT_GRACE_COOLDOWN_MINUTES * MINUTE_MS
        )
    }

    fun startEmergency(state: AppLimitGraceState, nowMs: Long): AppLimitGraceState {
        val currentStatus = status(state, nowMs)
        require(currentStatus is AppLimitGraceStatus.Cooldown)
        require(currentStatus.emergencyAvailable)
        return state.copy(
            emergencyGraceEndMs = nowMs + APP_LIMIT_EMERGENCY_GRACE_MINUTES * MINUTE_MS,
            emergencyGraceUsed = true
        )
    }

    fun endActiveGrace(state: AppLimitGraceState, nowMs: Long): AppLimitGraceState =
        when (val currentStatus = status(state, nowMs)) {
            is AppLimitGraceStatus.Active -> when (currentStatus.kind) {
                AppLimitGraceKind.NORMAL -> state.copy(
                    normalGraceEndMs = nowMs,
                    cooldownEndMs = nowMs + APP_LIMIT_GRACE_COOLDOWN_MINUTES * MINUTE_MS
                )
                AppLimitGraceKind.EMERGENCY -> state.copy(emergencyGraceEndMs = nowMs)
            }
            AppLimitGraceStatus.NormalAvailable,
            is AppLimitGraceStatus.Cooldown -> state
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
        return AppLimitGraceState(
            usagePeriodStartMs = usagePeriodStartMs,
            normalGraceEndMs = preferences.getLong(NORMAL_END_KEY, 0L),
            cooldownEndMs = preferences.getLong(COOLDOWN_END_KEY, 0L),
            emergencyGraceEndMs = preferences.getLong(EMERGENCY_END_KEY, 0L),
            emergencyGraceUsed = preferences.getBoolean(EMERGENCY_USED_KEY, false)
        )
    }

    fun startNormal(context: Context, durationMinutes: Int, nowMs: Long): AppLimitGraceState {
        val state = load(context, nowMs)
        require(AppLimitGracePolicy.status(state, nowMs) == AppLimitGraceStatus.NormalAvailable)
        return AppLimitGracePolicy.startNormal(
            usagePeriodStartMs = state.usagePeriodStartMs,
            durationMinutes = durationMinutes,
            nowMs = nowMs
        ).also { save(context, it) }
    }

    fun startEmergency(context: Context, nowMs: Long): AppLimitGraceState =
        AppLimitGracePolicy.startEmergency(load(context, nowMs), nowMs)
            .also { save(context, it) }

    fun endActiveGrace(context: Context, nowMs: Long): AppLimitGraceState =
        AppLimitGracePolicy.endActiveGrace(load(context, nowMs), nowMs)
            .also { save(context, it) }

    private fun save(context: Context, state: AppLimitGraceState) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            putLong(PERIOD_START_KEY, state.usagePeriodStartMs)
            putLong(NORMAL_END_KEY, state.normalGraceEndMs)
            putLong(COOLDOWN_END_KEY, state.cooldownEndMs)
            putLong(EMERGENCY_END_KEY, state.emergencyGraceEndMs)
            putBoolean(EMERGENCY_USED_KEY, state.emergencyGraceUsed)
        }
    }
}
