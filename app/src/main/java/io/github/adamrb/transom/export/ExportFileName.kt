package io.github.adamrb.transom.export

/**
 * Turns a title (or a filename the dashboard supplies) into a safe `<name>.md` for
 * `cacheDir/exports/`.
 *
 * The name ends up in a FileProvider URI and, after sharing, on whatever filesystem the target
 * app writes to, so it must not be able to escape the exports directory (no path separators, no
 * `..`, no leading dot) and should survive Windows/SMB targets (no `: * ? " < > |`). Control
 * characters are dropped rather than replaced because they carry no meaning in a name. Pure
 * Kotlin so the rules are unit-testable.
 */
object ExportFileName {

    /** Longest base name (before ".md"); keeps the full path well under common 255-byte limits. */
    const val MAX_BASE_LENGTH = 80

    const val EXTENSION = ".md"

    private const val FALLBACK = "transcript"

    private val unsafe = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(raw: String?): String {
        var base = (raw ?: "")
            .map { if (it == '\t' || it == '\n' || it == '\r') ' ' else it } // line breaks separate words
            .filter { it >= ' ' && it != '\u007F' } // strip remaining control chars (incl. NUL)
            .map { if (it in unsafe) '-' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Drop an extension the caller already added so we never produce "x.md.md".
        base = base.replace(Regex("(?i)\\.(md|markdown)$"), "").trimEnd()
        // No hidden files and no "." / ".." lookalikes.
        base = base.trimStart('.').trim()
        if (base.length > MAX_BASE_LENGTH) base = base.substring(0, MAX_BASE_LENGTH).trimEnd().trimEnd('.')
        // Nothing readable left (e.g. "///" became "---"): a generic name beats punctuation.
        if (base.none { it.isLetterOrDigit() }) base = FALLBACK
        return base + EXTENSION
    }
}
