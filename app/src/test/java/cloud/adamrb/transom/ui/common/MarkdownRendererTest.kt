package cloud.adamrb.transom.ui.common

import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownRendererTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val summary = """
        ## Summary

        **Main point.** The speaker wants a *garage* inventory.

        - Two sticks of butter
        - Seven Kirkland seltzers

        1. Buy butter
        2. Check the freezer
    """.trimIndent()

    @Test
    fun rendersHeadingsBoldAndListsAsSpansWithoutMarkupCharacters() {
        val spanned = MarkdownRenderer.render(context, summary)
        val text = spanned.toString()
        assertFalse(text.contains("##"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("- Two"))
        assertTrue(text.contains("Main point."))
        assertTrue(text.contains("Two sticks of butter"))
        assertEquals(1, spanned.getSpans(0, spanned.length, HeadingSpan::class.java).size)
        assertEquals(1, spanned.getSpans(0, spanned.length, StrongEmphasisSpan::class.java).size)
        assertEquals(1, spanned.getSpans(0, spanned.length, EmphasisSpan::class.java).size)
        assertEquals(2, spanned.getSpans(0, spanned.length, BulletListItemSpan::class.java).size)
    }

    @Test
    fun dropsALeadingSummaryHeadingButKeepsOtherHeadings() {
        assertEquals("**Main point.** Text", MarkdownRenderer.withoutSummaryHeading("## Summary\n\n**Main point.** Text"))
        assertEquals("Body", MarkdownRenderer.withoutSummaryHeading("# Summary:\nBody"))
        assertEquals("Body", MarkdownRenderer.withoutSummaryHeading("**Summary**\n\nBody"))
        assertEquals("Body", MarkdownRenderer.withoutSummaryHeading("**Summary:**\n\nBody"))
        assertEquals("Body", MarkdownRenderer.withoutSummaryHeading("Summary:\nBody"))
        assertEquals("## Key points\n\nBody", MarkdownRenderer.withoutSummaryHeading("## Key points\n\nBody"))
        assertEquals("Summary of the call was short.", MarkdownRenderer.withoutSummaryHeading("Summary of the call was short."))
    }

    @Test
    fun headingsNeverOutrankTheScreensSectionHeaders() {
        // Summary body is 14sp under a 15sp section header: 14 * 1.07 < 15.
        assertTrue(MarkdownRenderer.HEADING_MULTIPLIERS.all { it * 14f < 15f })
        assertEquals(6, MarkdownRenderer.HEADING_MULTIPLIERS.size)
    }

    @Test
    fun dropsTheHighlightsSectionWhateverItsShape() {
        // A heading: through to the next heading of the same or a higher level.
        assertEquals(
            "Body\n\n## Action items\n\n- Do it",
            MarkdownRenderer.withoutHighlightsSection("Body\n\n## Highlights\n\n- One\n- Two\n\n## Action items\n\n- Do it")
        )
        // A heading followed only by lower-level headings takes them along, and ends at the text's end.
        assertEquals("Body", MarkdownRenderer.withoutHighlightsSection("Body\n\n# Highlights\n\n### At 0:06\n\nWords"))
        // A bold-only label: through to the next bold label or heading.
        assertEquals("Body\n\n**Next:**\n\nMore", MarkdownRenderer.withoutHighlightsSection("Body\n\n**Highlights:**\n\n- One\n\n**Next:**\n\nMore"))
        // A plain "Highlights:" line over a list: the label and the list go, the prose after stays.
        assertEquals(
            "Body\n\nClosing thought.",
            MarkdownRenderer.withoutHighlightsSection("Body\n\nHighlights:\n\n- At 0:06: One\n- At 0:14: Two\n\nClosing thought.")
        )
        // A plain label with no list under it is left alone (it is prose, not a section).
        assertEquals("Highlights:\nare what we live for.", MarkdownRenderer.withoutHighlightsSection("Highlights:\nare what we live for."))
        // No such section: unchanged.
        assertEquals("## Key points\n\nBody", MarkdownRenderer.withoutHighlightsSection("## Key points\n\nBody"))
        assertEquals("The highlights of the year.", MarkdownRenderer.withoutHighlightsSection("The highlights of the year."))
    }

    @Test
    fun dropsFillerForEmptySections() {
        assertEquals("Body", MarkdownRenderer.withoutEmptySectionFiller("Body\n\nNo action items."))
        assertEquals("Body\n\nMore", MarkdownRenderer.withoutEmptySectionFiller("Body\n\n**No action items**\n\nMore"))
        assertEquals("No action items were discussed at length.", MarkdownRenderer.withoutEmptySectionFiller("No action items were discussed at length."))
    }

    @Test
    fun plainTextComesBackUnchanged() {
        assertEquals("A greeting.", MarkdownRenderer.render(context, "A greeting.\n").toString())
    }

    @Test
    fun plainTextPreviewDropsMarkupAndCollapsesLines() {
        val plain = MarkdownRenderer.toPlainText(context, "## Title\n\n**Bold** words\n\n- one\n- two")
        assertEquals("Title Bold words one two", plain)
    }

    @Test
    fun setMarkdownStylesTheTextView() {
        val view = TextView(context)
        MarkdownRenderer.setMarkdown(view, "**Bold** point")
        val spanned = view.text as Spanned
        assertEquals("Bold point", spanned.toString())
        assertEquals(1, spanned.getSpans(0, 4, StrongEmphasisSpan::class.java).size)
    }
}
