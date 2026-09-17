package cloud.adamrb.transom.ui.common

/**
 * Keeps codes and exception text off the screen. The server's 4xx `detail` and a recording's
 * `error` are sentences written for people and may be shown; "HTTP 409", OkHttp's "failed to
 * connect to ..." and anything mentioning an exception may not, and fall back to a fixed
 * sentence of ours. Pure Kotlin so the rule is unit-testable.
 */
object FriendlyErrors {

    private val HTTP_CODE = Regex("""^\s*HTTP\s*\d{3}""", RegexOption.IGNORE_CASE)
    private val TECHNICAL = Regex(
        """exception|stacktrace|\bjava\.|\bkotlin\.|okhttp|://|/api/|\berrno\b|\bECONN|\bEOF\b|timed? ?out\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when [raw] reads like something written for the user: begins with a capital letter,
     * has at least two words, is short, and carries no code, path, class name or exception
     * wording. The server's `detail` strings pass; transport errors and status codes do not.
     */
    fun isUserSentence(raw: String?): Boolean {
        val text = raw?.trim() ?: return false
        if (text.isEmpty() || text.length > 200) return false
        if (!text.first().isUpperCase()) return false
        if (!text.contains(' ')) return false
        if (HTTP_CODE.containsMatchIn(text)) return false
        if (TECHNICAL.containsMatchIn(text)) return false
        return true
    }

    /** [raw] when it is fit to show, else [fallback]. */
    fun forDisplay(raw: String?, fallback: String): String =
        if (isUserSentence(raw)) raw!!.trim() else fallback
}
