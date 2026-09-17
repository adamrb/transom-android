package io.github.adamrb.transom.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS-escaping for the token handed to localStorage: no token content may break out of the
 * single-quoted string literal in the injected script.
 */
class TokenInjectionTest {

    @Test
    fun plainTokenIsUntouched() {
        assertEquals("abc123-XYZ.foo~bar", TokenInjection.escapeJsString("abc123-XYZ.foo~bar"))
    }

    @Test
    fun singleQuotesAreEscaped() {
        assertEquals("a\\'b", TokenInjection.escapeJsString("a'b"))
    }

    @Test
    fun doubleQuotesAreEscaped() {
        assertEquals("a\\\"b", TokenInjection.escapeJsString("a\"b"))
    }

    @Test
    fun backslashesAreEscaped() {
        assertEquals("a\\\\b", TokenInjection.escapeJsString("a\\b"))
        // Trailing backslash must not swallow the closing quote
        assertEquals("abc\\\\", TokenInjection.escapeJsString("abc\\"))
    }

    @Test
    fun breakoutAttemptStaysInsideTheLiteral() {
        val evil = "'); localStorage.clear(); ('"
        // Every quote in the token must arrive escaped — the whole payload stays one literal
        assertEquals(
            "try{localStorage.setItem('pb_token','\\'); localStorage.clear(); (\\'');}catch(e){}",
            TokenInjection.setTokenScript(evil)
        )
    }

    @Test
    fun newlinesAndControlCharsAreEscaped() {
        assertEquals("a\\nb\\rc", TokenInjection.escapeJsString("a\nb\rc"))
        assertEquals("a\\u0000b\\u0009c", TokenInjection.escapeJsString("a\u0000b\tc"))
    }

    @Test
    fun jsLineSeparatorsAreEscaped() {
        // U+2028/U+2029 are line terminators in JS string literals
        assertEquals("a\\u2028b\\u2029c", TokenInjection.escapeJsString("a\u2028b\u2029c"))
    }

    @Test
    fun angleBracketIsEscaped() {
        assertEquals("a\\u003Cscript", TokenInjection.escapeJsString("a<script"))
    }

    @Test
    fun setTokenScriptEmbedsEscapedToken() {
        val script = TokenInjection.setTokenScript("t'k\\n")
        assertEquals("try{localStorage.setItem('pb_token','t\\'k\\\\n');}catch(e){}", script)
    }

    @Test
    fun ensureTokenScriptComparesAndSetsSameEscapedValue() {
        val script = TokenInjection.ensureTokenScript("a'b")
        // Same escaped literal is used for both the comparison and the set
        assertEquals(2, Regex("'a\\\\'b'").findAll(script).count())
        assertTrue(script.contains("return 'reload'"))
        assertTrue(script.contains("return 'ok'"))
    }
}
