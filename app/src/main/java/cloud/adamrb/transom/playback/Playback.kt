package cloud.adamrb.transom.playback

import android.net.Uri

/**
 * What the detail screen needs from the player, and nothing about how it is implemented. The
 * production [ControllerPlayback] is a media3 MediaController bound to [PlaybackService], so
 * playback outlives the screen (a notification with play/pause takes over); Robolectric tests
 * swap in a fake through [FileDetailActivity.playbackFactory] and drive the same calls.
 *
 * Positions and durations are milliseconds; [durationMs] is -1 until the player knows it.
 */
interface Playback {

    /** The screen's view of a recording to play. [mediaId] identifies it across binds (a path or URL). */
    data class Item(
        val mediaId: String,
        val uri: Uri,
        val title: String,
        val subtitle: String?,
        /** Ids for the notification's tap-through, see [PlaybackService.nowPlaying]. */
        val fileId: String?,
        val serverId: String?
    )

    interface Listener {
        /** Anything observable changed: connection, item, play state, duration, speed, an error. */
        fun onPlaybackChanged()
    }

    /** The controller is connected and the calls below reach a real player. */
    val isConnected: Boolean

    /** The item the player currently holds, whoever set it (this screen or an earlier one). */
    val currentMediaId: String?

    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long
    val speed: Float

    /** The player refused the current item (bad file, network); the UI hides itself then. */
    val hasError: Boolean

    /**
     * Make [item] the player's item unless it already is: a second bind of the same recording
     * (rotation, a re-render, coming back to the screen) must not restart playback.
     */
    fun setItem(item: Item)

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Let go of the player. Playback itself goes on in the service. */
    fun release()

    /** Builds a [Playback] for one screen; the production one connects to [PlaybackService]. */
    fun interface Factory {
        fun create(context: android.content.Context): Playback
    }
}
