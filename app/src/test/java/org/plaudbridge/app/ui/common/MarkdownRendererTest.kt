package org.plaudbridge.app.ui.common

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
