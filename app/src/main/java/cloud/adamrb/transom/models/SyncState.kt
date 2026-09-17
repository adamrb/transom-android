package cloud.adamrb.transom.models

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: SyncProgress) : SyncState()
    /** WiFi fast-transfer connect phase (mirrors iOS WiFiConnectPhase). */
    enum class WiFiConnectPhase { OPENING_HOTSPOT, CONNECTING_WIFI, HANDSHAKING }
    data class WiFiConnecting(val phase: WiFiConnectPhase) : SyncState()
    data class WiFiTransferring(val progress: SyncProgress) : SyncState()
    object Completed : SyncState()

    /**
     * A sync or transfer that did not finish. [message] is the SDK's or our own diagnostic text
     * and is for logs and the WiFi sheet's filter; screens pick their wording from [reason]
     * (see SyncFeedback) so no SDK string reaches the user. [at] stamps the failure so a screen
     * that (re)subscribes later, and gets this value replayed, can tell a fresh failure from a
     * stale one; it also keeps two identical failures in a row from being conflated away.
     */
    data class Failed(
        val message: String,
        val reason: Reason = Reason.OTHER,
        val at: Long = System.currentTimeMillis()
    ) : SyncState()

    /** Why a sync failed, in categories the UI can word for the user. */
    enum class Reason {
        /** Sync now was asked for with no recorder connected. */
        NOT_CONNECTED,
        /** The recorder stopped answering: no file list or no download progress for too long. */
        TIMED_OUT,
        /** The WiFi fast transfer could not be set up or broke off. */
        WIFI,
        OTHER
    }

    val isActive: Boolean
        get() = this is Syncing || this is WiFiConnecting || this is WiFiTransferring

    /** Current sync progress (non-null only when Syncing / WiFiTransferring) */
    val currentProgress: SyncProgress?
        get() = when (this) {
            is Syncing -> progress
            is WiFiTransferring -> progress
            else -> null
        }
}
