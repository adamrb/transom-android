package org.plaudbridge.app.ui.common

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Renders the server's AI summaries, which arrive as CommonMark (headings, bold, bullet and
 * numbered lists, the odd table), into styled text. One Markwon instance per process; it is
 * cheap to keep and expensive to build.
 */
object MarkdownRenderer {
    @Volatile
    private var markwon: Markwon? = null

    /**
     * Heading sizes relative to the body text. A summary sits under a 15sp section header on the
     * detail screen and its body is 14sp, so no heading may grow past that header: the largest
     * is a hair over body size, the rest are body size in bold. Headings still read as headings
     * (bold, on their own line), they just no longer outrank the page's own structure.
     */
    val HEADING_MULTIPLIERS = floatArrayOf(1.07f, 1f, 1f, 1f, 1f, 1f)

    private fun markwon(context: Context): Markwon =
        markwon ?: synchronized(this) {
            markwon ?: Markwon.builder(context.applicationContext)
                .usePlugin(object : AbstractMarkwonPlugin() {
                    // Summaries sit inside an already-labelled block: headings barely larger
                    // than body text and no rule under them, not document-sized titles.
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .headingBreakHeight(0)
                            .headingTextSizeMultipliers(HEADING_MULTIPLIERS)
                    }
                })
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context.applicationContext))
                .usePlugin(TaskListPlugin.create(context.applicationContext))
                .usePlugin(LinkifyPlugin.create())
                .build()
                .also { markwon = it }
        }

    private val summaryHeading = Regex("""\A\s*(?:#{1,6}\s*|\*\*)?summary\s*:?\s*(?:\*\*)?\s*:?\s*(?:\n|\z)""", RegexOption.IGNORE_CASE)

    /**
     * The summary without a leading "Summary" heading (any level, bold, or with a colon): the
     * screen already labels the block, so the model's own title would show twice.
     */
    fun withoutSummaryHeading(markdown: String): String = markdown.replaceFirst(summaryHeading, "").trimStart()

    /**
     * A line that reads "Highlights" and nothing else: a heading of any level (group 1 holds the
     * hashes), a bold-only line, or a plain "Highlights:" label above a list.
     */
    private val highlightsHeading = Regex("""^\s*(?:(#{1,6})\s*|\*\*)?highlights\s*:?\s*(?:\*\*)?\s*:?\s*$""", RegexOption.IGNORE_CASE)

    /** Any heading line, with its level in group 1 (bold-only lines count as a heading of level 7). */
    private val anyHeading = Regex("""^\s*(?:(#{1,6})\s+\S.*|\*\*[^*]+\*\*\s*:?\s*)$""")

    /** A list item, or a continuation line indented under one. */
    private val listLine = Regex("""^\s*(?:[-*+]|\d+[.)])\s+.*$|^\s+\S.*$""")

    /** The model's filler for an empty section ("No action items.", "**No action items**"). */
    private val emptySectionFiller = Regex("""^\s*(?:\*\*)?no (?:action items|highlights|decisions|follow[- ]ups)\.?(?:\*\*)?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * The summary without the model's filler for empty sections ("No action items."): older
     * stored summaries carry it (the server no longer writes it), and a line that says there is
     * nothing to say is nothing to show.
     */
    fun withoutEmptySectionFiller(markdown: String): String =
        markdown.lines().filterNot { emptySectionFiller.matches(it) }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * The summary without its "Highlights" section: the model tends to restate the recorder's
     * button-press highlights, which the screen already lists in its own Highlights rows. For a
     * heading (any level, or a bold-only line) the section runs to the next heading of the same
     * or a higher level, or to the end of the text; for a plain "Highlights:" label it runs
     * through the list under it. Other sections are untouched; a summary with no such section
     * comes back unchanged.
     */
    fun withoutHighlightsSection(markdown: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { highlightsHeading.matches(it) }
        if (start < 0) return markdown
        val match = highlightsHeading.find(lines[start])!!
        val isHeading = match.groupValues[1].isNotEmpty() || lines[start].trimStart().startsWith("**")
        var end = lines.size
        if (isHeading) {
            val level = match.groupValues[1].let { if (it.isEmpty()) 7 else it.length }
            for (i in start + 1 until lines.size) {
                val m = anyHeading.find(lines[i]) ?: continue
                val l = m.groupValues[1].let { if (it.isEmpty()) 7 else it.length }
                if (l <= level) {
                    end = i
                    break
                }
            }
        } else {
            // A plain label: take the blank lines and the list that follow it, stop at prose.
            var i = start + 1
            var sawItem = false
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) { i++; continue }
                if (listLine.matches(line)) { sawItem = true; i++; continue }
                break
            }
            if (!sawItem) return markdown // prose that happens to start with the word, not a section
            end = i
        }
        val kept = lines.subList(0, start) + lines.subList(end, lines.size)
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Styled text for [markdown]; plain text comes back unchanged apart from trimming. */
    fun render(context: Context, markdown: String): Spanned = markwon(context).toMarkdown(markdown.trim())

    /** Sets [markdown] on [view] as styled text, with tappable links. */
    fun setMarkdown(view: TextView, markdown: String) = markwon(view.context).setMarkdown(view, markdown.trim())

    /**
     * The summary as one line of plain text for list previews and search: markup rendered and
     * dropped, blank lines collapsed.
     */
    fun toPlainText(context: Context, markdown: String): String =
        render(context, markdown).toString().replace(Regex("\\s*\\n\\s*"), " ").trim()
}
