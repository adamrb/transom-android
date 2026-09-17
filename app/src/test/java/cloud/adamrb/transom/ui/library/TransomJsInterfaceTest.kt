package cloud.adamrb.transom.ui.library

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * TransomJsInterface: the two hooks the dashboard may call. Work lands on the main looper,
 * oversized payloads are dropped, an untrusted main-frame URL blocks both calls, and the page's
 * filename is sanitized before it becomes a path under cacheDir/exports/.
 */
@RunWith(RobolectricTestRunner::class)
class TransomJsInterfaceTest {

    private lateinit var context: Context
    private val started = mutableListOf<Intent>()
    private var trusted = true

    private lateinit var bridge: TransomJsInterface

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cloud.adamrb.transom.export.FileProviderTestSupport.resetCache()
        started.clear()
        trusted = true
        bridge = TransomJsInterface(
            appContext = context,
            pageIsTrusted = { trusted },
            startActivity = { started.add(it) }
        )
        File(context.cacheDir, "exports").deleteRecursively()
    }

    private fun clipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun copyTextCopiesOnMainThreadAndToasts() {
        // Simulate the WebView's background JS thread: nothing happens until the main looper runs.
        val t = Thread { bridge.copyText("hello from the page") }
        t.start(); t.join()
        assertNull(clipboardText())
        drainMain()
        assertEquals("hello from the page", clipboardText())
        assertEquals("Transcript copied", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun copyTextIgnoredWhenPageIsNotTrusted() {
        trusted = false
        bridge.copyText("evil")
        drainMain()
        assertNull(clipboardText())
    }

    @Test
    fun oversizedPayloadsAreRejected() {
        val big = "x".repeat(TransomJsInterface.MAX_PAYLOAD_BYTES + 1)
        bridge.copyText(big)
        bridge.shareMarkdown("big.md", big)
        drainMain()
        assertNull(clipboardText())
        assertTrue(started.isEmpty())
        // Multi-byte text is measured in UTF-8 bytes, not chars
        val multiByte = "会".repeat(TransomJsInterface.MAX_PAYLOAD_BYTES / 3 + 1)
        bridge.copyText(multiByte)
        drainMain()
        assertNull(clipboardText())
    }

    @Test
    fun shareMarkdownWritesSanitizedFileAndOpensChooser() {
        bridge.shareMarkdown("../My \"Call\".md", "# My Call\n\nbody\n")
        drainMain()

        val exported = File(context.cacheDir, "exports/-My -Call-.md")
        assertTrue(exported.exists())
        assertEquals("# My Call\n\nbody\n", exported.readText())
        assertEquals(1, File(context.cacheDir, "exports").listFiles()!!.size)

        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/markdown", send.type)
        assertEquals("-My -Call-", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("# My Call\n\nbody\n", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM).toString().endsWith("/-My%20-Call-.md"))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun shareMarkdownIgnoredWhenPageIsNotTrusted() {
        trusted = false
        bridge.shareMarkdown("x.md", "# x\n")
        drainMain()
        assertTrue(started.isEmpty())
        assertTrue(File(context.cacheDir, "exports").listFiles().isNullOrEmpty())
    }
}
