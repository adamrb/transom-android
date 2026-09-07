package org.plaudbridge.app.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.BuildConfig
import org.plaudbridge.app.R
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * QrLoginActivity against a fake approver: a code for the configured origin asks for
 * confirmation and then approves with the id from the code; a code for any other origin is
 * refused with both hosts named and never reaches the approver; each approve result maps to
 * its message. Codes are fed through [QrLoginActivity.handleScannedCode], the same entry point
 * the scanner result uses; the debug-only intent extra (what a camera-less emulator uses) gets
 * its own test, which also checks that release builds ignore it.
 */
@RunWith(RobolectricTestRunner::class)
class QrLoginActivityTest {

    private val id = "abcDEF123456_-abcDEF123456_-0123"

    private class FakeApprover(var result: ApiClient.ApproveLoginResult) : QrLoginActivity.LoginApprover {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun approve(requestId: String, label: String): ApiClient.ApproveLoginResult {
            calls += requestId to label
            return result
        }
    }

    private lateinit var fake: FakeApprover

    @Before
    fun setUp() {
        RecordingStore.init(ApplicationProvider.getApplicationContext())
        RecordingStore.clearAll()
        RecordingStore.serverBaseUrl = "https://bridge.example.com"
        RecordingStore.serverAuthToken = "tok"
        fake = FakeApprover(ApiClient.ApproveLoginResult.Ok)
        QrLoginActivity.approver = fake
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        ShadowDialog.reset()
    }

    private fun code(url: String, requestId: String = id) = """{"v":1,"kind":"login","url":"$url","id":"$requestId"}"""

    private fun launch(scanned: String): QrLoginActivity {
        val activity = Robolectric.buildActivity(QrLoginActivity::class.java).setup().get()
        activity.handleScannedCode(scanned)
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    /** The zxing CaptureActivity intent the screen started, if any. */
    private fun startedScanner(activity: QrLoginActivity): Intent? =
        shadowOf(activity).nextStartedActivityForResult?.intent

    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun dialogMessage(): String = latestDialog().findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun clickPositive() {
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun status(activity: QrLoginActivity) = activity.findViewById<TextView>(R.id.statusLabel).text.toString()

    // MARK: - Entry: the camera opens by itself; the debug extra replaces it in debug builds only

    @Test
    fun openingTheScreenLaunchesTheScannerWithTheSignInPrompt() {
        val activity = Robolectric.buildActivity(QrLoginActivity::class.java).setup().get()
        val scanner = startedScanner(activity)
        assertEquals("com.journeyapps.barcodescanner.CaptureActivity", scanner?.component?.className)
        assertEquals("Scan the sign-in code from the dashboard", scanner?.getStringExtra("PROMPT_MESSAGE"))
        assertEquals("Your server: bridge.example.com:443", activity.findViewById<TextView>(R.id.serverLabel).text.toString())
    }

    @Test
    fun debugExtraFeedsTheFlowInsteadOfTheCameraAndIsIgnoredInRelease() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), QrLoginActivity::class.java)
            .putExtra(QrLoginActivity.EXTRA_DEBUG_SCANNED, code("https://bridge.example.com"))
        val activity = Robolectric.buildActivity(QrLoginActivity::class.java, intent).setup().get()
        if (BuildConfig.DEBUG) {
            assertNull("debug: the extra stands in for the camera", startedScanner(activity))
            assertEquals("It will get access to your recordings on bridge.example.com.", dialogMessage())
        } else {
            assertNull("release: nothing outside the camera may feed the flow", ShadowDialog.getLatestDialog())
            assertEquals("com.journeyapps.barcodescanner.CaptureActivity", startedScanner(activity)?.component?.className)
        }
        assertTrue(fake.calls.isEmpty())
    }

    // MARK: - Same origin: confirm, then approve with the id from the code

    @Test
    fun sameOriginAsksThenApprovesWithTheCodesIdAndFinishes() {
        val activity = launch(code("https://bridge.example.com"))
        assertEquals("It will get access to your recordings on bridge.example.com.", dialogMessage())
        assertTrue("nothing may be sent before the user confirms", fake.calls.isEmpty())

        clickPositive()
        assertEquals(listOf(id to QrLoginActivity.sessionLabel()), fake.calls)
        assertTrue(fake.calls[0].second.startsWith("Web · "))
        assertEquals("Signed in. The computer will open the dashboard in a moment.", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun cancelInTheConfirmationSendsNothing() {
        val activity = launch(code("https://bridge.example.com"))
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fake.calls.isEmpty())
        assertFalse(activity.isFinishing)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun textuallyDifferentUrlForTheSameOriginIsApproved() {
        // Upper-case host, explicit default port, sub-path: still the configured server.
        RecordingStore.serverBaseUrl = "https://bridge.example.com/bridge"
        launch(code("https://BRIDGE.example.com:443/"))
        clickPositive()
        assertEquals(id, fake.calls.single().first)
    }

    // MARK: - Other origin: refuse, name both hosts, never call the approver

    @Test
    fun otherHostIsRefusedWithBothHostsNamedAndNothingIsSent() {
        val activity = launch(code("https://evil.example.com"))
        assertEquals(
            "This code is for a different server (evil.example.com:443). Only codes from your server (bridge.example.com:443) can be approved.",
            dialogMessage()
        )
        clickPositive() // OK on the explanation
        assertTrue(fake.calls.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun otherPortIsRefused() {
        launch(code("https://bridge.example.com:8443"))
        assertTrue(dialogMessage(), dialogMessage().startsWith("This code is for a different server (bridge.example.com:8443)."))
        assertTrue(fake.calls.isEmpty())
    }

    // MARK: - Not a sign-in code

    @Test
    fun setupCodeIsExplainedAsSuchWithoutADialog() {
        val activity = launch("""{"v":1,"url":"https://bridge.example.com","token":"secret"}""")
        assertNull(ShadowDialog.getLatestDialog())
        assertEquals("This is a server setup code, not a sign-in code. To change servers, use Your server above.", status(activity))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun garbageIsReportedWithTheParserReason() {
        val activity = launch("https://bridge.example.com/whatever")
        assertEquals("Code not recognized: not a Plaud Bridge sign-in code", status(activity))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun malformedIdNeverReachesTheApprover() {
        val activity = launch(code("https://bridge.example.com", requestId = "../../admin"))
        assertNull(ShadowDialog.getLatestDialog())
        assertEquals("Code not recognized: malformed request id", status(activity))
        assertTrue(fake.calls.isEmpty())
    }

    // MARK: - Approve results

    @Test
    fun expiredCodeSaysToReloadAndScanAgain() {
        fake.result = ApiClient.ApproveLoginResult.Expired
        val activity = launch(code("https://bridge.example.com"))
        clickPositive()
        assertEquals("That code expired. Reload the dashboard page and scan again.", status(activity))
        assertFalse(activity.isFinishing)
        assertTrue(activity.findViewById<Button>(R.id.scanButton).isEnabled)
    }

    @Test
    fun usedCodeSaysSo() {
        fake.result = ApiClient.ApproveLoginResult.AlreadyUsed
        val activity = launch(code("https://bridge.example.com"))
        clickPositive()
        assertEquals("That code was already used.", status(activity))
    }

    @Test
    fun authErrorUsesTheTokenRejectedMessage() {
        fake.result = ApiClient.ApproveLoginResult.AuthError(401)
        val activity = launch(code("https://bridge.example.com"))
        clickPositive()
        assertEquals("The server rejected the access token. Check it in Settings.", status(activity))
    }

    @Test
    fun networkErrorUsesTheUnreachableMessage() {
        fake.result = ApiClient.ApproveLoginResult.Error("Failed to connect to /127.0.0.1:1")
        val activity = launch(code("https://bridge.example.com"))
        clickPositive()
        assertEquals("Couldn't reach your server. Check that it's running and that you're online.", status(activity))
    }

    @Test
    fun unexpectedHttpStatusIsNamed() {
        fake.result = ApiClient.ApproveLoginResult.Error("HTTP 500")
        val activity = launch(code("https://bridge.example.com"))
        clickPositive()
        assertEquals("Server request failed: HTTP 500", status(activity))
    }

    // MARK: - No server configured

    @Test
    fun withoutAServerScanningIsDisabled() {
        RecordingStore.clearAll()
        val activity = launch(code("https://bridge.example.com"))
        assertNull(ShadowDialog.getLatestDialog())
        assertNull("no server, no camera", startedScanner(activity))
        assertFalse(activity.findViewById<Button>(R.id.scanButton).isEnabled)
        assertEquals(
            "Set up your server first. Sign-in codes can only be approved for the server this app is connected to.",
            status(activity)
        )
        assertTrue(fake.calls.isEmpty())
    }
}
