package org.plaudbridge.app.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line under a search result that shows where a hidden match came from. */
class SearchSnippetTest {

    private val summary = "## Summary\n\nWe went over the **quarterly budget** and agreed to move the launch " +
        "to October so the marketing team has time to prepare the campaign materials properly."

    @Test
    fun noSnippetWithoutAQueryOrWhenTheTitleAlreadyMatches() {
        assertNull(SearchSnippet.derive(null, "Budget call", listOf(summary)))
        assertNull(SearchSnippet.derive("  ", "Budget call", listOf(summary)))
        assertNull(SearchSnippet.derive("budget", "Budget call", listOf(summary)))
        assertNull(SearchSnippet.derive("BUDGET", "Quarterly budget", listOf(summary)))
    }

    @Test
    fun derivesAWindowAroundTheFirstHitWithMarkdownStripped() {
        val snippet = SearchSnippet.derive("october", "Planning", listOf(null, summary))!!
        assertTrue(snippet.text, snippet.text.contains("October"))
        assertFalse(snippet.text, snippet.text.contains("#"))
        assertFalse(snippet.text, snippet.text.contains("*"))
        assertFalse(snippet.text, snippet.text.contains("\n"))
        assertTrue(snippet.hasMatch)
        assertEquals("October", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun longTextIsTrimmedToWordEdgesWithEllipses() {
        val words = (1..80).map { "word$it" }
        val text = (words.take(40) + "needle" + words.drop(40)).joinToString(" ")
        val snippet = SearchSnippet.derive("needle", "Title", listOf(text))!!
        assertTrue(snippet.text, snippet.text.startsWith("…"))
        assertTrue(snippet.text, snippet.text.endsWith("…"))
        assertTrue(snippet.text.length < text.length)
        // Trimmed at spaces: the first and last tokens are whole words.
        val inner = snippet.text.trim('…')
        assertTrue(inner, inner.split(' ').first().matches(Regex("word\\d+")))
        assertTrue(inner, inner.split(' ').last().matches(Regex("word\\d+")))
        assertEquals("needle", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun shortTextIsShownWholeWithoutEllipses() {
        val snippet = SearchSnippet.derive("cat", "Title", listOf("The cat sat on the mat."))!!
        assertEquals("The cat sat on the mat.", snippet.text)
        assertEquals(4, snippet.matchStart)
        assertEquals(7, snippet.matchEnd)
    }

    @Test
    fun bodiesAreTriedInOrderAndAMissEverywhereGivesNothing() {
        val snippet = SearchSnippet.derive("mat", "Title", listOf("no hit here", "The cat sat on the mat."))!!
        assertTrue(snippet.text.contains("mat"))
        assertNull(SearchSnippet.derive("dog", "Title", listOf("no hit here", "The cat sat on the mat.", null)))
    }

    @Test
    fun serverSnippetWinsWhenPresent() {
        val snippet = SearchSnippet.derive("budget", "Title", listOf(summary), serverSnippet = "…about the budget for…")!!
        assertEquals("…about the budget for…", snippet.text)
        assertEquals("budget", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        // The server may snippet a stemmed hit the raw term is not in; the line still shows, unbolded.
        val stemmed = SearchSnippet.derive("budgets", "Title", listOf(summary), serverSnippet = "…the budget for…")!!
        assertEquals("…the budget for…", stemmed.text)
        assertFalse(stemmed.hasMatch)
    }
}
