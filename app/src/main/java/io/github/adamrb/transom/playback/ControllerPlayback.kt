package io.github.adamrb.transom.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.adamrb.transom.common.AppLog

/**
 * The production [Playback]: a media3 [MediaController] connected to [PlaybackService]. The
 * connection is asynchronous, so calls made before it completes are remembered ([pendingItem])
 * and applied on connect; everything else is a thin pass-through to the controller, which is a
 * [Player] itself. Releasing only disconnects this controller; the service keeps playing.
 */
class ControllerPlayback(context: Context) : Playback {

    private val appContext = context.applicationContext
    private val listeners = mutableListOf<Playback.Listener>()
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var pendingItem: Playback.Item? = null
    /** Play / seek asked for before the controller connected; applied right after [pendingItem]. */
    private var pendingPlay = false
    private var pendingSeekMs: Long? = null
    private var pendingSpeed: Float? = null
    private var released = false
    private var error: PlaybackException? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_PLAYER_ERROR, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY
                )
            ) {
                if (events.contains(Player.EVENT_PLAYER_ERROR)) error = player.playerError
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) error = null
                notifyChanged()
            }
        }
    }

    init {
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val f = MediaController.Builder(appContext, token).buildAsync()
            future = f
            f.addListener({ onConnected(f) }, ContextCompat.getMainExecutor(appContext))
        } catch (e: Exception) {
            // No session service on this build (or JVM): the page works without playback.
            AppLog.w(TAG, "media controller unavailable", e)
            future = null
        }
    }

    private fun onConnected(f: ListenableFuture<MediaController>) {
        if (released) return
        val c = try {
            f.get()
        } catch (e: Exception) {
            AppLog.w(TAG, "media controller connection failed", e)
            notifyChanged()
            return
        }
        controller = c
        c.addListener(playerListener)
        pendingItem?.let { applyItem(c, it) }
        pendingItem = null
        pendingSpeed?.let { c.setPlaybackSpeed(it) }
        pendingSpeed = null
        pendingSeekMs?.let { c.seekTo(it) }
        pendingSeekMs = null
        if (pendingPlay) c.play()
        pendingPlay = false
        notifyChanged()
    }

    override val isConnected: Boolean get() = controller != null

    override val currentMediaId: String? get() = controller?.currentMediaItem?.mediaId ?: pendingItem?.mediaId

    override val isPlaying: Boolean get() = controller?.isPlaying == true

    override val positionMs: Long get() = controller?.currentPosition ?: 0L

    override val durationMs: Long
        get() {
            val d = controller?.duration ?: return -1L
            return if (d == C.TIME_UNSET || d < 0) -1L else d
        }

    override val speed: Float get() = controller?.playbackParameters?.speed ?: 1f

    override val hasError: Boolean get() = error != null

    override fun setItem(item: Playback.Item) {
        val c = controller
        if (c == null) {
            pendingItem = item
            return
        }
        applyItem(c, item)
    }

    private fun applyItem(c: MediaController, item: Playback.Item) {
        val mediaItem = mediaItemFor(item)
        val current = c.currentMediaItem
        if (current?.mediaId == item.mediaId && c.playbackState != Player.STATE_IDLE) {
            // Same recording already loaded: only its words may have changed (a rename), and
            // those go into the notification without touching playback (same URI, so the
            // player updates the item in place rather than re-preparing it).
            if (current.mediaMetadata.title != mediaItem.mediaMetadata.title ||
                current.mediaMetadata.artist != mediaItem.mediaMetadata.artist
            ) {
                c.replaceMediaItem(c.currentMediaItemIndex, mediaItem)
            }
            return
        }
        error = null
        c.setMediaItem(mediaItem)
        c.prepare()
    }

    private fun mediaItemFor(item: Playback.Item): MediaItem {
        val extras = Bundle().apply {
            item.fileId?.let { putString(PlaybackService.EXTRA_FILE_ID, it) }
            item.serverId?.let { putString(PlaybackService.EXTRA_SERVER_RECORDING_ID, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.subtitle)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(item.mediaId).setUri(item.uri).setMediaMetadata(metadata).build()
    }

    override fun play() {
        val c = controller
        if (c == null) pendingPlay = true else c.play()
    }

    override fun pause() {
        val c = controller
        if (c == null) pendingPlay = false else c.pause()
    }

    override fun seekTo(positionMs: Long) {
        val c = controller
        if (c == null) {
            pendingSeekMs = positionMs.coerceAtLeast(0L)
            return
        }
        val d = durationMs
        c.seekTo(positionMs.coerceIn(0L, if (d > 0) d else Long.MAX_VALUE))
    }

    override fun setSpeed(speed: Float) {
        val c = controller
        if (c == null) pendingSpeed = speed else c.setPlaybackSpeed(speed)
    }

    override fun addListener(listener: Playback.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: Playback.Listener) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onPlaybackChanged() }
    }

    override fun release() {
        released = true
        listeners.clear()
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    companion object {
        private const val TAG = "Playback"

        /** The default [Playback.Factory]. */
        val FACTORY = Playback.Factory { context -> ControllerPlayback(context) }
    }
}
