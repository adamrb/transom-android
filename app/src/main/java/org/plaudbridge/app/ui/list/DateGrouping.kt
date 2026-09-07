package org.plaudbridge.app.ui.list

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Day grouping shared by the Files tab and the Library tab so both lists read the same way:
 * newest first, one "Today" / "Yesterday" / "EEE, MMM d" header per calendar day. Pure Kotlin
 * so the header rules are unit-testable; the RecyclerView side lives in [DateGroupedAdapter].
 */
object DateGrouping {

    sealed class Row<out T> {
        data class Header(val title: String) : Row<Nothing>()
        data class Item<T>(val item: T) : Row<T>()
    }

    /**
     * Sort [items] newest first by [timestamp] and insert a header before each new calendar day.
     * [now] is injectable so tests can pin what "Today" means.
     */
    fun <T> group(items: List<T>, now: Long = System.currentTimeMillis(), timestamp: (T) -> Long): List<Row<T>> {
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val rows = mutableListOf<Row<T>>()
        var lastDayKey = ""
        for (item in items.sortedByDescending(timestamp)) {
            val at = timestamp(item)
            val dayKey = dayFormat.format(Date(at))
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey
                rows.add(Row.Header(headerTitle(at, now)))
            }
            rows.add(Row.Item(item))
        }
        return rows
    }

    /** "Today", "Yesterday", or "EEE, MMM d" for the calendar day containing [dateMillis]. */
    fun headerTitle(dateMillis: Long, now: Long = System.currentTimeMillis()): String {
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dayKey = dayFormat.format(Date(dateMillis))
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        return when (dayKey) {
            dayFormat.format(today.time) -> "Today"
            dayFormat.format(yesterday.time) -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(dateMillis))
        }
    }

    /** "MMM d  ·  HH:mm" as the Files rows print it. */
    fun formatDateTime(millis: Long): String {
        val date = Date(millis)
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${dateFormat.format(date)}  ·  ${timeFormat.format(date)}"
    }

    /** "Xm Ys" below an hour, "Xh Ym" from an hour on, "--" when unknown (the Files row format). */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "--"
        val total = seconds.toInt()
        return if (total >= 3600) {
            String.format("%dh %dm", total / 3600, (total % 3600) / 60)
        } else {
            String.format("%dm %ds", total / 60, total % 60)
        }
    }

    /** The " · " joiner used between meta fields on a row. */
    const val SEPARATOR = "  ·  "
}
