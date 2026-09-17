package io.github.adamrb.transom.ui.filedetail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptPositionStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = TranscriptPositionStore(context.getSharedPreferences("test_positions", Context.MODE_PRIVATE))

    @Test
    fun encodesAndDecodesIndexAndOffset() {
        assertEquals("12:340", TranscriptPositionStore.encode(TranscriptPositionStore.Position(12, 340)))
        assertEquals(TranscriptPositionStore.Position(12, 340), TranscriptPositionStore.decode("12:340"))
        assertEquals(null, TranscriptPositionStore.decode(null))
        assertEquals(null, TranscriptPositionStore.decode("12"))
        assertEquals(null, TranscriptPositionStore.decode("a:b"))
        assertEquals(null, TranscriptPositionStore.decode("-1:0"))
        assertEquals(null, TranscriptPositionStore.decode("1:2:3"))
    }

    @Test
    fun savesLoadsAndForgetsPerRecording() {
        val store = store()
        store.save("server:a", TranscriptPositionStore.Position(7, 120))
        store.save("server:b", TranscriptPositionStore.Position(2, 0))
        assertEquals(TranscriptPositionStore.Position(7, 120), store.load("server:a"))
        assertEquals(TranscriptPositionStore.Position(2, 0), store.load("server:b"))
        assertEquals(null, store.load("server:c"))
        // Back at the top: nothing worth remembering, and an earlier place is forgotten.
        store.save("server:a", TranscriptPositionStore.Position(0, 0))
        assertEquals(null, store.load("server:a"))
        store.save("server:b", null)
        assertEquals(null, store.load("server:b"))
        // Scrolled a little into the first paragraph still counts.
        store.save("server:a", TranscriptPositionStore.Position(0, 80))
        assertEquals(TranscriptPositionStore.Position(0, 80), store.load("server:a"))
    }

    @Test
    fun theServerIdIsThePreferredKey() {
        val store = store()
        assertEquals("server:srv-9", store.keyFor("srv-9", "file-1"))
        assertEquals("file:file-1", store.keyFor(null, "file-1"))
        assertEquals("file:file-1", store.keyFor("  ", "file-1"))
        assertEquals(null, store.keyFor(null, null))
    }
}
