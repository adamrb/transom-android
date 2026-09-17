package io.github.adamrb.transom.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor text format must parse exactly like the dashboard's parseVocabEditor and print
 * exactly like the server's to_editor_text, or a list edited on the phone would drift from one
 * edited in the browser.
 */
@RunWith(RobolectricTestRunner::class)
class VocabularyEditorTextTest {

    @Test
    fun parsesTermsAliasesCommentsAndBlankLines() {
        val text = """
            |# names
            |
            |Parrot Deck = Parted Deck, Carrot Deck
            |  Morgan
            |Kahlúa=Kalua ,  Kaluha,
            |   = orphan alias
            |""".trimMargin()
        assertEquals(
            listOf(
                VocabEntry("Parrot Deck", listOf("Parted Deck", "Carrot Deck"), "manual"),
                VocabEntry("Morgan", emptyList(), "manual"),
                VocabEntry("Kahlúa", listOf("Kalua", "Kaluha"), "manual")
            ),
            VocabularyEditorText.parse(text)
        )
    }

    @Test
    fun splitsOnTheFirstEqualsOnly() {
        // "A = B = C" is term "A" with the single alias "B = C", like the dashboard's split(/=(.*)/s).
        assertEquals(
            listOf(VocabEntry("A", listOf("B = C"), "manual")),
            VocabularyEditorText.parse("A = B = C")
        )
    }

    @Test
    fun preservesSourceOfExistingTermsCaseInsensitively() {
        val existing = listOf(
            VocabEntry("Morgan", emptyList(), "obsidian"),
            VocabEntry("Parrot Deck", listOf("Parted Deck"), "manual")
        )
        val parsed = VocabularyEditorText.parse("morgan = Morgen\nParrot Deck\nNew Term", existing)
        assertEquals(
            listOf(
                VocabEntry("morgan", listOf("Morgen"), "obsidian"),
                VocabEntry("Parrot Deck", emptyList(), "manual"),
                VocabEntry("New Term", emptyList(), "manual")
            ),
            parsed
        )
    }

    @Test
    fun formatMatchesServerToEditorText() {
        val entries = listOf(
            VocabEntry("plaud", listOf("plod")),
            VocabEntry("Morgan"),
            VocabEntry("Alex", listOf("Alec", "Alix"), "obsidian")
        )
        // Sorted case-insensitively; aliases after " = ", comma-space separated.
        assertEquals("Alex = Alec, Alix\nMorgan\nplaud = plod", VocabularyEditorText.format(entries))
        assertEquals("", VocabularyEditorText.format(emptyList()))
    }

    @Test
    fun roundTripsThroughFormatAndParse() {
        val entries = listOf(
            VocabEntry("Alex", listOf("Alec", "Alix"), "obsidian"),
            VocabEntry("Morgan", emptyList(), "manual"),
            VocabEntry("Parrot Deck", listOf("Parted Deck"), "manual")
        )
        val text = VocabularyEditorText.format(entries)
        assertEquals(entries, VocabularyEditorText.parse(text, existing = entries))
        assertEquals(text, VocabularyEditorText.format(VocabularyEditorText.parse(text, entries)))
    }

    @Test
    fun jsonMappingRoundTrips() {
        val e = VocabEntry("Parrot Deck", listOf("Parted Deck"), "obsidian")
        assertEquals(e, VocabEntry.fromJson(e.toJson()))
        assertEquals("""{"term":"Parrot Deck","aliases":["Parted Deck"],"source":"obsidian"}""", e.toJson().toString())
        // Missing aliases/source fall back to an empty list and "manual".
        assertEquals(VocabEntry("X", emptyList(), "manual"), VocabEntry.fromJson(JSONObject("""{"term":"X"}""")))
        assertTrue(VocabEntry.listFromJson(null).isEmpty())
    }
}
