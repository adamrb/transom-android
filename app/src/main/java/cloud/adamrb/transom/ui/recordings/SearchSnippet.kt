package cloud.adamrb.transom.ui.recordings

/**
 * The one-line "why did this row match" text under a search result. A row whose title contains
 * the term needs none: the match is in plain sight. Otherwise the snippet is the server's
 * `match_snippet` when the row came back from a server-side search, else a window of the text
 * the phone has (preview, summary) around the first hit, so the user is never left staring at
 * a row that matched on words they cannot see. Pure Kotlin; the adapter turns [Snippet] into a
 * spannable with the matched term in bold.
 */
object SearchSnippet {

    /** [matchStart]/[matchEnd] index the term inside [text]; both -1 when it is not in there. */
    data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int) {
        val hasMatch: Boolean get() = matchStart >= 0
    }

    /** Characters kept on either side of the hit before the window is trimmed to word edges. */
    const val CONTEXT_CHARS = 60

    /** Markdown punctuation that summaries carry and a one-line snippet should not. */
    private val MARKUP = Regex("[#*`_>]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param query the search box text (blank means no search, so no snippet)
     * @param title the row's plain title; a hit there means no snippet
     * @param bodies texts the search also matched against, in order of preference
     * @param serverSnippet the server's ready-made snippet, when the row has one
     */
    fun derive(query: String?, title: String, bodies: List<String?>, serverSnippet: String? = null): Snippet? {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (title.contains(q, ignoreCase = true)) return null
        serverSnippet?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.let { s ->
            val at = s.indexOf(q, ignoreCase = true)
            return Snippet(s, at, if (at < 0) -1 else at + q.length)
        }
        for (body in bodies) {
            val text = body?.let { clean(it) } ?: continue
            val at = text.indexOf(q, ignoreCase = true)
            if (at < 0) continue
            return window(text, at, q.length)
        }
        return null
    }

    /** Collapse whitespace and drop markdown punctuation so the line reads as prose. */
    fun clean(text: String): String = text.replace(MARKUP, "").replace(WHITESPACE, " ").trim()

    /** Cut [text] to about [CONTEXT_CHARS] either side of the hit, snapped to word boundaries, with ellipses. */
    private fun window(text: String, at: Int, length: Int): Snippet {
        var start = (at - CONTEXT_CHARS).coerceAtLeast(0)
        if (start > 0) {
            val space = text.indexOf(' ', start)
            if (space in start until at) start = space + 1
        }
        var end = (at + length + CONTEXT_CHARS).coerceAtMost(text.length)
        if (end < text.length) {
            val space = text.lastIndexOf(' ', end)
            if (space > at + length) end = space
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val matchStart = prefix.length + (at - start)
        return Snippet(prefix + text.substring(start, end) + suffix, matchStart, matchStart + length)
    }
}
