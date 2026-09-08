package org.plaudbridge.app.ui.common

import org.plaudbridge.app.R
import org.plaudbridge.app.models.SyncState

/**
 * What Home and the Recordings tab do with a [SyncState]: whether the transfer banner shows,
 * and how a failure is worded and offered for retry. One place so the two tabs cannot drift
 * (they used to each map Failed to a silent fade-out, which is how a sync that never answered
 * left a banner up for good on both). Pure Kotlin apart from string resource ids.
 */
object SyncFeedback {

    /** What the transfer banner should do on a state change. */
    enum class Banner {
        /** Show it with the state's progress. */
        SHOW,
        /** Leave it as it is (the WiFi connect window belongs to the Fast Transfer sheet). */
        KEEP,
        /** Let the 100% reading sit for a moment, then hide. */
        HIDE_SOON,
        /** Hide now. */
        HIDE
    }

    fun banner(state: SyncState): Banner = when (state) {
        is SyncState.Syncing, is SyncState.WiFiTransferring -> Banner.SHOW
        is SyncState.WiFiConnecting -> Banner.KEEP
        SyncState.Completed -> Banner.HIDE_SOON
        SyncState.Idle, is SyncState.Failed -> Banner.HIDE
    }

    /** How long after it happened a failure still deserves a snackbar when a screen (re)subscribes. */
    const val FRESH_MS = 15_000L

    /**
     * A StateFlow replays its current value to every new collector, so a Failed from minutes
     * ago would otherwise pop a snackbar on every tab switch or rotation. Fresh means recent.
     */
    fun isFresh(failed: SyncState.Failed, now: Long = System.currentTimeMillis()): Boolean =
        now - failed.at <= FRESH_MS

    /** The sentence for the user; never the SDK's [SyncState.Failed.message]. */
    fun messageRes(failed: SyncState.Failed): Int = when (failed.reason) {
        SyncState.Reason.NOT_CONNECTED -> R.string.sync_connect_first
        SyncState.Reason.TIMED_OUT -> R.string.sync_failed_timeout
        SyncState.Reason.WIFI -> R.string.sync_failed_wifi
        SyncState.Reason.OTHER -> R.string.sync_failed
    }

    /** Retry makes sense unless there is nothing to retry against. */
    fun offersRetry(failed: SyncState.Failed): Boolean = failed.reason != SyncState.Reason.NOT_CONNECTED
}
