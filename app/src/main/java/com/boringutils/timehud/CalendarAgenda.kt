package com.boringutils.timehud

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TodayCalendarItem(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String,
    val calendarColor: Int?
)

object CalendarGoalSection {
    const val HEADER = "Calendar Today"

    fun removeFrom(goalText: String): String {
        val lines = goalText.lines()
        val sectionStart = lines.indexOfFirst { it.trim() == HEADER }
        if (sectionStart < 0) return goalText
        return lines.take(sectionStart)
            .joinToString(separator = "\n")
            .trimEnd()
    }
}

object CalendarAgenda {
    private val instanceProjection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.CALENDAR_COLOR,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY
    )

    fun hasReadCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    fun loadTodayVisibleInstances(context: Context): List<TodayCalendarItem> {
        if (!hasReadCalendarPermission(context)) return emptyList()

        val startMillis = todayStartMillis()
        val endMillis = tomorrowStartMillis(startMillis)
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMillis)
        ContentUris.appendId(uriBuilder, endMillis)

        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC, " +
            "${CalendarContract.Instances.END} ASC"

        return try {
            context.contentResolver.query(
                uriBuilder.build(),
                instanceProjection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.toTodayCalendarItem())
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    fun appendCalendarSection(shortTermGoals: String, items: List<TodayCalendarItem>): String {
        val manualGoals = CalendarGoalSection.removeFrom(shortTermGoals).trim()
        if (items.isEmpty()) return manualGoals

        val calendarSection = buildString {
            appendLine(CalendarGoalSection.HEADER)
            items.forEach { item ->
                appendLine(formatForGoals(item))
            }
        }.trim()

        return listOf(manualGoals, calendarSection)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
    }

    fun formatForGoals(item: TodayCalendarItem): String {
        val title = item.title.ifBlank { "Untitled event" }
        if (item.allDay) return "All day $title"

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val begin = formatter.format(Date(item.beginMillis))
        val end = formatter.format(Date(item.endMillis))
        return "$begin-$end $title"
    }

    private fun todayStartMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun tomorrowStartMillis(todayStartMillis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = todayStartMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun android.database.Cursor.toTodayCalendarItem(): TodayCalendarItem =
        TodayCalendarItem(
            eventId = getLong(0),
            calendarId = getLong(1),
            calendarName = getString(2).orEmpty().ifBlank { "Calendar" },
            calendarColor = if (isNull(3)) null else getInt(3),
            title = cleanTitle(getString(4)),
            beginMillis = getLong(5),
            endMillis = getLong(6),
            allDay = getInt(7) == 1
        )

    private fun cleanTitle(rawTitle: String?): String =
        rawTitle
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Untitled event" }
}
