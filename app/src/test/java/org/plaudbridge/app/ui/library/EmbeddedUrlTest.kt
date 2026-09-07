package org.plaudbridge.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedUrlTest {
    @Test
    fun appendsEmbeddedFlagToBareOrigin() {
        assertEquals("https://plaud.example.com?embedded=1", LibraryFragment.embeddedUrl("https://plaud.example.com"))
    }

    @Test
    fun appendsEmbeddedFlagKeepingPathAndExistingQuery() {
        assertEquals(
            "https://plaud.example.com/dash?x=1&embedded=1",
            LibraryFragment.embeddedUrl("https://plaud.example.com/dash?x=1"),
        )
    }
}
