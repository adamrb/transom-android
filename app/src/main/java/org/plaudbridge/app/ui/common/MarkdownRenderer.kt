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

    private fun markwon(context: Context): Markwon =
        markwon ?: synchronized(this) {
            markwon ?: Markwon.builder(context.applicationContext)
                .usePlugin(object : AbstractMarkwonPlugin() {
                    // Summaries sit inside an already-labelled block: headings a notch larger
                    // than body text and no rule under them, not document-sized titles.
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .headingBreakHeight(0)
                            .headingTextSizeMultipliers(floatArrayOf(1.25f, 1.15f, 1.1f, 1f, 1f, 1f))
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
