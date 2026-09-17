package cloud.adamrb.transom.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.adamrb.transom.R
import cloud.adamrb.transom.models.SyncProgress
import cloud.adamrb.transom.models.SyncState

/**
 * Banner and snackbar decisions for every sync state. The point that matters most: Failed
 * hides the banner (it used to on both tabs, silently) AND is worded for the user with Retry.
 */
class SyncFeedbackTest {

    private val progress = SyncProgress(totalFiles = 0, syncedFiles = 0)

    @Test
    fun bannerFollowsTheState() {
        assertEquals(SyncFeedback.Banner.SHOW, SyncFeedback.banner(SyncState.Syncing(progress)))
        assertEquals(SyncFeedback.Banner.SHOW, SyncFeedback.banner(SyncState.WiFiTransferring(progress)))
        assertEquals(SyncFeedback.Banner.KEEP, SyncFeedback.banner(SyncState.WiFiConnecting(SyncState.WiFiConnectPhase.HANDSHAKING)))
        assertEquals(SyncFeedback.Banner.HIDE_SOON, SyncFeedback.banner(SyncState.Completed))
        assertEquals(SyncFeedback.Banner.HIDE, SyncFeedback.banner(SyncState.Idle))
        assertEquals(SyncFeedback.Banner.HIDE, SyncFeedback.banner(SyncState.Failed("Failed to fetch file list")))
        assertEquals(SyncFeedback.Banner.HIDE, SyncFeedback.banner(SyncState.Failed("x", SyncState.Reason.TIMED_OUT)))
    }

    @Test
    fun failuresAreWordedByReasonNeverByTheSdkMessage() {
        assertEquals(R.string.sync_connect_first, SyncFeedback.messageRes(SyncState.Failed("No recorder connected", SyncState.Reason.NOT_CONNECTED)))
        assertEquals(R.string.sync_failed_timeout, SyncFeedback.messageRes(SyncState.Failed("Recorder stopped responding", SyncState.Reason.TIMED_OUT)))
        assertEquals(R.string.sync_failed_wifi, SyncFeedback.messageRes(SyncState.Failed("openWiFi status 4", SyncState.Reason.WIFI)))
        assertEquals(R.string.sync_failed, SyncFeedback.messageRes(SyncState.Failed("Failed to fetch file list")))
    }

    @Test
    fun retryIsOfferedUnlessThereIsNoRecorderToRetryAgainst() {
        assertFalse(SyncFeedback.offersRetry(SyncState.Failed("x", SyncState.Reason.NOT_CONNECTED)))
        assertTrue(SyncFeedback.offersRetry(SyncState.Failed("x", SyncState.Reason.TIMED_OUT)))
        assertTrue(SyncFeedback.offersRetry(SyncState.Failed("x", SyncState.Reason.WIFI)))
        assertTrue(SyncFeedback.offersRetry(SyncState.Failed("x")))
    }

    @Test
    fun onlyRecentFailuresAreAnnounced() {
        val now = 1_000_000L
        assertTrue(SyncFeedback.isFresh(SyncState.Failed("x", at = now), now))
        assertTrue(SyncFeedback.isFresh(SyncState.Failed("x", at = now - SyncFeedback.FRESH_MS), now))
        assertFalse(SyncFeedback.isFresh(SyncState.Failed("x", at = now - SyncFeedback.FRESH_MS - 1), now))
        // Two identical failures in a row are distinct values, so a StateFlow re-emits the second.
        assertFalse(SyncState.Failed("x", at = 1) == SyncState.Failed("x", at = 2))
    }
}
