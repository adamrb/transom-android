package io.github.adamrb.transom.ui.library

/**
 * Builds the JavaScript snippets that hand the server bearer token to the dashboard SPA
 * (it reads localStorage key "pb_token"). Pure string work — kept out of the Fragment so the
 * escaping is unit-testable on the JVM.
 */
object TokenInjection {

    const val STORAGE_KEY = "pb_token"

    /**
     * Escape [value] for embedding inside a single-quoted JS string literal. Escapes the JS
     * metacharacters (backslash, both quote kinds, line terminators incl. U+2028/U+2029) and
     * every remaining control char / `<` as \\uXXXX, so no token content can break out of the
     * literal regardless of what the server put in it.
     */
    fun escapeJsString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '\'' -> sb.append("\\'")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\u2028' || c == '\u2029' || c == '<' || c < ' ' ->
                    sb.append("\\u%04X".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Early best-effort set (run at onPageStarted): store the token before the SPA boots. */
    fun setTokenScript(token: String): String =
        "try{localStorage.setItem('$STORAGE_KEY','${escapeJsString(token)}');}catch(e){}"

    /**
     * Post-load check (run at onPageFinished): if the stored token differs from the configured
     * one — the SPA booted before the onPageStarted set landed, or the token changed — set it
     * and return "reload" so the caller can reload the page once. Returns a JSON-quoted string
     * ("reload" / "ok" / "error") via evaluateJavascript.
     */
    fun ensureTokenScript(token: String): String {
        val escaped = escapeJsString(token)
        return "(function(){try{" +
            "if(localStorage.getItem('$STORAGE_KEY')!=='$escaped'){" +
            "localStorage.setItem('$STORAGE_KEY','$escaped');return 'reload';}" +
            "return 'ok';}catch(e){return 'error';}})()"
    }
}
