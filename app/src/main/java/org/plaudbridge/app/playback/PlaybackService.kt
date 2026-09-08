package org.plaudbridge.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.plaudbridge.app.R
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.filedetail.FileDetailActivity

/**
 * The one player in the app, in a service so a recording keeps playing when the detail screen
 * is left (a media notification with play/pause and seek takes over). The detail screen talks
 * to it through a [androidx.media3.session.MediaController], see [ControllerPlayback].
 *
 * Audio comes from the phone (a file the sync stored) or streamed from the bridge server, which
 * wants the access token in an Authorization header. That header is added here, per request,
 * and only for URLs under the configured server, so it can never leak to another host and a
 * token changed in Settings is picked up by the next request.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
        val withAuth = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (isOwnServer(spec.uri)) spec.withAdditionalHeaders(mapOf("Authorization" to ApiClient.authHeader())) else spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(withAuth as DataSource.Factory))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nowPlaying = mediaItem?.let {
                    val extras = it.mediaMetadata.extras
                    NowPlaying(extras?.getString(EXTRA_FILE_ID), extras?.getString(EXTRA_SERVER_RECORDING_ID))
                }
            }

            /** At the end, rewind and rest: a plain Play (card or notification) then starts over. */
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_mic) }
        )
    }

    /** Tapping the notification opens the detail screen of whatever is playing, see [FileDetailActivity]. */
    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, FileDetailActivity::class.java).setAction(FileDetailActivity.ACTION_NOW_PLAYING)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app was swiped away: keep playing only if something is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        nowPlaying = null
        super.onDestroy()
    }

    /** Which recording the player holds, for a screen opened from the notification. */
    data class NowPlaying(val fileId: String?, val serverId: String?)

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_SERVER_RECORDING_ID = "server_recording_id"

        /** Set by the service as items change; null when nothing is loaded (or the process restarted). */
        @Volatile
        var nowPlaying: NowPlaying? = null
            private set

        /** True only for URLs under the configured server's root: the token goes nowhere else. */
        internal fun isOwnServer(uri: Uri): Boolean {
            val base = RecordingStore.serverBaseUrl?.trimEnd('/') ?: return false
            val baseUri = Uri.parse(base)
            return uri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
                uri.host.equals(baseUri.host, ignoreCase = true) &&
                uri.port == baseUri.port &&
                (uri.path ?: "").startsWith((baseUri.path ?: "").trimEnd('/') + "/")
        }
    }
}
