package org.plaudbridge.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server sentences may reach the screen; codes, paths and exception text may not. */
class FriendlyErrorsTest {

    @Test
    fun serverDetailSentencesPass() {
        assertTrue(FriendlyErrors.isUserSentence("Automations are turned off on the server"))
        assertTrue(FriendlyErrors.isUserSentence("This recording has no transcript yet."))
        assertTrue(FriendlyErrors.isUserSentence("  Recording is still being transcribed  "))
    }

    @Test
    fun codesAndTechnicalTextDoNot() {
        assertFalse(FriendlyErrors.isUserSentence("HTTP 409"))
        assertFalse(FriendlyErrors.isUserSentence("HTTP 500: upload rejected"))
        assertFalse(FriendlyErrors.isUserSentence("network error"))
        assertFalse(FriendlyErrors.isUserSentence("failed to connect to bridge.example.com/10.0.0.5 (port 443)"))
        assertFalse(FriendlyErrors.isUserSentence("java.net.SocketTimeoutException: timeout"))
        assertFalse(FriendlyErrors.isUserSentence("Unexpected exception while routing"))
        assertFalse(FriendlyErrors.isUserSentence("Connection timed out"))
        assertFalse(FriendlyErrors.isUserSentence("See https://example.com/docs"))
        assertFalse(FriendlyErrors.isUserSentence("Unauthorized"))
        assertFalse(FriendlyErrors.isUserSentence(""))
        assertFalse(FriendlyErrors.isUserSentence(null))
        assertFalse(FriendlyErrors.isUserSentence("A ".repeat(150)))
    }

    @Test
    fun forDisplayFallsBackToOurSentence() {
        assertEquals("Couldn't reach your server.", FriendlyErrors.forDisplay("HTTP 409", "Couldn't reach your server."))
        assertEquals("Automations are turned off on the server",
            FriendlyErrors.forDisplay(" Automations are turned off on the server ", "fallback"))
    }
}
