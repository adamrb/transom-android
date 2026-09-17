package cloud.adamrb.transom.ui.list

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Day grouping shared by the Recordings tab and Home's recent list so both read the same way:
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
    fun headerTitle(dateMillis: Long, now: Long = System.currentTimeMillis()): String =
        relativeDay(dateMillis, now) ?: SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(dateMillis))

    /** "Today" / "Yesterday" when [dateMillis] falls on those calendar days relative to [now], else null. */
    private fun relativeDay(dateMillis: Long, now: Long): String? {
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dayKey = dayFormat.format(Date(dateMillis))
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        return when (dayKey) {
            dayFormat.format(today.time) -> "Today"
            dayFormat.format(yesterday.time) -> "Yesterday"
            else -> null
        }
    }

    /**
     * A friendly timestamp, the same one everywhere a recording's time is printed: "Today
     * 12:25 AM", "Yesterday 6:47 PM", "Sep 3, 6:47 PM", and with the year only once it is not
     * this one ("Sep 3, 2025, 6:47 PM"). No seconds.
     */
    fun formatDateTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        val date = Date(millis)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        relativeDay(millis, now)?.let { return "$it $time" }
        val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        val year = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
        val day = if (year == thisYear) SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
        else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        return "$day, $time"
    }

    /**
     * Compact duration, the same form the detail screen uses: "16s" under a minute, "4m 12s"
     * under an hour, "2h 19m" from an hour on (seconds dropped there: nobody needs them on a two
     * hour recording). "--" when the length is not known yet.
     */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** The " · " joiner used between meta fields on a row. */
    const val SEPARATOR = "  ·  "
}
