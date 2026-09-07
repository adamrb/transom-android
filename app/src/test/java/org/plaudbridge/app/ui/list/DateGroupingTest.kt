package org.plaudbridge.app.ui.list

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Header wording and grouping shared by the Files and Library lists. */
class DateGroupingTest {

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 7, 14, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun daysAgo(n: Int, hour: Int = 9): Long =
        Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -n); set(Calendar.HOUR_OF_DAY, hour) }.timeInMillis

    @Test
    fun headerTitlesTodayYesterdayThenDate() {
        assertEquals("Today", DateGrouping.headerTitle(daysAgo(0, 1), now))
        assertEquals("Yesterday", DateGrouping.headerTitle(daysAgo(1, 23), now))
        val expected = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(java.util.Date(daysAgo(2)))
        assertEquals(expected, DateGrouping.headerTitle(daysAgo(2), now))
    }

    private data class Rec(val name: String, val at: Long)

    @Test
    fun groupsNewestFirstWithOneHeaderPerDay() {
        val items = listOf(
            Rec("old", daysAgo(5)),
            Rec("today-early", daysAgo(0, 8)),
            Rec("yesterday", daysAgo(1)),
            Rec("today-late", daysAgo(0, 12))
        )
        val rows = DateGrouping.group(items, now) { it.at }
        val flattened = rows.map {
            when (it) {
                is DateGrouping.Row.Header -> "H:${it.title}"
                is DateGrouping.Row.Item -> it.item.name
            }
        }
        val oldTitle = DateGrouping.headerTitle(daysAgo(5), now)
        assertEquals(
            listOf("H:Today", "today-late", "today-early", "H:Yesterday", "yesterday", "H:$oldTitle", "old"),
            flattened
        )
    }

    @Test
    fun emptyInputGivesNoRows() {
        assertEquals(emptyList<DateGrouping.Row<Rec>>(), DateGrouping.group(emptyList<Rec>(), now) { it.at })
    }

    @Test
    fun durationFormatMatchesFilesRows() {
        assertEquals("--", DateGrouping.formatDuration(0))
        assertEquals("1m 5s", DateGrouping.formatDuration(65))
        assertEquals("1h 1m", DateGrouping.formatDuration(3661))
    }
}
