package com.boringutils.timehud

import android.content.Context
import androidx.core.content.edit

/**
 * The last periodic check-in bucket the HUD observed, scoped to the 3:00 AM
 * usage period and check-in interval it was computed for.
 */
internal data class CheckInBucketRecord(
    val periodStartMs: Long,
    val intervalMinutes: Int,
    val bucket: Long
)

internal object CheckInBucketRestorePolicy {
    const val NO_OBSERVED_BUCKET = -1L

    /**
     * Returns the bucket to resume from when the service first samples a usage
     * period, for example after a restart, reboot, or package update.
     *
     * - A record for the same period and interval resumes exactly, so a bucket that
     *   was already shown is not shown again.
     * - A record for the same period but a different interval uses the current
     *   bucket as the baseline, matching a live interval change.
     * - No record, or one from another period, keeps fresh-start behavior.
     */
    fun restore(
        record: CheckInBucketRecord?,
        periodStartMs: Long,
        intervalMinutes: Int,
        currentBucket: Long
    ): Long {
        if (record == null || record.periodStartMs != periodStartMs || record.bucket < 0L) {
            return NO_OBSERVED_BUCKET
        }
        return if (
            record.intervalMinutes == CheckInSettings.normalizeIntervalMinutes(intervalMinutes)
        ) {
            record.bucket
        } else {
            currentBucket
        }
    }
}

internal object CheckInBucketStore {
    private const val PREFS_NAME = "timehud_check_in_bucket"
    private const val KEY_PERIOD_START_MS = "period_start_ms"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_BUCKET = "bucket"

    fun load(context: Context): CheckInBucketRecord? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PERIOD_START_MS) || !prefs.contains(KEY_BUCKET)) return null
        return CheckInBucketRecord(
            periodStartMs = prefs.getLong(KEY_PERIOD_START_MS, 0L),
            intervalMinutes = prefs.getInt(KEY_INTERVAL_MINUTES, 0),
            bucket = prefs.getLong(KEY_BUCKET, CheckInBucketRestorePolicy.NO_OBSERVED_BUCKET)
        )
    }

    fun save(context: Context, record: CheckInBucketRecord) {
        prefs(context).edit {
            putLong(KEY_PERIOD_START_MS, record.periodStartMs)
            putInt(KEY_INTERVAL_MINUTES, record.intervalMinutes)
            putLong(KEY_BUCKET, record.bucket)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
