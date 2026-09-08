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
    fun durationIsCompactLikeTheDetailScreen() {
        assertEquals("--", DateGrouping.formatDuration(0))
        assertEquals("--", DateGrouping.formatDuration(-5))
        assertEquals("16s", DateGrouping.formatDuration(16))
        assertEquals("1m 0s", DateGrouping.formatDuration(60))
        assertEquals("1m 5s", DateGrouping.formatDuration(65))
        assertEquals("4m 12s", DateGrouping.formatDuration(252))
        assertEquals("1h 1m", DateGrouping.formatDuration(3661))
        assertEquals("2h 19m", DateGrouping.formatDuration(2 * 3600 + 19 * 60 + 40))
        assertEquals("1h 0m", DateGrouping.formatDuration(3600))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun timestampsAreFriendlyWithNoSeconds() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            // now = Sep 7, 2026 14:00
            assertEquals("Today 12:25 AM", DateGrouping.formatDateTime(at(2026, Calendar.SEPTEMBER, 7, 0, 25), now))
            assertEquals("Yesterday 6:47 PM", DateGrouping.formatDateTime(at(2026, Calendar.SEPTEMBER, 6, 18, 47), now))
            assertEquals("Sep 3, 6:47 PM", DateGrouping.formatDateTime(at(2026, Calendar.SEPTEMBER, 3, 18, 47), now))
            assertEquals("Dec 24, 2025, 9:05 AM", DateGrouping.formatDateTime(at(2025, Calendar.DECEMBER, 24, 9, 5), now))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
