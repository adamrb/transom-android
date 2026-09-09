package org.plaudbridge.app.ui.filedetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ActivityFileDetailBinding
import org.plaudbridge.app.databinding.ViewFileDetailHeaderBinding
import org.plaudbridge.app.export.ExportFileName
import org.plaudbridge.app.export.TranscriptHighlight
import org.plaudbridge.app.export.TranscriptMarkdown
import org.plaudbridge.app.export.TranscriptParagraph
import org.plaudbridge.app.export.TranscriptShare
import org.plaudbridge.app.managers.TitleSyncManager
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.RoutingRun
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.playback.ControllerPlayback
import org.plaudbridge.app.playback.Playback
import org.plaudbridge.app.playback.PlaybackService
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.ContentWidth
import org.plaudbridge.app.ui.common.MarkdownRenderer
import org.plaudbridge.app.ui.common.themeColor
import org.plaudbridge.app.ui.recordings.ApiServerRecordingActions
import org.plaudbridge.app.ui.recordings.RecordingActions
import org.plaudbridge.app.ui.recordings.RecordingItem
import org.plaudbridge.app.ui.recordings.RecordingsAdapter
import org.plaudbridge.app.ui.recordings.ServerRecordingActions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Recording detail page
 * Collapsing header (name/date/duration/status) + Summary + Highlights + Automations + Transcript
 * (one list item per paragraph) + a player that keeps playing after the screen is left.
 *
 * One screen for one recording, wherever it lives. The intent carries whichever ids are known:
 *  - `file_id`: the phone's [RecordingFile] (offline audio, cached transcript, upload state);
 *  - `server_recording_id`: the bridge server's copy (fresh title, status, transcript).
 * A phone copy renders instantly from its cache; a server id then refreshes title, status and
 * transcript from the server. Both sides are combined through [RecordingItem], the same merge
 * the Recordings tab draws its rows from, so the header cannot disagree with the list, and
 * rendered through [DetailModel], the handful of fields the content blocks actually use.
 *
 * The page is a RecyclerView: position 0 is the header block ([header], summary, highlights,
 * automations, the Transcript section header and the empty state), the rest one paragraph each,
 * so a two-hour transcript renders as it scrolls. Playback runs in [PlaybackService] and is
 * driven here through a [Playback]; the paragraph being played is tinted and kept in view until
 * the reader scrolls away, when a "Return to playback" chip offers the way back.
 *
 * The only thing written back into RecordingStore is a transcript fetched for a recording the
 * phone already indexes, which is exactly what TitleSyncManager stores in the background.
 */
class FileDetailActivity : AppCompatActivity(), JumpToSheet.Host, MoreActionsSheet.Host {

    private lateinit var binding: ActivityFileDetailBinding

    /** The list's first item: summary, highlights, automations, section header, empty state. */
    private lateinit var header: ViewFileDetailHeaderBinding
    private lateinit var adapter: TranscriptAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var positionStore: TranscriptPositionStore
    private val syncManager get() = (application as PlaudBridgeApp).syncManager

    /** The phone's copy, when the phone has one. */
    private var currentFile: RecordingFile? = null

    /** The server's id when known (intent, or remembered by the phone copy from its upload). */
    private var serverRecordingId: String? = null

    /** The server's copy once fetched. */
    private var serverRecording: ServerRecording? = null

    /** Transcript document fetched from the server in this view; wins over the phone's cache. */
    private var serverTranscriptJSON: String? = null

    /**
     * This screen saw the transcription still running (status pending/transcribing, a 409, or a
     * re-transcribe it requested) and has not seen it finish since. Two uses: only then does a
     * Ready transcript count as having ARRIVED, the moment the server's router starts on it (a
     * recording opened already transcribed was routed long ago and must not wait on a poll before
     * it may say "No automations ran"); and while set, the transcript on screen is a cached one
     * the server no longer holds as routable, so it does not count for [serverTranscriptReady].
     */
    private var transcriptWasPending = false

    /**
     * The server has a finished transcript for this recording as far as this screen knows: text
     * is on screen and nothing since said the transcription is (again) in progress. Gates both the
     * "No automations ran" verdict and the Run automations action (the server answers 409 without
     * a transcript, even while an old one is still shown here during a re-transcribe).
     */
    private val serverTranscriptReady: Boolean
        get() = transcriptPlainText != null && !transcriptWasPending

    /** What the content blocks currently show, whichever source it came from. */
    private var currentModel: DetailModel? = null

    /**
     * The server's router runs for this recording, newest first; null until the first successful
     * fetch (the section stays hidden rather than flashing an empty state while loading, and a
     * failed fetch is silent because the transcript is the page's job, the automations a bonus).
     */
    private var routingRuns: List<RoutingRun>? = null

    /**
     * The fields the content blocks render. [transcriptJSON] is the server transcript document
     * (text, segments, summary, highlights); the phone caches the same document.
     */
    data class DetailModel(
        val title: String,
        val recordedAtMillis: Long,
        val durationSeconds: Long,
        val summary: String?,
        val transcriptJSON: String?
    )

    /**
     * The server calls the screen makes. An interface (with the real client as the default) so
     * a Robolectric test can drive the screen without a network stack; the default runs the
     * blocking ApiClient calls on Dispatchers.IO. Extends the list's action seam so Rename,
     * Re-transcribe and Delete go through the exact same code as the long-press sheet.
     */
    interface ServerDetailSource : ServerRecordingActions {
        suspend fun recording(id: String): ApiClient.RecordingResult
        suspend fun transcript(id: String): ApiClient.TranscriptResult
        suspend fun routing(id: String): ApiClient.RoutingResult
        /** [instructions] null for a plain run; otherwise the user's text for the automations. */
        suspend fun rerunRouting(id: String, idempotencyKey: String, instructions: String?): ApiClient.ActionResult
        suspend fun retryDelivery(deliveryId: String): ApiClient.RetryResult
        /** PATCH speakers; answers with the transcript document (contract §2). */
        suspend fun renameSpeakers(id: String, renames: Map<String, String>): ApiClient.TranscriptResult
    }

    private object ApiServerDetailSource : ServerDetailSource, ServerRecordingActions by ApiServerRecordingActions {
        override suspend fun recording(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRecording(id) }
        override suspend fun transcript(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchTranscript(id) }
        override suspend fun routing(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRouting(id) }
        override suspend fun rerunRouting(id: String, idempotencyKey: String, instructions: String?) =
            withContext(Dispatchers.IO) { ApiClient.rerunRouting(id, idempotencyKey, instructions) }
        override suspend fun retryDelivery(deliveryId: String) = withContext(Dispatchers.IO) { ApiClient.retryDelivery(deliveryId) }
        override suspend fun renameSpeakers(id: String, renames: Map<String, String>) =
            withContext(Dispatchers.IO) { ApiClient.renameSpeakers(id, renames) }
    }

    companion object {
        /** Intent extra: the phone's RecordingFile id. */
        const val EXTRA_FILE_ID = "file_id"

        /** Intent extra: the bridge server's recording id. */
        const val EXTRA_SERVER_RECORDING_ID = "server_recording_id"

        /**
         * Intent action from the playback notification: open whatever is playing. The ids come
         * from [PlaybackService.nowPlaying] rather than the intent, which is built once.
         */
        const val ACTION_NOW_PLAYING = "org.plaudbridge.app.action.NOW_PLAYING"

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var serverSource: ServerDetailSource = ApiServerDetailSource

        /** Swapped by tests; production connects to [PlaybackService]. */
        @VisibleForTesting
        var playbackFactory: Playback.Factory = ControllerPlayback.FACTORY

        /**
         * Delays before re-reading the routing endpoint while a delivery is still in progress:
         * agents report back seconds to tens of seconds after the hand-off, so three reads spread
         * over about forty seconds catch most outcomes without keeping the radio busy.
         */
        @VisibleForTesting
        val ROUTING_POLL_DELAYS_MS = longArrayOf(8_000L, 15_000L, 17_000L)

        /** After "Run automations": the router answers within seconds, the agents a while later. */
        @VisibleForTesting
        val RERUN_REFRESH_DELAYS_MS = longArrayOf(3_000L, 15_000L)

        /** Transcript documents at least this long are parsed off the main thread. */
        const val PARSE_OFF_MAIN_CHARS = 20_000

        /** Where that parse runs; tests swap in an inline dispatcher for determinism. */
        @VisibleForTesting
        var transcriptParseDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

        /** How often the recording is re-read while the server is still transcribing it. */
        @VisibleForTesting
        const val TRANSCRIPTION_POLL_INTERVAL_MS = 5_000L

        /** A route's reason is a paragraph; show its opening lines and unfold on tap. */
        private const val REASON_COLLAPSED_LINES = 3

        /** Lines of summary shown before "Read more" (about a paragraph on a phone). */
        private const val SUMMARY_COLLAPSED_LINES = 8

        /** Opens a transcript paragraph that holds a bookmark; the same star the Highlights rows use. */
        @VisibleForTesting
        const val BOOKMARK_STAR = "★ "

        /** How long the paragraph a highlight jumps to stays tinted. */
        @VisibleForTesting
        const val PARAGRAPH_FLASH_MS = 1_200L

        /** The player's skip buttons: a short hop back to re-hear a phrase, a longer one ahead. */
        const val SKIP_BACK_MS = 15_000L
        const val SKIP_FORWARD_MS = 30_000L

        /** The speed chips, in chip order. */
        val SPEEDS = floatArrayOf(1f, 1.5f, 2f)

        /** How often the clock, slider and now-playing paragraph follow the player while it plays. */
        private const val PROGRESS_TICK_MS = 250L

        private const val PLAYER_PREFS = "player"
        private const val PREF_SPEED = "speed"

        /** Recording ids with a Run automations call on the wire, see [runAutomations]. */
        private val rerunsInFlight = mutableSetOf<String>()

        /** One Run automations intent: its idempotency key and the instructions it carries (null for none). */
        private data class RerunIntent(val key: String, val instructions: String?)

        /** The user's pending Run automations intent, per server id, see [runAutomations]. */
        private val pendingReruns = mutableMapOf<String, RerunIntent>()

        /**
         * The screen currently showing each server recording, so a rerun that finishes after a
         * rotation reports to the live replacement instead of a destroyed instance.
         */
        private val liveScreens = mutableMapOf<String, FileDetailActivity>()

        /**
         * A detail screen for this server recording is in front of the user right now (resumed,
         * not merely alive behind another activity or the home screen): notifications stay quiet.
         */
        fun isShowing(serverId: String): Boolean = liveScreens[serverId]?.inForeground == true

        /** An answer that leaves open whether the server ran the router anyway (timeout, 5xx, lost response). */
        private fun isAmbiguousRerunFailure(result: ApiClient.ActionResult): Boolean =
            result is ApiClient.ActionResult.Error && result.code != 409

        /** Tests share one process: clear the process-wide rerun state between them. */
        @VisibleForTesting
        internal fun resetProcessStateForTests() {
            transcriptParseDispatcher = Dispatchers.Default
            playbackFactory = ControllerPlayback.FACTORY
            rerunsInFlight.clear()
            pendingReruns.clear()
            liveScreens.clear()
        }

        /** Outlives any one screen so a rerun's in-flight state survives a rotation. */
        private val rerunScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * How long after upload a recording counts as "still settling", see [isRecentUpload].
         * Generous because the contract carries no transcription-completion time and a long
         * recording or a backlog can keep the transcriber busy for a while; the cost of being
         * wrong is only a hidden section (not a wrong verdict) until the screen is next refreshed.
         */
        private const val RECENT_UPLOAD_WINDOW_MS = 30 * 60_000L

        /** Open a merged row: both ids travel when both are known. */
        fun intentFor(context: Context, item: RecordingItem): Intent =
            Intent(context, FileDetailActivity::class.java).apply {
                item.localId?.let { putExtra(EXTRA_FILE_ID, it) }
                item.serverId?.let { putExtra(EXTRA_SERVER_RECORDING_ID, it) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The layout manager first: inflating a child against the list asks it for layout params.
        // Focus must not scroll the page: the paragraphs are selectable (so focusable) text, and
        // a focus change (a dialog closing, a keyboard) would otherwise yank the list to whichever
        // paragraph the framework hands focus to.
        layoutManager = object : LinearLayoutManager(this) {
            override fun onRequestChildFocus(parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?): Boolean = true
        }
        binding.transcriptList.layoutManager = layoutManager
        header = ViewFileDetailHeaderBinding.inflate(layoutInflater, binding.transcriptList, false)
        positionStore = TranscriptPositionStore(this)

        setupList()
        setupToolbar()
        setupBottomBar()
        setupAudioPlayerControls()
        // Wide screens: header, page and player share one centred column. The list's rows (and
        // the header item) pad themselves by the screen margin, so the list is capped short of it.
        ContentWidth.limit(binding.headerBlock)
        ContentWidth.limit(binding.transcriptList, contentInset = resources.getDimensionPixelSize(R.dimen.pb_screen_padding))
        ContentWidth.limit(binding.audioPlayer)
        header.copyTranscriptButton.setOnClickListener { copyTranscript() }
        header.exportMarkdownButton.setOnClickListener { currentModel?.let { m -> exportMarkdown(m) } }
        header.copySummaryButton.setOnClickListener { currentModel?.let { m -> copySummary(m) } }
        header.automationsShowEarlier.setOnClickListener {
            showEarlierRuns = true
            bindAutomations()
        }
        header.emptyDetailsToggle.setOnClickListener {
            header.emptyDetails.visibility = if (header.emptyDetails.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        var fileId = intent.getStringExtra(EXTRA_FILE_ID)
        var serverId = intent.getStringExtra(EXTRA_SERVER_RECORDING_ID)
        if (fileId == null && serverId == null && intent.action == ACTION_NOW_PLAYING) {
            // Opened from the playback notification: show whatever the player holds.
            PlaybackService.nowPlaying?.let {
                fileId = it.fileId
                serverId = it.serverId
            }
        }
        fileId?.let { currentFile = findFile(it) }
        serverRecordingId = serverId ?: currentFile?.serverId?.takeIf { it.isNotBlank() }
        if (currentFile == null && serverRecordingId == null) {
            finish()
            return
        }
        playback = playbackFactory.create(this).also { it.addListener(playbackListener) }

        val file = currentFile
        if (file != null) {
            backfillDuration(file)
            render() // instant: cached title, transcript and local audio
        } else {
            binding.fileNameLabel.text = ""
            binding.fileDateLabel.text = getString(R.string.checking_transcript)
        }

        val sid = serverRecordingId
        when {
            // Also loads the Automations section once the recording (and so its age) is known.
            sid != null && RecordingStore.isServerConfigured -> loadServerRecording(sid)
            // Uploaded before the phone learned the server id (legacy index): resolve it by
            // (device, session) and fetch the transcript the old way. Also when a transcript is
            // already cached: the id is what unlocks the server-side content (automations, the
            // server menu actions), and once stored the next open takes the server path above.
            file != null && file.uploaded -> fetchTranscriptFromServer(file, userInitiated = false)
        }
    }

    // MARK: - Page structure

    private fun setupList() {
        adapter = TranscriptAdapter(header.root, onSeek = { seekPlayerTo(it) }, onSpeakerTap = { showRenameSpeakerDialog(it) })
        binding.transcriptList.adapter = adapter
        binding.transcriptList.itemAnimator = null // tint changes must not fade the whole row
        binding.transcriptList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) onUserScrollGesture()
            }
        })
        binding.fastScroller.attach(binding.transcriptList, object : TranscriptFastScroller.Host {
            override val paragraphCount: Int get() = transcriptParagraphs.size
            override fun timeLabelAt(paragraphIndex: Int): String? = adapter.rows.getOrNull(paragraphIndex)?.timeLabel
            override fun scrollToParagraph(paragraphIndex: Int) {
                binding.appBar.setExpanded(false, false)
                layoutManager.scrollToPositionWithOffset(adapter.positionOf(paragraphIndex), 0)
            }
            override fun onUserScrollGesture() = this@FileDetailActivity.onUserScrollGesture()
        })
    }

    private fun setupToolbar() {
        binding.backButton.setOnClickListener { finish() }
        binding.moreButton.setOnClickListener { showMoreSheet() }
        binding.jumpToButton.setOnClickListener { showJumpToSheet() }
        binding.toolbarTitle.setOnClickListener { scrollToTop() }
        // The big title fades out as the header collapses; the toolbar title fades in over the
        // last stretch, so the two are never both readable at once.
        binding.appBar.addOnOffsetChangedListener { appBar, offset ->
            val range = appBar.totalScrollRange
            val collapsed = if (range == 0) 0f else -offset / range.toFloat()
            binding.toolbarTitle.alpha = ((collapsed - 0.6f) / 0.4f).coerceIn(0f, 1f)
            binding.headerBlock.alpha = 1f - (collapsed / 0.7f).coerceIn(0f, 1f)
        }
    }

    /** The bottom bar (chip + player) changes height as things appear; the list keeps clear of it. */
    private fun setupBottomBar() {
        binding.bottomBar.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height != oldBottom - oldTop) {
                val extra = (16 * resources.displayMetrics.density).toInt()
                // Only the bottom changes; the sides belong to ContentWidth on wide screens
                binding.transcriptList.setPadding(binding.transcriptList.paddingLeft, 0, binding.transcriptList.paddingRight, height + extra)
                binding.fastScroller.setPadding(0, 0, 0, height)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            binding.audioPlayer.setPadding(
                binding.audioPlayer.paddingLeft, binding.audioPlayer.paddingTop,
                binding.audioPlayer.paddingRight, (12 * resources.displayMetrics.density).toInt() + bottom
            )
            insets
        }
    }

    /** Toolbar title tap: back to the header, wherever the reader is. */
    private fun scrollToTop() {
        binding.appBar.setExpanded(true, animationsEnabled())
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    private fun showJumpToSheet() {
        if (transcriptParagraphs.isEmpty()) return
        if (supportFragmentManager.findFragmentByTag(JumpToSheet.TAG) != null) return
        JumpToSheet().show(supportFragmentManager, JumpToSheet.TAG)
    }

    override fun jumpToItems(): List<JumpToItem> =
        JumpToItems.build(transcriptParagraphs, currentHighlights, currentModel?.durationSeconds ?: 0L)

    override fun onJumpTo(item: JumpToItem) {
        seekPlayerTo(item.seconds)
        if (item.paragraphIndex >= 0) revealParagraph(item.paragraphIndex)
    }

    /** onCreate already loaded everything; only a RETURN to the screen needs a refresh. */
    private var resumedBefore = false

    /** Between onResume and onPause: the only time the transcription poll may run. */
    private var inForeground = false

    override fun onResume() {
        super.onResume()
        inForeground = true
        // Pick up what changed while we were away (a rename, a transcript stored by the
        // background title sync); the server side re-renders in place after its own writes.
        val file = currentFile
        if (file != null) {
            currentFile = findFile(file.id) ?: file
            if (currentModel != null) render()
        }
        // Agents finish their work while the user is elsewhere; coming back should show it.
        if (resumedBefore && serverRecordingId != null && RecordingStore.isServerConfigured) {
            refreshRouting()
            // Likewise a transcription that was running when the user left: ask at once.
            if (transcriptionInProgress) scheduleTranscriptionPoll(0L)
        }
        resumedBefore = true
        registerAsLiveScreen()
        syncTranscriptionPolling()
        syncPlayerUi()
    }

    /** This instance is the one showing [serverRecordingId] now, see [liveScreens]. */
    private fun registerAsLiveScreen() {
        serverRecordingId?.let { liveScreens[it] = this }
    }

    /**
     * Read from the persistent store FIRST: updateTranscript/updateDuration write to disk, while
     * syncManager.files is an in-memory snapshot that may still hold stale objects.
     */
    private fun findFile(fileId: String): RecordingFile? =
        RecordingStore.allFiles.find { it.id == fileId } ?: syncManager.files.value.find { it.id == fileId }

    /**
     * Self-heal: entries synced before the duration fix have duration 0 stored; recompute from
     * the local audio and backfill so old files show the real length too.
     */
    private fun backfillDuration(file: RecordingFile) {
        val localPath = file.localPath
        if (file.duration > 0 || localPath == null || !File(localPath).exists()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val d = org.plaudbridge.app.managers.SyncManager.shared.audioDurationSec(localPath)
            if (d > 0) {
                RecordingStore.updateDuration(file.id, d)
                runOnUiThread {
                    currentFile = findFile(file.id) ?: file
                    currentModel?.let { bindMetaLine(it.recordedAtMillis, d) }
                    syncPlayerUi()
                }
            }
        }
    }

    /** On-screen transcript as plain characters (speaker paragraphs); null when nothing parseable. */
    private var transcriptPlainText: String? = null

    /** What Copy transcript puts on the clipboard: speaker paragraphs, no timestamps. */
    private var transcriptCopyText: String? = null

    /** The paragraphs on screen, one list row each. */
    private var transcriptParagraphs: List<TranscriptParagraph> = emptyList()

    /** The document's speaker labels, for the rename dialog. */
    private var transcriptSpeakers: List<String> = emptyList()

    /** The document's button-press highlights, for the rows and the Jump to sheet. */
    private var currentHighlights: List<TranscriptHighlight> = emptyList()

    private val flashHandler = Handler(Looper.getMainLooper())

    /** The merged view of whatever this screen knows; null before anything has loaded. */
    private fun currentItem(): RecordingItem? {
        val file = currentFile
        val rec = serverRecording
        return if (file == null && rec == null) null else RecordingItem(file, rec)
    }

    /**
     * Render everything from the phone copy and the server copy together. The server transcript
     * fetched in this view wins over the phone's cache; the transcript's own summary wins over
     * the list object's (it is the freshest), then the server summary, then the phone's.
     */
    private fun render() {
        val item = currentItem() ?: return
        val file = item.local
        val rec = item.server
        // The server's fresh word that there is no speech overrides any transcript text held here
        // (the phone's cache, or a document fetched before a re-transcribe): nothing to show.
        val transcriptJSON = if (rec?.noSpeech == true) null else serverTranscriptJSON ?: file?.transcriptJSON
        val transcriptSummary = transcriptJSON?.let { transcriptExportFields(it).second }
        bindContent(
            DetailModel(
                title = RecordingsAdapter.rowTitle(this, item),
                recordedAtMillis = item.recordedAt,
                durationSeconds = item.durationSeconds,
                summary = transcriptSummary ?: rec?.summary ?: file?.summaryText,
                transcriptJSON = transcriptJSON
            )
        )
        bindStatusBadge(item)
        bindTranscriptionProgress(item)
        if (transcriptPlainText == null && !transcriptParsePending) bindEmptyState(item)
        bindAudio(file, rec)
        bindAutomations()
        syncTranscriptionPolling()
        updateToolbarActions()
    }

    /** The ⋮ and Jump to buttons only when there is something for them to act on. */
    private fun updateToolbarActions() {
        binding.moreButton.visibility = if (currentModel != null && currentItem() != null) View.VISIBLE else View.GONE
        binding.jumpToButton.visibility =
            if (transcriptParagraphs.isNotEmpty() && jumpToItems().isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * The server heard no speech in this recording. Its recording object is authoritative once
     * fetched (after a re-transcribe it may contradict what the phone cached, in either
     * direction); only before that does the transcript document held here (the phone's cache)
     * stand in. Such a recording has no text to show, copy, export or route; the audio is still
     * there to listen to.
     */
    private val isNoSpeech: Boolean
        get() = serverRecording?.noSpeech
            ?: ServerRecording.transcriptSaysNoSpeech(serverTranscriptJSON ?: currentFile?.transcriptJSON)

    /**
     * Header, summary, highlights and transcript blocks from a [DetailModel]. Leaves the empty
     * state hidden; [bindEmptyState] decides what it says because the reason a transcript is
     * missing differs per source.
     */
    private fun bindContent(model: DetailModel) {
        currentModel = model
        binding.fileNameLabel.text = model.title
        binding.toolbarTitle.text = model.title
        bindMetaLine(model.recordedAtMillis, model.durationSeconds)

        // Highlights: the server's transcript-around-each-button-press rows, above the transcript
        currentHighlights = model.transcriptJSON?.let { TranscriptHighlight.parse(it) } ?: emptyList()

        // Summary block (only when a summary exists). The model's own "Summary" heading goes (the
        // block is labelled), so does filler for empty sections, and so does its Highlights
        // section when the Highlights rows below already list the same button presses.
        val summaryMarkdown = model.summary?.takeIf { it.isNotBlank() }?.let { raw ->
            var md = MarkdownRenderer.withoutEmptySectionFiller(MarkdownRenderer.withoutSummaryHeading(raw))
            if (currentHighlights.isNotEmpty()) md = MarkdownRenderer.withoutHighlightsSection(md)
            md.takeIf { it.isNotBlank() }
        }
        val hasSummary = summaryMarkdown != null
        header.summaryCard.visibility = if (hasSummary) View.VISIBLE else View.GONE
        header.summaryHeaderRow.visibility = if (hasSummary) View.VISIBLE else View.GONE
        header.summaryText.visibility = if (hasSummary) View.VISIBLE else View.GONE
        if (summaryMarkdown != null) {
            MarkdownRenderer.setMarkdown(header.summaryText, summaryMarkdown)
            foldLongSummary()
        } else {
            header.summaryMore.visibility = View.GONE
        }

        bindHighlights(currentHighlights)

        // Transcript: the document's reader paragraphs (or the same grouping derived from its
        // segments), else its flat text; nothing parseable leaves the (caller-defined) empty state
        bindTranscript(model.transcriptJSON)
    }

    // MARK: - Transcript

    /** A transcript document read into what the list shows. Built off the main thread when long. */
    private data class ParsedTranscript(
        val json: String,
        val paragraphs: List<TranscriptParagraph>,
        val copyText: String?,
        val speakers: List<String>
    )

    /** The document currently on screen, so a re-render with the same one does nothing. */
    private var parsedTranscript: ParsedTranscript? = null
    private var transcriptParseJob: kotlinx.coroutines.Job? = null
    private var pendingParseJson: String? = null

    /** A long document is being read on a background thread; the loading bar shows meanwhile. */
    private val transcriptParsePending: Boolean get() = transcriptParseJob?.isActive == true

    /**
     * Put the document's paragraphs in the list. A short document is parsed inline. A long one
     * (a two-hour recording is a megabyte of JSON) is parsed on a background thread with the
     * loading bar showing, so the header, summary and highlights never wait on it; the same
     * document offered again (a metadata refresh, a poll, a resume) is not parsed twice.
     */
    private fun bindTranscript(json: String?) {
        if (json == null) {
            transcriptParseJob?.cancel()
            pendingParseJson = null
            showTranscriptLoading(false)
            applyTranscript(null)
            return
        }
        if (parsedTranscript?.json == json) {
            applyTranscriptState()
            return
        }
        if (json == pendingParseJson && transcriptParsePending) return
        transcriptParseJob?.cancel()
        if (json.length < PARSE_OFF_MAIN_CHARS) {
            pendingParseJson = null
            applyTranscript(parseTranscript(json))
            return
        }
        pendingParseJson = json
        showTranscriptLoading(true)
        transcriptParseJob = lifecycleScope.launch {
            val parsed = withContext(transcriptParseDispatcher) { parseTranscript(json) }
            pendingParseJson = null
            applyTranscript(parsed)
            // What the rest of the page shows depends on whether there is a transcript.
            currentItem()?.let { if (transcriptPlainText == null) bindEmptyState(it) }
            bindAutomations()
            updateToolbarActions()
        }
    }

    /** Pure: the document's paragraphs (server layout, derived grouping, or its flat text as paragraphs). */
    private fun parseTranscript(json: String): ParsedTranscript {
        val parsed = TranscriptParagraph.parse(json) ?: emptyList()
        val paragraphs = if (parsed.isNotEmpty()) parsed else flatTranscriptText(json)
            ?.split("\n\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { TranscriptParagraph(null, it, null, null) } ?: emptyList()
        return ParsedTranscript(
            json = json,
            paragraphs = paragraphs,
            copyText = copyTextFor(json) ?: TranscriptParagraph.plain(paragraphs).takeIf { paragraphs.isNotEmpty() },
            speakers = TranscriptParagraph.speakersOf(json, paragraphs)
        )
    }

    private fun applyTranscript(parsed: ParsedTranscript?) {
        showTranscriptLoading(false)
        parsedTranscript = parsed
        transcriptParagraphs = parsed?.paragraphs ?: emptyList()
        transcriptSpeakers = parsed?.speakers ?: emptyList()
        transcriptPlainText = if (transcriptParagraphs.isNotEmpty()) TranscriptParagraph.plain(transcriptParagraphs) else null
        transcriptCopyText = parsed?.copyText ?: transcriptPlainText
        flashHandler.removeCallbacksAndMessages(null)
        adapter.submit(TranscriptRows.build(transcriptParagraphs))
        if (transcriptParagraphs.isNotEmpty()) {
            header.emptyState.visibility = View.GONE
            restorePositionIfAny()
        }
        applyTranscriptState()
    }

    // Test seams: the list's rows and a bound paragraph view, without a laid-out RecyclerView.

    @VisibleForTesting
    internal fun transcriptRowsForTests(): List<TranscriptRow> = adapter.rows

    @VisibleForTesting
    internal fun bindParagraphForTests(index: Int): View {
        val holder = adapter.createViewHolder(binding.transcriptList, TranscriptAdapter.TYPE_PARAGRAPH)
        adapter.bindViewHolder(holder, adapter.positionOf(index))
        return holder.itemView
    }

    @VisibleForTesting
    internal fun nowPlayingIndexForTests(): Int = adapter.nowPlayingIndex

    @VisibleForTesting
    internal fun flashIndexForTests(): Int = adapter.flashIndex

    @VisibleForTesting
    internal fun followPlaybackForTests(): Boolean = followPlayback

    @VisibleForTesting
    internal fun simulateUserScrollForTests() = onUserScrollGesture()

    /** Everything on the page that hangs off "is there a transcript": actions, section header, rename, scroller. */
    private fun applyTranscriptState() {
        val has = transcriptParagraphs.isNotEmpty()
        header.transcriptActions.visibility = if (has) View.VISIBLE else View.GONE
        header.transcriptSectionHeader.visibility = if (has) View.VISIBLE else View.GONE
        adapter.speakerRenameEnabled = has && canRenameSpeakers
        binding.fastScroller.refresh()
    }

    /**
     * Speaker labels are renamable when the server holds the transcript (a rename is a server
     * write that comes back as the re-rendered document) and is not busy replacing it.
     */
    private val canRenameSpeakers: Boolean
        get() = serverRecordingId != null && RecordingStore.isServerConfigured && !transcriptWasPending && isServerTranscriptShown

    /** The paragraphs on screen came from the server in this view (not only the phone's cache). */
    private val isServerTranscriptShown: Boolean get() = serverTranscriptJSON != null && parsedTranscript?.json == serverTranscriptJSON

    private fun showTranscriptLoading(loading: Boolean) {
        header.transcriptLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) header.emptyState.visibility = View.GONE
    }

    /**
     * A document with no segments at all but a flat `text` (one "Speaker N: ..." line per turn):
     * shown as blank-line-separated paragraphs. Null when there is nothing to show.
     */
    private fun flatTranscriptText(json: String): String? =
        transcriptExportFields(json).first?.let { TranscriptMarkdown.plainParagraphs(it) }?.takeIf { it.isNotBlank() }

    /** Meta line "MMM d, yyyy · HH:mm · Xm Ys" (mirrors iOS). */
    private fun bindMetaLine(recordedAtMillis: Long, durationSeconds: Long) {
        binding.fileDateLabel.text = "${formatMetaDate(recordedAtMillis)} · ${formatMetaDuration(durationSeconds)}"
    }

    /** The meta line's date format, shared with the Automations timestamps so the page reads as one. */
    private fun formatMetaDate(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(millis))

    /**
     * The badge only speaks while the server is still working on the recording or gave up on
     * it. A finished recording wears no badge: "Transcribed" or "Uploaded" would just restate
     * that the transcript below exists.
     */
    private fun bindStatusBadge(item: RecordingItem) {
        when (item.status) {
            RecordingItem.Status.TRANSCRIBING ->
                setStatusBadge(RecordingsAdapter.transcribingText(this, item.server), R.attr.pbColorWarning, R.drawable.bg_status_pending)
            RecordingItem.Status.FAILED ->
                setStatusBadge(getString(R.string.status_failed), MaterialR.attr.colorError, R.drawable.bg_status_pending)
            else -> binding.statusBadge.visibility = View.GONE
        }
    }

    private fun setStatusBadge(text: String, colorAttr: Int, backgroundRes: Int) {
        binding.statusBadge.visibility = View.VISIBLE
        binding.statusBadge.text = text
        binding.statusBadge.setTextColor(themeColor(colorAttr))
        binding.statusBadge.setBackgroundResource(backgroundRes)
    }

    /**
     * The bar under the header while the server is working on the recording: determinate when
     * the server reports how far the transcription is, indeterminate for the stages that carry
     * no percentage (queued, speaker identification, summarizing) and for older servers. The
     * indicator refuses to switch to indeterminate while visible, so a mode change hides it first.
     */
    private fun bindTranscriptionProgress(item: RecordingItem) {
        val indicator = binding.transcriptionProgress
        val rec = item.server
        if (item.status != RecordingItem.Status.TRANSCRIBING || rec == null) {
            indicator.visibility = View.GONE
            return
        }
        val percent = rec.progressPercent
        val wantIndeterminate = percent == null
        if (indicator.isIndeterminate != wantIndeterminate) {
            indicator.visibility = View.GONE
            indicator.isIndeterminate = wantIndeterminate
        }
        if (percent != null) indicator.setProgressCompat(percent, indicator.visibility == View.VISIBLE)
        indicator.visibility = View.VISIBLE
    }

    // MARK: - Transcription polling

    /** The one pending re-read of the recording while the server is still transcribing it. */
    private var transcriptionPollRunnable: Runnable? = null

    /**
     * The server is still working on the recording as far as this screen knows: its recording
     * object says so, or, before any recording object has arrived (a legacy phone copy whose id
     * the transcript lookup just resolved), the transcript endpoint answered 409. The latter is a
     * provisional state that the first recording response replaces.
     */
    private val transcriptionInProgress: Boolean
        get() = serverRecording?.isTranscribing ?: transcriptWasPending

    /**
     * While the server reports the recording as queued or transcribing, re-read it every few
     * seconds so the stage and percentage move on their own; once it reports done (or failed)
     * that very object goes through the usual load path (transcript, automations), and the
     * polling stops. Only while this screen is in front: leaving cancels the poll, coming back
     * asks at once. Called from every render, so it is idempotent: one poll pending at a time.
     */
    private fun syncTranscriptionPolling() {
        val wanted = inForeground && transcriptionInProgress &&
            serverRecordingId != null && RecordingStore.isServerConfigured
        if (!wanted) {
            cancelTranscriptionPoll()
            return
        }
        // A read already on the wire schedules the next one itself once it lands.
        if (transcriptionPollRunnable == null && !transcriptionPollInFlight) scheduleTranscriptionPoll(TRANSCRIPTION_POLL_INTERVAL_MS)
    }

    private fun scheduleTranscriptionPoll(delayMs: Long) {
        cancelTranscriptionPoll()
        val serverId = serverRecordingId ?: return
        val poll = Runnable {
            transcriptionPollRunnable = null
            pollTranscription(serverId)
        }
        transcriptionPollRunnable = poll
        routingHandler.postDelayed(poll, delayMs)
    }

    /** Drop the pending (not yet fired) poll. A read already on the wire is left to land. */
    private fun cancelTranscriptionPoll() {
        transcriptionPollRunnable?.let { routingHandler.removeCallbacks(it) }
        transcriptionPollRunnable = null
    }

    /**
     * Leaving the screen: drop the pending poll AND abandon a read on the wire. Its answer would
     * otherwise land after the one the return fires, and an older status applied over a newer
     * one (a stale "transcribing" over "done", or the reverse) is exactly what must not happen.
     */
    private fun stopTranscriptionPolling() {
        cancelTranscriptionPoll()
        transcriptionPollGeneration++ // whatever is on the wire is stale from here on
        transcriptionPollJob?.cancel()
        transcriptionPollJob = null
        transcriptionPollInFlight = false
    }

    /** The poll read on the wire, if any; cancelled when the screen leaves, see [stopTranscriptionPolling]. */
    private var transcriptionPollJob: kotlinx.coroutines.Job? = null

    /** True from the moment a poll read starts until its answer is in hand (or it is abandoned). */
    private var transcriptionPollInFlight = false

    /**
     * Bumped for every read started and for every stop: a read whose generation is no longer
     * the current one is stale and must touch nothing, however and whenever it comes back.
     */
    private var transcriptionPollGeneration = 0

    /**
     * One poll: GET the recording. Still working means re-render (and re-schedule); finished
     * (done or failed) means this object is the one to show, so it goes straight into the load
     * path rather than being fetched a second time (a second GET that failed would leave the
     * badge frozen on the old stage with no poll to move it). One read at a time: a second is
     * never started while one is on the wire, so answers cannot cross.
     */
    private fun pollTranscription(serverId: String) {
        if (transcriptionPollInFlight) return
        transcriptionPollInFlight = true
        val generation = ++transcriptionPollGeneration
        transcriptionPollJob = lifecycleScope.launch {
            val result = try {
                serverSource.recording(serverId)
            } finally {
                // Only the current read owns the flag: a stale one winding down after a
                // pause must not clear it under the read the return started.
                if (generation == transcriptionPollGeneration) transcriptionPollInFlight = false
            }
            // A read abandoned on pause normally never reaches this line (cancelled at the
            // suspension); should its answer arrive anyway, it is stale and applies nothing.
            if (generation != transcriptionPollGeneration) return@launch
            when (result) {
                is ApiClient.RecordingResult.Ok -> {
                    if (result.recording.isTranscribing) {
                        serverRecording = result.recording
                        serverKnowsRecording = true
                        transcriptWasPending = true
                        render()
                    } else {
                        onServerRecordingLoaded(result.recording)
                    }
                }
                // A blip must not freeze the badge on a stale stage: ask again next time round.
                is ApiClient.RecordingResult.Error -> syncTranscriptionPolling()
                // Repeating the same request cannot change these; the next visit asks again.
                is ApiClient.RecordingResult.NotFound, is ApiClient.RecordingResult.AuthError -> {}
            }
        }
    }

    /**
     * Why there is no transcript yet, and the one button that can change that. "Check for
     * transcript" appears whenever the server has (or should have) this recording; a recording
     * that has not left the phone or the recorder yet gets an explanation and no button.
     */
    private fun bindEmptyState(item: RecordingItem) {
        val rec = item.server
        val file = item.local
        when {
            item.serverId != null || file?.uploaded == true -> {
                // A finished recording with nothing in it: one line, and no transcript button
                // (there is nothing to check for; Re-transcribe stays in the menu).
                if (rec?.isTranscribing != true && isNoSpeech) {
                    showEmptyState(getString(R.string.detail_no_speech_line), null, icon = R.drawable.ic_mic_off)
                    return
                }
                // While the server works, the title carries the stage (the badge's wording).
                val title = if (rec?.isTranscribing == true) RecordingsAdapter.transcribingText(this, rec)
                    else getString(R.string.transcript)
                val subtitle = when (rec?.status) {
                    // The server's own sentence about what went wrong; the raw text behind Details.
                    ServerRecording.STATUS_FAILED -> rec.error ?: getString(R.string.transcription_failed)
                    ServerRecording.STATUS_STORED -> getString(R.string.transcript_not_started)
                    else -> getString(R.string.transcription_pending)
                }
                val details = if (rec?.status == ServerRecording.STATUS_FAILED) rec.errorDetail else null
                showEmptyState(title, subtitle, getString(R.string.check_transcript), details = details) {
                    checkForTranscript()
                }
            }
            file != null && file.isSynced ->
                showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_upload))
            else -> showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_sync))
        }
    }

    /**
     * Centered empty state where the transcript would start; [buttonText] null hides the button,
     * [details] (the raw failure text) sits behind a "Details" disclosure when given.
     */
    private fun showEmptyState(
        title: String, subtitle: String?, buttonText: String? = null,
        icon: Int = R.drawable.ic_recordings, details: String? = null, onButton: (() -> Unit)? = null
    ) {
        header.transcriptLoading.visibility = View.GONE
        header.emptyState.visibility = View.VISIBLE
        header.emptyIcon.setImageResource(icon)
        header.emptyTitle.text = title
        header.emptySubtitle.text = subtitle
        header.emptySubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        header.emptyDetails.text = details
        header.emptyDetails.visibility = View.GONE
        header.emptyDetailsToggle.visibility = if (details.isNullOrBlank()) View.GONE else View.VISIBLE
        if (buttonText != null) {
            header.generateButton.visibility = View.VISIBLE
            header.generateButton.text = buttonText
            header.generateButton.isEnabled = true
            header.generateButton.setOnClickListener { onButton?.invoke() }
        } else {
            header.generateButton.visibility = View.GONE
        }
    }

    // MARK: - Server side

    /**
     * GET the recording, then its transcript. The header re-renders as soon as the recording
     * arrives so a server-only recording is not blank while a long transcript downloads; 409
     * (still transcribing) and the other outcomes become the empty state's wording.
     */
    private fun loadServerRecording(id: String) {
        lifecycleScope.launch {
            when (val result = serverSource.recording(id)) {
                is ApiClient.RecordingResult.Ok -> onServerRecordingLoaded(result.recording)
                is ApiClient.RecordingResult.NotFound -> failServer(getString(R.string.recording_not_on_server))
                is ApiClient.RecordingResult.AuthError -> failServer(getString(R.string.transcript_auth_error))
                is ApiClient.RecordingResult.Error -> failServer(getString(R.string.transcript_server_error))
            }
        }
    }

    /**
     * A recording object has arrived (open, Check for transcript, a poll that saw the
     * transcription finish): show it, then load what hangs off it, the automations and the
     * transcript.
     */
    private fun onServerRecordingLoaded(rec: ServerRecording) {
        serverRecording = rec
        serverKnowsRecording = true
        if (rec.isTranscribing) transcriptWasPending = true
        // The server routes nothing for a recording with no speech: no run is coming, so a wait
        // for one (started before the silence was known) has nothing to wait for.
        if (rec.noSpeech) clearRoutingWait()
        render()
        // First read of the automations. A recording uploaded minutes ago may sit in the gap
        // between "transcript done" and "router run inserted": wait for the run rather than
        // declare that nothing ran. Not for a recording with no speech, which is never routed.
        refreshRouting(awaitRun = routingRuns == null && isRecentUpload(rec) && !rec.noSpeech)
        // Nothing on screen yet and a finished transcript on its way: the loading bar, not the
        // empty state's wording, is the honest picture until it lands.
        if (transcriptPlainText == null && !rec.noSpeech) {
            if (rec.isDone) showTranscriptLoading(true) else header.emptySubtitle.text = getString(R.string.checking_transcript)
        }
        loadServerTranscript(rec)
    }

    private fun loadServerTranscript(rec: ServerRecording) {
        lifecycleScope.launch {
            val outcome = serverSource.transcript(rec.id)
            if (transcriptPlainText == null) showTranscriptLoading(false)
            when (outcome) {
                is ApiClient.TranscriptResult.Ready -> {
                    val arrived = transcriptArrived()
                    storeServerTranscript(outcome.rawJson)
                    render()
                    // A transcript that just landed means the server's router is about to run
                    // (detached, after transcription); re-read the automations until its run
                    // shows up and keep polling for the agents. Not for a recording with no
                    // speech in it: there is nothing to route, so no run is coming.
                    if (isNoSpeech) clearRoutingWait()
                    if (arrived) {
                        refreshRouting(awaitRun = !isNoSpeech)
                        // The outcomes should reach the user even after this screen is gone.
                        if (!isNoSpeech) watchAutomationsAfterTranscript(rec.id)
                    }
                }
                is ApiClient.TranscriptResult.Pending -> {
                    transcriptWasPending = true
                    render()
                }
                is ApiClient.TranscriptResult.NotFound ->
                    if (rec.status == ServerRecording.STATUS_DONE) failServer(getString(R.string.recording_not_on_server))
                    else render()
                is ApiClient.TranscriptResult.AuthError -> failServer(getString(R.string.transcript_auth_error))
                is ApiClient.TranscriptResult.Error -> failServer(getString(R.string.transcript_server_error))
            }
        }
    }

    /**
     * Keep the fetched transcript for this view and, when the phone indexes this recording,
     * cache it there too (with its AI title) so the list and the next open are instant. Nothing
     * is written for a server-only recording.
     */
    private fun storeServerTranscript(rawJson: String) {
        serverTranscriptJSON = rawJson
        serverKnowsRecording = true
        val file = currentFile ?: return
        if (file.transcriptJSON == rawJson) return
        val firstCopy = file.transcriptJSON == null
        TitleSyncManager.storeTranscript(file.id, rawJson)
        currentFile = findFile(file.id) ?: file
        // The phone's first copy of this transcript: the recording leaves the background title
        // sync's work list here, so its automations are followed from here too (a recording that
        // finished before its screen opened never reads as "pending" to this screen).
        if (firstCopy && !ServerRecording.transcriptSaysNoSpeech(rawJson)) {
            serverRecordingId?.let { watchAutomationsAfterTranscript(it) }
        }
    }

    /**
     * Did this transcript just land? Yes when the screen saw the transcription pending and the
     * server now answers 200: the transcript endpoint returns 409 for anything but a finished
     * transcription, so the answer itself is the proof, whatever is on screen (after Re-transcribe
     * the old transcript stays visible, and the new one may even read the same). Consumes the
     * pending flag so a later re-read of the same transcript does not count again.
     */
    private fun transcriptArrived(): Boolean {
        if (!transcriptWasPending) return false
        transcriptWasPending = false
        return true
    }

    /**
     * The one transcript button: re-ask the server. With a server id that is a fresh GET of the
     * recording (its status may have moved on) and its transcript; without one the id is first
     * resolved from (device, session).
     */
    private fun checkForTranscript() {
        header.generateButton.isEnabled = false
        header.emptySubtitle.text = getString(R.string.checking_transcript)
        header.emptySubtitle.visibility = View.VISIBLE
        val serverId = serverRecordingId
        val file = currentFile
        when {
            serverId != null -> loadServerRecording(serverId)
            file != null -> fetchTranscriptFromServer(file, userInitiated = true)
        }
    }

    /**
     * A server call failed. With content on screen (a phone copy, or an earlier server answer)
     * the message only replaces the empty-state wording, never the content; with nothing to show
     * yet, the header is cleared and the message is the page.
     */
    private fun failServer(message: String) {
        header.generateButton.isEnabled = true
        showTranscriptLoading(false)
        if (currentItem() == null) {
            binding.fileDateLabel.text = ""
            showEmptyState(getString(R.string.transcript), message, getString(R.string.check_transcript)) {
                checkForTranscript()
            }
        } else if (transcriptPlainText == null) {
            header.emptyState.visibility = View.VISIBLE
            header.emptySubtitle.text = message
            header.emptySubtitle.visibility = View.VISIBLE
        }
        updateToolbarActions()
    }

    /** What to tell the user about a failed server action: the server's sentence, else a generic line. */
    private fun actionErrorMessage(result: ApiClient.ActionResult): String = when (result) {
        is ApiClient.ActionResult.Ok -> ""
        is ApiClient.ActionResult.NotFound -> getString(R.string.recording_not_on_server)
        is ApiClient.ActionResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.ActionResult.Error -> result.detail ?: getString(R.string.detail_request_failed)
    }

    // MARK: - Highlights

    /**
     * One row per highlight: "★ m:ss" in the accent color, then the text (or the server's
     * no-speech placeholder). Rows are plain TextViews built here rather than a RecyclerView
     * because the list is short (one per button press) and lives inside the page's header block.
     * Tapping a row seeks the player to the highlight's start (when there is a player) and
     * scrolls the page to the transcript paragraph holding the bookmark, so a highlight is an
     * easy jump into the text.
     */
    private fun bindHighlights(highlights: List<TranscriptHighlight>) {
        header.highlightsList.removeAllViews()
        val visible = highlights.isNotEmpty()
        header.highlightsHeader.visibility = if (visible) View.VISIBLE else View.GONE
        header.highlightsList.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val accent = themeColor(R.attr.pbColorHighlightAccent)
        val density = resources.displayMetrics.density
        for ((index, h) in highlights.withIndex()) {
            val stamp = "★ ${TranscriptMarkdown.formatTimestamp(h.at)}"
            val body = h.text.ifEmpty { getString(R.string.highlight_no_speech) }
            val row = android.widget.TextView(this).apply {
                text = android.text.SpannableStringBuilder("$stamp  $body").apply {
                    setSpan(
                        android.text.style.ForegroundColorSpan(accent), 0, stamp.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, stamp.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                setTextColor(themeColor(R.attr.pbColorTextBody))
                textSize = 14f
                typeface = android.graphics.Typeface.SANS_SERIF
                setLineSpacing(4 * density, 1f)
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                background = ContextCompat.getDrawable(this@FileDetailActivity, selectableBackgroundRes())
                tag = h
                contentDescription = getString(R.string.detail_seek_to_fmt, TranscriptMarkdown.formatTimestamp(h.at))
                setOnClickListener {
                    seekPlayerTo(h.start)
                    revealParagraphForHighlight(index, h)
                }
            }
            header.highlightsList.addView(row)
        }
    }

    private fun selectableBackgroundRes(): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    /**
     * Scroll the page so the paragraph holding highlight [index] sits near the top and give it a
     * short background flash. The paragraph is found by its bookmark index, or by time when the
     * document's paragraphs do not name it (a derived layout, or a highlight the server dropped).
     */
    private fun revealParagraphForHighlight(index: Int, h: TranscriptHighlight) {
        val paragraphs = transcriptParagraphs
        if (paragraphs.isEmpty()) return
        val position = paragraphs.indexOfFirst { index in it.bookmarks }.takeIf { it >= 0 }
            ?: JumpToItems.paragraphIndexAt(paragraphs, h.at).takeIf { it >= 0 }
            ?: paragraphs.lastIndex
        revealParagraph(position)
    }

    /** Put paragraph [index] at the top of the viewport (header collapsed) and tint it for a moment. */
    private fun revealParagraph(index: Int) {
        if (index !in adapter.rows.indices) return
        binding.appBar.setExpanded(false, false)
        layoutManager.scrollToPositionWithOffset(adapter.positionOf(index), (16 * resources.displayMetrics.density).toInt())
        flashHandler.removeCallbacksAndMessages(null)
        adapter.setFlash(index)
        flashHandler.postDelayed({ adapter.setFlash(-1) }, PARAGRAPH_FLASH_MS)
    }

    /** False under the system's "Remove animations" setting (and in tests), where a jump beats a glide. */
    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    // MARK: - Reading position

    /** Restored once per screen, when the first paragraphs land. */
    private var positionRestored = false

    private fun positionKey(): String? = positionStore.keyFor(serverRecordingId, currentFile?.id)

    private fun restorePositionIfAny() {
        if (positionRestored) return
        positionRestored = true
        val key = positionKey() ?: return
        val position = positionStore.load(key) ?: return
        if (position.paragraphIndex !in adapter.rows.indices) return
        binding.appBar.setExpanded(false, false)
        layoutManager.scrollToPositionWithOffset(adapter.positionOf(position.paragraphIndex), -position.offsetPx)
    }

    /** Remember where the reader is: the first visible paragraph and how far it is scrolled past the top. */
    @VisibleForTesting
    internal fun savePosition() {
        val key = positionKey() ?: return
        if (adapter.rows.isEmpty()) return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val paragraphIndex = adapter.paragraphIndexOf(first)
        if (paragraphIndex < 0) {
            positionStore.save(key, null) // the header is in view: back at the top
            return
        }
        val child = layoutManager.findViewByPosition(first)
        val offset = (child?.let { binding.transcriptList.paddingTop - it.top } ?: 0).coerceAtLeast(0)
        positionStore.save(key, TranscriptPositionStore.Position(paragraphIndex, offset))
    }

    // MARK: - Automations

    /** Older runs are collapsed behind "Show earlier runs" until tapped; reset per page view. */
    private var showEarlierRuns = false

    private val routingHandler = Handler(Looper.getMainLooper())

    /** One fetch at a time; a request that arrives mid-flight is honoured once the current one lands. */
    private var routingLoading = false
    private var routingReloadRequested = false

    /** Which of [ROUTING_POLL_DELAYS_MS] the next in-progress poll uses; reset by every refresh. */
    private var routingPollIndex = 0

    /** The one pending in-progress poll, so a refresh can cancel it without touching other timers. */
    private var routingPollRunnable: Runnable? = null

    /**
     * A run is expected but may not exist yet (the router starts after the transcript lands and
     * runs detached). While set, an empty answer keeps the polling going and the "No automations
     * ran" line stays out of sight, so the user does not read a verdict that is about to change.
     * Cleared only when the run shows up: a spent polling budget stops the reads but is not proof
     * that nothing ran (a slow model, a backlog), so the verdict stays open until the next refresh
     * (a return to the screen, Run automations, Retry) asks again.
     */
    private var routingAwaitingRun = false

    /**
     * The latest run already on screen when the wait began. After Re-transcribe the history is
     * not empty, so "a run exists" is not the signal; "a run NEWER than this one exists" is.
     */
    private var routingAwaitBaselineRunId: String? = null

    /**
     * The wait began while a read was already on the wire: that read predates the arrival, so its
     * latest run is the baseline (not the stale null of a screen that had no history yet), and it
     * must not be mistaken for the awaited run.
     */
    private var routingAwaitBaselinePending = false

    /**
     * The routing endpoint answered 404 for a recording the server does have: an older server
     * without automations. The section stays hidden and the menu stops offering Run automations,
     * which would fail the same way.
     */
    private var routingUnsupported = false

    /**
     * The server has confirmed this recording exists (it returned the recording object, its
     * transcript, or its id from the lookup). Only then is a routing 404 about the endpoint
     * rather than the recording.
     */
    private var serverKnowsRecording = false

    /**
     * (Re)load the routing section and start a fresh polling budget. Every trigger (open,
     * resume, transcript arrival, Run automations, Retry) goes through here so the polling
     * cannot stack: the pending poll is cancelled and rescheduled from the new fetch.
     * [awaitRun] marks the transcript-arrival case, see [routingAwaitingRun].
     */
    private fun refreshRouting(awaitRun: Boolean = false, onSettled: (() -> Unit)? = null) {
        routingPollRunnable?.let { routingHandler.removeCallbacks(it) }
        routingPollRunnable = null
        routingPollIndex = 0
        if (awaitRun) {
            routingAwaitingRun = true
            routingAwaitBaselineRunId = routingRuns?.firstOrNull()?.id
            routingAwaitBaselinePending = routingLoading
        }
        onSettled?.let { routingSettledCallbacks += it }
        loadRouting()
    }

    /**
     * Nothing to wait for after all (the recording turned out to have no speech, which the server
     * never routes): drop the wait for a run, and the poll it was keeping alive unless an agent
     * is still working on an earlier run, which the poll is also there to follow.
     */
    private fun clearRoutingWait() {
        routingAwaitingRun = false
        routingAwaitBaselinePending = false
        if (routingRuns?.any { it.hasInProgressDelivery } == true) return
        routingPollRunnable?.let { routingHandler.removeCallbacks(it) }
        routingPollRunnable = null
    }

    /** Run once the next routing read (and any reload queued behind it) has landed or been skipped. */
    private val routingSettledCallbacks = mutableListOf<() -> Unit>()

    private fun drainRoutingSettledCallbacks() {
        val callbacks = routingSettledCallbacks.toList()
        routingSettledCallbacks.clear()
        callbacks.forEach { it() }
    }

    /** Uploaded within the last few minutes: transcription and routing may still be settling. */
    private fun isRecentUpload(rec: ServerRecording): Boolean {
        val uploadedAt = rec.uploadedAt ?: return false
        return System.currentTimeMillis() - uploadedAt < RECENT_UPLOAD_WINDOW_MS
    }

    private fun loadRouting() {
        val serverId = serverRecordingId
        if (serverId == null || !RecordingStore.isServerConfigured) {
            drainRoutingSettledCallbacks()
            return
        }
        if (routingLoading) {
            routingReloadRequested = true
            return
        }
        routingLoading = true
        lifecycleScope.launch {
            val result = serverSource.routing(serverId)
            routingLoading = false
            when (result) {
                is ApiClient.RoutingResult.Ok -> {
                    routingUnsupported = false
                    routingRuns = result.runs
                    val latestId = result.runs.firstOrNull()?.id
                    if (routingAwaitBaselinePending) {
                        routingAwaitBaselineRunId = latestId
                        routingAwaitBaselinePending = false
                    } else if (latestId != null && latestId != routingAwaitBaselineRunId) {
                        routingAwaitingRun = false
                    }
                    scheduleRoutingPollIfNeeded(result.runs)
                    bindAutomations()
                }
                // Failures are silent: an old server without the endpoint, or a blip, must not
                // put an error where the transcript is the point of the page. What was shown
                // before stays, and a poll that failed still counts against the budget so one
                // dropped request does not leave a Working line frozen.
                is ApiClient.RoutingResult.NotFound -> {
                    if (serverKnowsRecording) routingUnsupported = true
                }
                is ApiClient.RoutingResult.AuthError,
                is ApiClient.RoutingResult.Error -> {
                    scheduleRoutingPollIfNeeded(routingRuns ?: emptyList())
                    bindAutomations() // the budget may just have run out, which changes what shows
                }
            }
            if (routingReloadRequested) {
                routingReloadRequested = false
                loadRouting()
            } else {
                drainRoutingSettledCallbacks()
            }
        }
    }

    /**
     * While an agent is still working (on any run: a retried delivery lives on an older one) or
     * the router's run has yet to appear, read again a few times, then stop.
     */
    private fun scheduleRoutingPollIfNeeded(runs: List<RoutingRun>) {
        val working = runs.any { it.hasInProgressDelivery }
        if (!working && !routingAwaitingRun) return
        // Budget spent: stop reading; the awaiting state (if any) stays, see [routingAwaitingRun].
        // A poll already waiting will read soon enough; a read that overlapped it (a queued
        // reload) must not push it out and spend a slot of the budget.
        if (routingPollRunnable != null) return
        val delay = ROUTING_POLL_DELAYS_MS.getOrNull(routingPollIndex) ?: return
        routingPollIndex++
        val poll = Runnable {
            routingPollRunnable = null
            loadRouting()
        }
        routingPollRunnable = poll
        routingHandler.postDelayed(poll, delay)
    }

    /**
     * The section shows when the server has told us something: runs to list, or the certainty
     * that routing ran and matched nothing (an empty list on a transcribed recording). Before the
     * first answer, without a server copy, or while the recording is still being transcribed
     * (routing has not had its turn yet) the section stays out of the way.
     */
    private fun bindAutomations() {
        val runs = routingRuns
        header.automationsList.removeAllViews()
        val transcribed = serverTranscriptReady || serverRecording?.isDone == true
        // A recording with no speech had nothing to route: "No automations ran" would be noise.
        val showEmpty = runs != null && runs.isEmpty() && transcribed && !routingAwaitingRun && !isNoSpeech
        val showRuns = runs != null && runs.isNotEmpty()
        header.automationsHeader.visibility = if (showEmpty || showRuns) View.VISIBLE else View.GONE
        header.automationsEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        header.automationsList.visibility = if (showRuns) View.VISIBLE else View.GONE
        if (runs == null || !showRuns) {
            header.automationsShowEarlier.visibility = View.GONE
            return
        }
        val shown = if (showEarlierRuns) runs else runs.take(1)
        shown.forEachIndexed { index, run -> header.automationsList.addView(buildRunBlock(run, isLatest = index == 0)) }
        header.automationsShowEarlier.visibility = if (runs.size > 1 && !showEarlierRuns) View.VISIBLE else View.GONE
    }

    /**
     * One router run: the matched routes with their reasons and delivery outcomes, or the reason
     * nothing happened (router error, or no route matched). Plain views built here rather than a
     * RecyclerView for the same reason as the highlights: a handful of rows inside the header.
     */
    private fun buildRunBlock(run: RoutingRun, isLatest: Boolean): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        // One card per run, so the section reads as blocks rather than a column of grey text.
        val block = android.widget.LinearLayout(this).apply {
            id = R.id.automation_run_block
            orientation = android.widget.LinearLayout.VERTICAL
            tag = run
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(6), dp(16), dp(14))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        if (!isLatest) {
            block.addView(mutedText(getString(R.string.automations_earlier_run_fmt, run.createdAt?.let { formatMetaDate(it) } ?: ""))
                .apply { id = R.id.automation_run_header; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        }
        // What the user told the automations when starting this run by hand.
        run.instructions?.let { instructions ->
            block.addView(mutedText(getString(R.string.automations_instructions_fmt, instructions)).apply {
                id = R.id.automation_run_instructions
                setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.ITALIC)
                setPadding(0, dp(8), 0, 0)
            })
        }
        run.error?.let { error ->
            block.addView(android.widget.TextView(this).apply {
                id = R.id.automation_run_error
                text = error
                setTextColor(themeColor(MaterialR.attr.colorError))
                textSize = 13f
                typeface = android.graphics.Typeface.SANS_SERIF
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        if (run.routes.isEmpty() && run.deliveries.isEmpty() && run.error == null) {
            val stamp = run.createdAt?.let { " · " + formatMetaDate(it) } ?: ""
            block.addView(mutedText(getString(R.string.automations_no_match) + stamp).apply {
                id = R.id.automation_no_match
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        // Per route: name with a state icon, then what happened (the headline), then the
        // router's reason last and muted, since "why it ran" matters less than "what it did".
        val covered = mutableSetOf<String>()
        var routesShown = 0
        fun addRoute(name: String, reason: String?, deliveries: List<Delivery>) {
            if (routesShown++ > 0) block.addView(View(this).apply {
                setBackgroundColor(themeColor(R.attr.pbColorDivider))
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { topMargin = dp(12) }
            })
            block.addView(buildRouteRow(name, deliveries))
            deliveries.forEach { block.addView(buildDeliveryRow(it)) }
            if (reason != null) block.addView(buildReasonRow(reason))
        }
        for (route in run.routes) {
            covered += route.name
            addRoute(route.name, route.reason, run.deliveriesFor(route.name))
        }
        // A delivery whose route the decision does not list (should not happen; shown so nothing
        // the server did is invisible).
        run.deliveries.filter { it.routeName !in covered }.groupBy { it.routeName }.forEach { (name, list) ->
            addRoute(name.ifBlank { "?" }, null, list)
        }
        return block
    }

    /** Worst state among a route's hand-offs, for the icon next to its name. */
    private enum class RouteState { WORKING, DONE, FAILED, UNKNOWN, HANDED_OFF }

    /**
     * Same precedence as [deliveryPresentation], so the icon agrees with the words: the agent's
     * report first (a "done" report outranks a hand-off that later read as failed), the hand-off
     * status only without one. A webhook that was accepted and will not report is neither
     * working nor done: handed off, shown neutral.
     */
    private fun routeState(deliveries: List<Delivery>): RouteState? {
        if (deliveries.isEmpty()) return null
        val states = deliveries.map { d ->
            when (d.resultStatus) {
                Delivery.RESULT_DONE -> RouteState.DONE
                Delivery.RESULT_FAILED -> RouteState.FAILED
                Delivery.RESULT_UNKNOWN -> RouteState.UNKNOWN
                Delivery.RESULT_QUEUED -> RouteState.WORKING
                else -> when {
                    d.status == Delivery.STATUS_FAILED -> RouteState.FAILED
                    d.status == Delivery.STATUS_PENDING -> RouteState.WORKING
                    d.actionType == Delivery.ACTION_WEBHOOK -> RouteState.HANDED_OFF
                    else -> RouteState.DONE
                }
            }
        }
        // Failed beats working beats unknown beats handed-off beats done: what still needs attention.
        val rank = mapOf(RouteState.FAILED to 0, RouteState.WORKING to 1, RouteState.UNKNOWN to 2, RouteState.HANDED_OFF to 3, RouteState.DONE to 4)
        return states.minByOrNull { rank.getValue(it) }
    }

    /** Route name with a state icon in front: green check, amber sync while working, red warning. */
    private fun buildRouteRow(name: String, deliveries: List<Delivery>): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val state = routeState(deliveries)
        val (icon, colorAttr) = when (state) {
            RouteState.DONE -> R.drawable.ic_check_circle to R.attr.pbColorSuccess
            RouteState.WORKING -> R.drawable.ic_sync to R.attr.pbColorWarning
            RouteState.FAILED -> R.drawable.ic_warning to MaterialR.attr.colorError
            RouteState.UNKNOWN -> R.drawable.ic_warning to MaterialR.attr.colorOnSurfaceVariant
            RouteState.HANDED_OFF, null -> R.drawable.ic_bolt to MaterialR.attr.colorOnSurfaceVariant
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(android.widget.ImageView(this@FileDetailActivity).apply {
                id = R.id.automation_route_icon
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(themeColor(colorAttr))
                contentDescription = null
            }, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(android.widget.TextView(this@FileDetailActivity).apply {
                id = R.id.automation_route_name
                text = name
                setTextColor(themeColor(MaterialR.attr.colorOnSurface))
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** The router's reason, muted and collapsed to a few lines; tap to unfold. */
    private fun buildReasonRow(reason: String): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return mutedText(reason).apply {
            id = R.id.automation_route_reason
            textSize = 12.5f
            maxLines = REASON_COLLAPSED_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(30), dp(6), 0, 0)
            setOnClickListener {
                maxLines = if (maxLines == REASON_COLLAPSED_LINES) Int.MAX_VALUE else REASON_COLLAPSED_LINES
            }
        }
    }

    /**
     * One delivery: the agent's report when there is one ("Saved to Meetings/Note.md"), a chip
     * only while it is still working or when it failed, the time underneath, and a Retry pill
     * when the server allows one. The agent's own report (result_status) speaks first; only
     * without one does the hand-off status stand in.
     */
    private fun buildDeliveryRow(d: Delivery): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val presentation = deliveryPresentation(d)
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.automation_delivery_row
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            // Indented under the route's icon so name → outcome → reason line up as one column.
            setPadding(dp(30), dp(6), 0, dp(2))
            tag = d
        }
        presentation.chip?.let { chipText ->
            row.addView(android.widget.TextView(this).apply {
                id = R.id.automation_delivery_pill
                text = chipText
                setTextColor(themeColor(presentation.chipColorAttr))
                setBackgroundResource(presentation.chipBackground)
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                includeFontPadding = false
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); marginEnd = dp(8) })
        }
        val textColumn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            presentation.detail?.let { detail ->
                // The outcome is the headline of the card: body colour, a size up from the reason.
                addView(android.widget.TextView(this@FileDetailActivity).apply {
                    id = R.id.automation_delivery_text
                    text = detail
                    setTextColor(themeColor(R.attr.pbColorTextBody))
                    textSize = 14f
                    typeface = android.graphics.Typeface.SANS_SERIF
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
            d.effectiveAt?.let { at ->
                addView(mutedText(formatMetaDate(at)).apply {
                    id = R.id.automation_delivery_time
                    textSize = 12f
                    setPadding(0, if (presentation.detail != null) dp(2) else 0, 0, 0)
                })
            }
        }
        row.addView(textColumn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (d.canRetry) {
            row.addView(android.widget.TextView(this).apply {
                id = R.id.automation_retry
                text = getString(R.string.retry)
                setTextColor(themeColor(MaterialR.attr.colorOnSurface))
                setBackgroundResource(R.drawable.bg_pill_outline_gray)
                textSize = 12f
                typeface = android.graphics.Typeface.SANS_SERIF
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { retryDelivery(d) }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)
            ).apply { marginStart = dp(8) })
        }
        return row
    }

    /**
     * How one delivery reads: [chip] only while in flight or failed (null otherwise), [detail]
     * the outcome in words (the agent's summary alone when there is one), or null when the chip
     * says it all.
     */
    private data class DeliveryPresentation(val chip: String?, val chipColorAttr: Int, val chipBackground: Int, val detail: String?)

    private fun deliveryPresentation(d: Delivery): DeliveryPresentation = when (d.resultStatus) {
        Delivery.RESULT_QUEUED -> DeliveryPresentation(
            getString(R.string.automation_state_working), R.attr.pbColorWarning, R.drawable.bg_status_pending, null
        )
        Delivery.RESULT_DONE -> DeliveryPresentation(null, 0, 0, d.resultSummary ?: getString(R.string.automation_state_done))
        Delivery.RESULT_FAILED -> DeliveryPresentation(
            getString(R.string.automation_state_failed), MaterialR.attr.colorError, R.drawable.bg_status_pending,
            d.resultSummary ?: d.lastError
        )
        // The job was accepted but never reported back within the server's deadline: neither
        // good nor bad news; Retry is offered because the server allows it.
        Delivery.RESULT_UNKNOWN -> DeliveryPresentation(null, 0, 0, getString(R.string.automation_no_report_text))
        else -> when (d.status) {
            Delivery.STATUS_FAILED -> DeliveryPresentation(
                getString(R.string.automation_state_failed), MaterialR.attr.colorError, R.drawable.bg_status_pending, d.lastError
            )
            Delivery.STATUS_PENDING -> DeliveryPresentation(
                getString(R.string.detail_delivery_waiting), R.attr.pbColorWarning, R.drawable.bg_status_pending, null
            )
            // "ok" without a report: a webhook was handed to something that has not (or will
            // not) report back; a markdown or decision-only action ran to completion right there.
            else -> DeliveryPresentation(
                null, 0, 0,
                if (d.actionType == Delivery.ACTION_WEBHOOK) getString(R.string.automation_handed_off_text)
                else getString(R.string.automation_state_done)
            )
        }
    }

    /** The user unfolded the summary; a re-render keeps it open. */
    private var summaryExpanded = false

    /**
     * A summary longer than [SUMMARY_COLLAPSED_LINES] lines folds to that many with a "Read more"
     * line under it, so the top of the page stays scannable (summary, automations, transcript)
     * instead of one long column of text. Short summaries show whole with no toggle. Decided
     * after layout, from the rendered line count.
     */
    private fun foldLongSummary() {
        val text = header.summaryText
        val more = header.summaryMore
        text.maxLines = if (summaryExpanded) Int.MAX_VALUE else SUMMARY_COLLAPSED_LINES
        text.ellipsize = if (summaryExpanded) null else android.text.TextUtils.TruncateAt.END
        more.text = getString(if (summaryExpanded) R.string.show_less else R.string.read_more)
        more.setOnClickListener {
            summaryExpanded = !summaryExpanded
            foldLongSummary()
        }
        // Re-decided on every layout of the text, not once: the wide-screen column cap lands in
        // its own post-layout pass and can turn a summary that fit into one that overflows.
        if (!summaryFoldWatched) {
            summaryFoldWatched = true
            text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshSummaryMore() }
        }
        text.post { refreshSummaryMore() }
    }

    private var summaryFoldWatched = false

    /** Show "Read more" only while the folded summary actually hides something (or is unfolded). */
    private fun refreshSummaryMore() {
        val text = header.summaryText
        val layout = text.layout ?: return
        val overflows = summaryExpanded || layout.lineCount >= SUMMARY_COLLAPSED_LINES &&
            (layout.lineCount > SUMMARY_COLLAPSED_LINES || layout.getEllipsisCount(layout.lineCount - 1) > 0)
        header.summaryMore.visibility = if (overflows && text.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun mutedText(text: CharSequence): android.widget.TextView = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        textSize = 13f
        typeface = android.graphics.Typeface.SANS_SERIF
    }

    /** Ask the server to run the failed delivery again, then watch the section for the outcome. */
    private fun retryDelivery(d: Delivery) {
        lifecycleScope.launch {
            when (val result = serverSource.retryDelivery(d.id)) {
                is ApiClient.RetryResult.Ok -> {
                    snack(getString(R.string.automation_retry_queued))
                    refreshRouting()
                    // Follow the retried hand-off beyond this screen: every OTHER hand-off on
                    // screen is known, this one (same id, next attempt) is what to wait for.
                    serverRecordingId?.let { serverId ->
                        val others = routingRuns.orEmpty().flatMap { it.deliveries }.map { it.id }.filter { it != d.id }.toSet()
                        val title = currentFile?.displayName ?: serverRecording?.displayTitle
                        org.plaudbridge.app.managers.AutomationWatcher.watch(
                            serverId, currentFile?.id, title, others,
                            forgetDeliveryIds = setOf(d.id), forgetRunIds = setOfNotNull(d.routerRunId)
                        )
                    }
                }
                // The delivery moved on without us (retried elsewhere, or it succeeded after
                // all): the refreshed section is the answer, no dialog needed.
                is ApiClient.RetryResult.Conflict -> refreshRouting()
                is ApiClient.RetryResult.NotFound -> showAlert(getString(R.string.automations), getString(R.string.recording_not_on_server))
                is ApiClient.RetryResult.AuthError -> showAlert(getString(R.string.automations), getString(R.string.transcript_auth_error))
                is ApiClient.RetryResult.Error -> {
                    showAlert(getString(R.string.automations), result.detail ?: getString(R.string.detail_request_failed))
                    // A lost response may hide a retry the server did run: show its state.
                    refreshRouting()
                }
            }
        }
    }

    /**
     * Menu: run the AI router again. The router itself answers within seconds; the agents it
     * hands off to report later, so the section is re-read on a short schedule and each read
     * keeps polling while a delivery is still working.
     *
     * The call is synchronous on the server and not idempotent (each call is a new run with real
     * side effects: notes written, sessions started), so it runs in a process-wide scope with a
     * process-wide in-flight set: a second tap, on this screen or on the one that replaces it
     * after a rotation, must not send another while the first is still on the wire. The outcome
     * is handed to whichever screen shows the recording when it lands (this one, or its
     * replacement after a rotation), so the replacement gets the feedback and the refreshes too.
     *
     * The guard is released at once for a definitive answer (success, 404, auth, 409). An
     * ambiguous error (timeout, lost response, 5xx) may hide a run the server did create, so the
     * guard stays until the live screen has re-read the section and can show it; with no screen
     * left to reconcile, it is released, and the next open reads the history fresh anyway.
     */
    private fun runAutomations(serverId: String, instructions: String? = null) {
        val history = routingRuns ?: return // not loaded yet; the menu item is disabled then
        if (!rerunsInFlight.add(serverId)) return
        val baselineRunId = history.firstOrNull()?.id
        // One idempotency key per user intent: kept across an ambiguous failure so the next
        // tap re-sends the same key AND the same instructions, and the server replays the run
        // it already made instead of starting a second one; dropped once the server has
        // answered definitively. While an intent is unresolved it is the only thing that may
        // go on the wire, whatever the user typed this time: the earlier request may still be
        // running on the server (routing can take minutes), and a second one with other
        // instructions would run its side effects alongside. The user is told, so the new words
        // are not silently lost; once the earlier run is settled a fresh tap gets its own key.
        val pending = pendingReruns[serverId]
        val intent = pending ?: RerunIntent(UUID.randomUUID().toString(), instructions)
        if (pending != null && pending.instructions != instructions) {
            snack(getString(R.string.automations_retrying_earlier), Snackbar.LENGTH_LONG)
        }
        pendingReruns[serverId] = intent
        // Follow the outcomes beyond this screen from the moment the request goes out: routing
        // can take minutes and the user may well have left before the server answers. A definite
        // failure below takes the watch back; an ambiguous one may still have produced a run.
        watchAutomations(serverId)
        rerunScope.launch {
            val result = try {
                serverSource.rerunRouting(serverId, intent.key, intent.instructions)
            } catch (e: Throwable) {
                rerunsInFlight.remove(serverId)
                org.plaudbridge.app.managers.AutomationWatcher.unwatch(serverId)
                throw e
            }
            if (result !is ApiClient.ActionResult.Ok && !isAmbiguousRerunFailure(result)) {
                org.plaudbridge.app.managers.AutomationWatcher.unwatch(serverId)
            }
            // Only companion state and ids from here: this coroutine may outlive the screen that
            // started it by minutes and must not keep that screen (and its views) alive.
            val screen = liveScreens[serverId]
            if (!isAmbiguousRerunFailure(result)) pendingReruns.remove(serverId)
            if (screen == null || !isAmbiguousRerunFailure(result)) rerunsInFlight.remove(serverId)
            screen?.onRerunFinished(result, baselineRunId) { runFound ->
                rerunsInFlight.remove(serverId)
                // The run the lost response was about has shown up: the intent is spent, so the
                // next tap is a new one and must not replay it. Not found: keep the key, the
                // request may still have landed and a replay is the safe outcome.
                if (runFound) pendingReruns.remove(serverId)
            }
        }
    }

    /**
     * Menu: run the router with a note from the user ("file this as a work meeting"). A
     * multi-line field with a counter; Run stays off until something is typed, and the text is
     * trimmed and cut to what the server accepts before it goes to [runAutomations].
     */
    private fun showRunWithInstructionsDialog(serverId: String) {
        val view = layoutInflater.inflate(R.layout.dialog_run_instructions, null)
        val field = view.findViewById<EditText>(R.id.instructionsField)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.run_automations)
            .setView(view)
            .setPositiveButton(R.string.run) { _, _ ->
                val text = field.text.toString().trim().take(ApiClient.ROUTE_INSTRUCTIONS_MAX)
                if (text.isNotEmpty()) runAutomations(serverId, text)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        val run = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        run.isEnabled = !field.text.isNullOrBlank()
        field.doAfterTextChanged { run.isEnabled = !it.isNullOrBlank() }
        field.requestFocus()
    }

    /**
     * Feedback and follow-up for a finished Run automations call. On an ambiguous error the
     * request may well have reached the server and be creating a run with real side effects, so
     * the section is re-read on the same schedule as a success and [onReconciled] (which releases
     * the in-flight guard) runs only once a run newer than [baselineRunId] has shown up, or the
     * scheduled reads are exhausted: the user sees what happened before a second tap is possible.
     */
    private fun onRerunFinished(result: ApiClient.ActionResult, baselineRunId: String?, onReconciled: (runFound: Boolean) -> Unit) {
        if (isDestroyed || isFinishing) {
            onReconciled(false)
            return
        }
        when (result) {
            is ApiClient.ActionResult.Ok -> {
                snack(getString(R.string.automations_queued))
                for (delay in RERUN_REFRESH_DELAYS_MS) routingHandler.postDelayed({ refreshRouting() }, delay)
            }
            is ApiClient.ActionResult.Error -> {
                // 409: no transcript to route yet (the menu hides the action then, but the
                // transcript can vanish under a re-transcribe between the two).
                showAlert(
                    getString(R.string.automations),
                    if (result.code == 409) result.detail ?: getString(R.string.automations_need_transcript) else actionErrorMessage(result)
                )
                if (isAmbiguousRerunFailure(result)) reconcileRerun(baselineRunId, onReconciled)
            }
            else -> showAlert(getString(R.string.automations), actionErrorMessage(result))
        }
    }

    /**
     * Follow this recording's automations beyond the screen (AutomationWatcher posts a
     * notification per outcome). Every hand-off already on screen is passed as known so the
     * previous run's results are not announced again.
     */
    private fun watchAutomations(serverId: String) {
        val runs = routingRuns.orEmpty()
        val knownDeliveries = runs.flatMap { it.deliveries }.map { it.id }.toSet()
        val knownRuns = runs.map { it.id }.toSet()
        val title = currentFile?.displayName ?: serverRecording?.displayTitle
        org.plaudbridge.app.managers.AutomationWatcher.watch(serverId, currentFile?.id, title, knownDeliveries, knownRuns)
    }

    /**
     * Same, when a transcript just landed: nothing on screen is passed as known, because the
     * routing section may already show the run this transcript produced (the two fetches race)
     * and marking it known would silence its outcomes. History is told apart by what was
     * announced before, which the watcher tracks itself.
     */
    private fun watchAutomationsAfterTranscript(serverId: String) {
        val title = currentFile?.displayName ?: serverRecording?.displayTitle
        org.plaudbridge.app.managers.AutomationWatcher.watch(serverId, currentFile?.id, title)
    }

    /** One ambiguous rerun being reconciled, see [reconcileRerun]. [release] is idempotent. */
    private inner class RerunReconciliation(private val baselineRunId: String?, private val onDone: (runFound: Boolean) -> Unit) {
        private var released = false
        var readsLeft = RERUN_REFRESH_DELAYS_MS.size + 1

        fun release(runFound: Boolean = false) {
            if (released) return
            released = true
            rerunReconciliations.remove(this)
            onDone(runFound)
        }

        /** After each read: a newer run than the one before the tap settles it; so does the last read. */
        fun onRead() {
            readsLeft--
            val latest = routingRuns?.firstOrNull()?.id
            val runFound = latest != null && latest != baselineRunId
            if (runFound || readsLeft <= 0) release(runFound)
        }
    }

    private val rerunReconciliations = mutableListOf<RerunReconciliation>()

    private fun reconcileRerun(baselineRunId: String?, onReconciled: (runFound: Boolean) -> Unit) {
        val reconciliation = RerunReconciliation(baselineRunId, onReconciled)
        rerunReconciliations += reconciliation
        refreshRouting(onSettled = { reconciliation.onRead() })
        for (delay in RERUN_REFRESH_DELAYS_MS) {
            routingHandler.postDelayed({ refreshRouting(onSettled = { reconciliation.onRead() }) }, delay)
        }
    }

    private fun showAlert(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A short confirmation, above the player when there is one. */
    private fun snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT) {
        val snackbar = Snackbar.make(binding.coordinator, text, duration)
        if (binding.audioPlayer.visibility == View.VISIBLE) snackbar.anchorView = binding.bottomBar
        snackbar.show()
    }

    // MARK: - Transcript for a phone copy without a server id

    /** Guard so the automatic check on open runs only once per page view. */
    private var transcriptChecked = false

    /**
     * Fetch the transcript for a phone copy whose server id is not known: resolve it by
     * device_sn + session_id, then GET /recordings/{id}/transcript. 404/409 means the
     * transcription is still pending. Once the id is known it is remembered, and every later
     * check goes through [loadServerRecording].
     */
    private fun fetchTranscriptFromServer(file: RecordingFile, userInitiated: Boolean) {
        if (!userInitiated) {
            if (transcriptChecked) return
            transcriptChecked = true
        }
        if (!RecordingStore.isServerConfigured) return
        header.generateButton.isEnabled = false
        header.emptySubtitle.text = getString(R.string.checking_transcript)
        header.emptySubtitle.visibility = View.VISIBLE

        lifecycleScope.launch {
            var foundByLookup = false
            // The server may be switched while the lookup is on the wire; an id from the OLD
            // server must not be written into the index (the same guard TitleSyncManager uses).
            val configGen = RecordingStore.serverConfigGeneration
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val cachedId = file.serverId
                    if (cachedId != null) {
                        ApiClient.fetchTranscript(cachedId)
                    } else {
                        // Look up with the recording's OWN device SN (blank stays blank: the
                        // upload sent the SN as-is, so substituting the connected device's SN
                        // could resolve to a DIFFERENT device's recording).
                        when (val lookup = ApiClient.lookupRecordingId(file.deviceSN, file.sessionId)) {
                            is ApiClient.LookupResult.Found -> {
                                if (RecordingStore.serverConfigGeneration != configGen) {
                                    return@withContext ApiClient.TranscriptResult.Error("server changed")
                                }
                                RecordingStore.updateServerId(file.id, lookup.id)
                                foundByLookup = true
                                ApiClient.fetchTranscript(lookup.id)
                            }
                            is ApiClient.LookupResult.NotFound -> ApiClient.TranscriptResult.NotFound
                            is ApiClient.LookupResult.AuthError -> ApiClient.TranscriptResult.AuthError(lookup.code)
                            is ApiClient.LookupResult.Error -> ApiClient.TranscriptResult.Error(lookup.message)
                        }
                    }
                } catch (e: Exception) {
                    ApiClient.TranscriptResult.Error(e.message ?: "network error")
                }
            }

            if (RecordingStore.serverConfigGeneration != configGen) {
                // Answered by a server the app no longer talks to: show nothing from it.
                header.generateButton.isEnabled = true
                return@launch
            }
            // The lookup may have stored a server id; from here on the server path owns it.
            currentFile = findFile(file.id) ?: file
            if (serverRecordingId == null) serverRecordingId = currentFile?.serverId?.takeIf { it.isNotBlank() }
            if (foundByLookup) serverKnowsRecording = true
            registerAsLiveScreen()
            header.generateButton.isEnabled = true
            when (outcome) {
                is ApiClient.TranscriptResult.Ready -> {
                    val arrived = transcriptArrived()
                    storeServerTranscript(outcome.rawJson)
                    render()
                    if (arrived && serverRecordingId != null) {
                        refreshRouting(awaitRun = true)
                        if (!isNoSpeech) serverRecordingId?.let { watchAutomationsAfterTranscript(it) }
                    }
                }
                is ApiClient.TranscriptResult.Pending -> {
                    transcriptWasPending = true
                    header.emptySubtitle.text = getString(R.string.transcription_pending)
                    bindAutomations() // a cached transcript on screen no longer counts as routable
                    // With the id now known, the recording object can be watched until it is
                    // done (provisionally, on the strength of the 409, see transcriptionInProgress).
                    syncTranscriptionPolling()
                }
                is ApiClient.TranscriptResult.NotFound -> {
                    header.emptySubtitle.text = getString(R.string.transcript_not_on_server)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_not_on_server))
                }
                is ApiClient.TranscriptResult.AuthError -> {
                    header.emptySubtitle.text = getString(R.string.transcript_auth_error)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_auth_error))
                }
                is ApiClient.TranscriptResult.Error -> {
                    header.emptySubtitle.text = getString(R.string.transcript_server_error)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_server_error))
                }
            }
            // With the id known this can be the very first read of the automations, whatever the
            // transcript answer was (a cached transcript is on screen when it is Pending or Error).
            // Skipped when the Ready branch above already started one.
            if (serverRecordingId != null && routingRuns == null && !routingLoading) refreshRouting()
            updateToolbarActions()
        }
    }

    private fun showTranscriptAlert(message: String) = showAlert(getString(R.string.transcript), message)

    /** "Xm Ys" / "Xh Ym Zs" duration for the meta line. */
    private fun formatMetaDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0 || h > 0) append("${m}m ")
            append("${s}s")
        }.trim()
    }

    // MARK: - Audio player

    /** The screen's handle on the app's one player (in [PlaybackService]); null before onCreate binds it. */
    private var playback: Playback? = null

    /** What this screen wants played; null when the recording has no audio the phone can reach. */
    private var boundItem: Playback.Item? = null

    /** The slider is under the reader's finger: the ticker must not move it. */
    private var scrubbing = false

    /** Auto-scroll keeps the paragraph being played in view until the reader scrolls away. */
    private var followPlayback = true

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val playbackListener = object : Playback.Listener {
        override fun onPlaybackChanged() {
            maybeLoadItem()
            syncPlayerUi()
        }
    }

    private val playerPrefs get() = getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)

    /** The speed the reader last chose, kept across recordings. */
    private var preferredSpeed: Float
        get() = playerPrefs.getFloat(PREF_SPEED, 1f).takeIf { s -> SPEEDS.any { it == s } } ?: 1f
        set(value) = playerPrefs.edit().putFloat(PREF_SPEED, value).apply()

    private fun setupAudioPlayerControls() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.rewindButton.setOnClickListener { seekBy(-SKIP_BACK_MS) }
        binding.forwardButton.setOnClickListener { seekBy(SKIP_FORWARD_MS) }
        binding.progressSlider.setLabelFormatter { formatClock(it.toLong()) }
        binding.progressSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.currentTimeLabel.text = formatClock(value.toLong())
        }
        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                scrubbing = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                scrubbing = false
                seekPlayerToMs(slider.value.toLong())
            }
        })
        binding.speedChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val speed = speedForChip(checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener)
            preferredSpeed = speed
            if (playbackIsOurs) playback?.setSpeed(speed)
        }
        syncSpeedChips(preferredSpeed)
        binding.returnToPlayback.setOnClickListener { resumeFollow() }
    }

    private fun speedForChip(chipId: Int): Float = when (chipId) {
        R.id.speed1_5x -> 1.5f
        R.id.speed2x -> 2f
        else -> 1f
    }

    private fun chipForSpeed(speed: Float): Int = when {
        speed >= 2f -> R.id.speed2x
        speed >= 1.5f -> R.id.speed1_5x
        else -> R.id.speed1x
    }

    private fun syncSpeedChips(speed: Float) {
        val id = chipForSpeed(speed)
        if (binding.speedChips.checkedChipId != id) binding.speedChips.check(id)
    }

    /**
     * Local audio when the phone has it (works offline, no auth needed), else the server stream
     * once the server copy is known, else no player.
     */
    private fun bindAudio(file: RecordingFile?, rec: ServerRecording?) {
        val model = currentModel
        val path = file?.localPath
        val subtitle = model?.let { formatMetaDate(it.recordedAtMillis) }
        val item = when {
            path != null && File(path).exists() -> {
                // Self-heal legacy files exported with the SDK's corrupt OpusTags header
                if (path.endsWith(".opus", ignoreCase = true)) org.plaudbridge.app.common.OpusRepair.repairIfNeeded(path)
                Playback.Item(path, android.net.Uri.fromFile(File(path)), model?.title ?: "", subtitle, file?.id, rec?.id ?: serverRecordingId)
            }
            rec != null -> try {
                val url = ApiClient.recordingAudioUrl(rec.id)
                Playback.Item(url, android.net.Uri.parse(url), model?.title ?: "", subtitle, file?.id, rec.id)
            } catch (e: IllegalStateException) {
                null
            }
            else -> null
        }
        val previous = boundItem
        boundItem = item
        // The same recording from a different source (its phone copy was just removed, so the
        // server stream takes over): a player still on the old source must not keep playing it
        // behind a card that says otherwise. Hand it the new source, or stop it when there is none.
        val p = playback
        if (p != null && previous != null && previous.mediaId != item?.mediaId && p.currentMediaId == previous.mediaId) {
            if (item != null) p.setItem(item) else p.pause()
        }
        if (item == null) {
            binding.audioPlayer.visibility = View.GONE
            stopProgressTicks()
            updateReturnChip()
            return
        }
        binding.audioPlayer.visibility = View.VISIBLE
        maybeLoadItem()
        // A rename (or a title that arrived after the first bind) reaches the notification too.
        if (p != null && p.isConnected && p.currentMediaId == item.mediaId) p.setItem(item)
        syncPlayerUi()
    }

    /**
     * Give the player this recording when it holds nothing yet, so the duration is known before
     * the first tap. When it is busy with ANOTHER recording (left playing from a previous screen)
     * that one keeps going until the reader presses play here, see [ensureItemLoaded].
     */
    private fun maybeLoadItem() {
        val p = playback ?: return
        val item = boundItem ?: return
        if (!p.isConnected) return
        if (p.currentMediaId == null) p.setItem(item)
    }

    /** Before play or seek: this recording must be the one in the player. */
    private fun ensureItemLoaded() {
        val p = playback ?: return
        val item = boundItem ?: return
        if (p.currentMediaId != item.mediaId || p.hasError) p.setItem(item)
    }

    /** The player holds this screen's recording (rather than nothing, or another recording). */
    private val playbackIsOurs: Boolean
        get() {
            val id = boundItem?.mediaId ?: return false
            return playback?.currentMediaId == id
        }

    /** The length to show: the player's once it knows, else what the server or the phone recorded. */
    private fun knownDurationMs(): Long {
        val p = playback
        if (p != null && playbackIsOurs && p.durationMs > 0) return p.durationMs
        return (currentModel?.durationSeconds ?: 0L) * 1000L
    }

    /** Everything the player card shows, from the player's state. */
    private fun syncPlayerUi() {
        val p = playback
        val item = boundItem ?: return
        if (p != null && p.hasError && p.currentMediaId == item.mediaId) {
            // The player refused this recording: no controls that cannot work.
            binding.audioPlayer.visibility = View.GONE
            stopProgressTicks()
            updateReturnChip()
            return
        }
        binding.audioPlayer.visibility = View.VISIBLE
        val ours = playbackIsOurs
        val playing = ours && p?.isPlaying == true
        val position = if (ours) p?.positionMs ?: 0L else 0L
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        binding.totalTimeLabel.text = formatClock(knownDurationMs())
        if (!scrubbing) binding.currentTimeLabel.text = formatClock(position)
        updateSlider(position, knownDurationMs())
        syncSpeedChips(if (ours && p != null) p.speed else preferredSpeed)
        updateNowPlaying(position, playing)
        if (playing && inForeground) startProgressTicks() else stopProgressTicks()
        updateReturnChip()
    }

    private fun updateSlider(positionMs: Long, durationMs: Long) {
        val slider = binding.progressSlider
        val to = if (durationMs > 0) durationMs.toFloat() else 1f
        if (slider.valueTo != to) {
            if (slider.value > to) slider.value = 0f
            slider.valueTo = to
        }
        if (!scrubbing) slider.value = positionMs.toFloat().coerceIn(0f, to)
    }

    private fun togglePlayPause() {
        val p = playback ?: return
        if (boundItem == null) return
        if (playbackIsOurs && p.isPlaying) {
            p.pause()
        } else {
            ensureItemLoaded()
            p.setSpeed(preferredSpeed)
            p.play()
            followPlayback = true
        }
        syncPlayerUi()
    }

    private fun seekBy(deltaMs: Long) {
        val p = playback ?: return
        if (boundItem == null) return
        val current = if (playbackIsOurs) p.positionMs else 0L
        seekPlayerToMs(current + deltaMs)
    }

    /** Jump playback to [seconds] from the start; no player, no action. */
    private fun seekPlayerTo(seconds: Double) = seekPlayerToMs((seconds * 1000).toLong())

    private fun seekPlayerToMs(ms: Long) {
        val p = playback ?: return
        if (boundItem == null) return
        val duration = knownDurationMs()
        val target = ms.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        ensureItemLoaded()
        p.seekTo(target)
        binding.currentTimeLabel.text = formatClock(target)
        updateSlider(target, duration)
        updateNowPlaying(target, p.isPlaying)
    }

    private fun startProgressTicks() {
        if (progressRunnable != null) return
        val tick = object : Runnable {
            override fun run() {
                val p = playback
                if (p == null || !playbackIsOurs || !p.isPlaying) {
                    progressRunnable = null
                    syncPlayerUi()
                    return
                }
                val position = p.positionMs
                if (!scrubbing) {
                    binding.currentTimeLabel.text = formatClock(position)
                    updateSlider(position, knownDurationMs())
                }
                updateNowPlaying(position, true)
                progressHandler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
        progressRunnable = tick
        progressHandler.post(tick)
    }

    private fun stopProgressTicks() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    /**
     * "m:ss", or "h:mm:ss" throughout once the recording is an hour or longer, so the two clock
     * labels keep one width while the numbers move.
     */
    private fun formatClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (knownDurationMs() >= 3_600_000L) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else TranscriptMarkdown.formatTimestamp(s.toDouble())
    }

    // MARK: - Now playing

    /**
     * Tint the paragraph the playhead is in and, while following, keep it in view. Nothing is
     * tinted before playback has started (position 0, not playing).
     */
    private fun updateNowPlaying(positionMs: Long, playing: Boolean) {
        val index = if (playbackIsOurs && (playing || positionMs > 0)) TranscriptRows.nowPlayingIndex(transcriptParagraphs, positionMs / 1000.0) else -1
        if (index == adapter.nowPlayingIndex) return
        adapter.setNowPlaying(index)
        if (playing && followPlayback && index >= 0) ensureParagraphVisible(index)
    }

    /** Scroll only when the paragraph is not already fully on screen; land it in the upper third. */
    private fun ensureParagraphVisible(index: Int) {
        val position = adapter.positionOf(index)
        val first = layoutManager.findFirstCompletelyVisibleItemPosition()
        val last = layoutManager.findLastCompletelyVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && position in first..last) return
        binding.appBar.setExpanded(false, animationsEnabled())
        val viewport = binding.transcriptList.height - binding.transcriptList.paddingBottom
        layoutManager.scrollToPositionWithOffset(position, (viewport / 3).coerceAtLeast(0))
    }

    /** The reader took over the scrolling: stop following until asked to return. */
    private fun onUserScrollGesture() {
        if (!followPlayback) return
        followPlayback = false
        updateReturnChip()
    }

    private fun resumeFollow() {
        followPlayback = true
        adapter.nowPlayingIndex.takeIf { it >= 0 }?.let { ensureParagraphVisible(it) }
        updateReturnChip()
    }

    /** The chip offers the way back only while something is playing and the reader has wandered. */
    private fun updateReturnChip() {
        val show = playbackIsOurs && playback?.isPlaying == true && !followPlayback && transcriptParagraphs.isNotEmpty()
        binding.returnToPlayback.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopTranscriptionPolling()
        savePosition()
        // Playback goes on in the service; only the screen's clock stops ticking.
        stopProgressTicks()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressTicks()
        playback?.release()
        playback = null
        routingHandler.removeCallbacksAndMessages(null)
        flashHandler.removeCallbacksAndMessages(null)
        transcriptParseJob?.cancel()
        // Reads in flight die with the scope; whoever waited on them must not wait forever
        // (a rerun guard held for reconciliation would otherwise pin Run automations off).
        drainRoutingSettledCallbacks()
        rerunReconciliations.toList().forEach { it.release() }
        // Only our own entry: after a rotation the replacement may already be registered.
        serverRecordingId?.let { if (liveScreens[it] === this) liveScreens.remove(it) }
    }

    // MARK: - More sheet

    private fun showMoreSheet() {
        if (currentModel == null || currentItem() == null) return
        if (supportFragmentManager.findFragmentByTag(MoreActionsSheet.TAG) != null) return
        MoreActionsSheet().show(supportFragmentManager, MoreActionsSheet.TAG)
    }

    override fun moreSheetTitle(): String? = currentModel?.title

    override fun buildMoreMenu(): Menu? {
        if (currentModel == null || currentItem() == null) return null
        val popup = PopupMenu(this, binding.moreButton)
        popup.menuInflater.inflate(R.menu.menu_file_detail, popup.menu)
        applyMenuVisibility(popup.menu)
        return popup.menu
    }

    /**
     * Hide what does not apply: phone actions need audio on the phone (an entry that is still to
     * be downloaded, or whose audio the user removed, has nothing to export or remove), server
     * actions need a server copy.
     */
    @VisibleForTesting
    internal fun applyMenuVisibility(menu: Menu) {
        currentModel ?: return
        val hasAudio = currentFile?.isSynced == true
        val hasServer = serverRecordingId != null
        menu.findItem(R.id.action_export)?.isVisible = hasAudio
        menu.findItem(R.id.action_retranscribe)?.isVisible = hasServer
        // The router needs a transcript to read (see serverTranscriptReady; a recording with no
        // speech has none, whatever the server's transcript endpoint answered) and a server that
        // has the automations endpoints at all.
        // Disabled until the history has loaded: reconciling an ambiguous rerun compares against
        // the latest run before the tap, which needs that history to be known.
        val canRunAutomations = hasServer && serverTranscriptReady && !routingUnsupported && !isNoSpeech
        val runAutomationsEnabled = serverRecordingId !in rerunsInFlight && routingRuns != null
        for (id in intArrayOf(R.id.action_run_automations, R.id.action_run_automations_with_instructions)) {
            menu.findItem(id)?.apply {
                isVisible = canRunAutomations
                isEnabled = runAutomationsEnabled
            }
        }
        menu.findItem(R.id.action_remove_from_phone)?.isVisible = currentItem()?.canRemoveFromPhone == true
        menu.findItem(R.id.action_delete)?.isVisible = true
    }

    /** Menu dispatch, separate from the sheet so tests can drive it. */
    @VisibleForTesting
    override fun onMenuAction(itemId: Int): Boolean {
        currentModel ?: return false
        val item = currentItem() ?: return false
        when (itemId) {
            R.id.action_export -> currentFile?.let { exportAudio(it) }
            R.id.action_rename -> showRenameDialog(item)
            R.id.action_retranscribe -> serverRecordingId?.let { retranscribeOnServer(it) }
            R.id.action_run_automations -> serverRecordingId?.let { runAutomations(it) }
            R.id.action_run_automations_with_instructions -> serverRecordingId?.let { showRunWithInstructionsDialog(it) }
            R.id.action_remove_from_phone -> confirmRemoveFromPhone(item)
            R.id.action_delete -> confirmDelete(item)
            else -> return false
        }
        return true
    }

    private fun retranscribeOnServer(serverId: String) {
        header.generateButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = RecordingActions.retranscribe(serverId, serverSource)) {
                is ApiClient.ActionResult.Ok -> {
                    snack(getString(R.string.retranscribe_queued))
                    transcriptWasPending = true
                    loadServerRecording(serverId)
                }
                else -> {
                    header.generateButton.isEnabled = true
                    showTranscriptAlert(actionErrorMessage(result))
                }
            }
        }
    }

    /** A one-line text dialog (rename the recording, rename a speaker): outlined field, Save / Cancel. */
    private fun showTextDialog(
        title: String, hint: String, initial: String, emptyError: String,
        onTextChanged: ((String, TextInputLayout) -> Unit)? = null, onSave: (String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_text_field, null)
        val layout = view.findViewById<TextInputLayout>(R.id.textFieldLayout)
        val field = view.findViewById<EditText>(R.id.textField)
        layout.hint = hint
        field.setText(initial)
        field.selectAll()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.detail_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val text = field.text.toString().trim()
            if (text.isEmpty()) {
                layout.error = emptyError
            } else {
                dialog.dismiss()
                onSave(text)
            }
        }
        field.doAfterTextChanged {
            layout.error = null
            onTextChanged?.invoke(it.toString().trim(), layout)
        }
        field.requestFocus()
    }

    private fun showRenameDialog(item: RecordingItem) {
        showTextDialog(
            title = getString(R.string.detail_rename_title),
            hint = getString(R.string.detail_rename_hint),
            initial = item.title,
            emptyError = getString(R.string.detail_title_required)
        ) { newName -> if (newName != item.title) rename(item, newName) }
    }

    /** Server PATCH when the recording is there (mirrored into the phone copy), else a local rename. */
    private fun rename(item: RecordingItem, newName: String) {
        lifecycleScope.launch {
            when (val result = RecordingActions.rename(item, newName, serverSource, syncManager)) {
                is ApiClient.ActionResult.Ok -> {
                    currentFile?.let { currentFile = findFile(it.id) ?: it }
                    val serverId = serverRecordingId
                    if (serverId != null && RecordingStore.isServerConfigured) loadServerRecording(serverId) else render()
                }
                else -> showTranscriptAlert(actionErrorMessage(result))
            }
        }
    }

    // MARK: - Speaker rename

    /**
     * Tap on a speaker label: rename that speaker throughout the transcript. The server rewrites
     * the document and answers with it (contract §2); a name another speaker already has merges
     * the two, which the field's helper line says before Save.
     */
    private fun showRenameSpeakerDialog(speaker: String) {
        val serverId = serverRecordingId ?: return
        val others = transcriptSpeakers.filter { it != speaker }
        showTextDialog(
            title = getString(R.string.detail_rename_speaker_title_fmt, speaker),
            hint = getString(R.string.detail_rename_speaker_hint),
            initial = speaker,
            emptyError = getString(R.string.detail_name_required),
            onTextChanged = { text, layout ->
                layout.helperText = others.firstOrNull { it.equals(text, ignoreCase = true) }
                    ?.let { getString(R.string.detail_rename_speaker_merge_fmt, it) }
            }
        ) { newName -> if (newName != speaker) renameSpeaker(serverId, speaker, newName) }
    }

    private fun renameSpeaker(serverId: String, from: String, to: String) {
        lifecycleScope.launch {
            when (val result = serverSource.renameSpeakers(serverId, mapOf(from to to))) {
                is ApiClient.TranscriptResult.Ready -> {
                    storeServerTranscript(result.rawJson)
                    render()
                    snack(getString(R.string.detail_speaker_renamed))
                }
                // The endpoint is missing: a server from before speaker renames (or the
                // recording is gone, which the next refresh will say in its own words).
                is ApiClient.TranscriptResult.NotFound -> snack(getString(R.string.detail_server_needs_update))
                is ApiClient.TranscriptResult.AuthError -> snack(getString(R.string.transcript_auth_error))
                is ApiClient.TranscriptResult.Error -> snack(result.detail ?: getString(R.string.detail_request_failed))
                is ApiClient.TranscriptResult.Pending -> snack(getString(R.string.detail_request_failed))
            }
        }
    }

    private fun confirmRemoveFromPhone(item: RecordingItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_from_phone)
            .setMessage(getString(R.string.remove_from_phone_confirm_fmt, RecordingsAdapter.rowTitle(this, item)))
            .setPositiveButton(R.string.remove_from_phone) { _, _ -> removeFromPhone(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Drop the phone's audio. The index entry stays (flagged, see RecordingActions) so the page
     * re-reads it: the audio player and the phone-only menu items go, the server content stays.
     * Without a server copy on screen there is nothing left to show.
     */
    private fun removeFromPhone(item: RecordingItem) {
        if (!RecordingActions.removeFromPhone(item, syncManager)) return
        currentFile = currentFile?.let { findFile(it.id) }
        if (serverRecording != null) {
            render()
            snack(getString(R.string.removed_from_phone))
        } else {
            Toast.makeText(this, R.string.removed_from_phone, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete(item: RecordingItem) {
        val shownTitle = RecordingsAdapter.rowTitle(this, item)
        val message = if (item.serverId != null) {
            getString(R.string.delete_everywhere_confirm_fmt, shownTitle)
        } else {
            getString(R.string.delete_phone_only_confirm_fmt, shownTitle)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ -> delete(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(themeColor(MaterialR.attr.colorError))
    }

    /** Server first (when it has the recording), then the phone copy; see [RecordingActions.delete]. */
    private fun delete(item: RecordingItem) {
        lifecycleScope.launch {
            when (val result = RecordingActions.delete(item, serverSource, syncManager)) {
                is ApiClient.ActionResult.Ok -> {
                    // A toast, not a snackbar: the screen closes right after and the list
                    // behind it is where the confirmation must be readable.
                    Toast.makeText(this@FileDetailActivity, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
                    if (playbackIsOurs) playback?.pause()
                    finish() // the lists refresh on resume
                }
                else -> showTranscriptAlert(actionErrorMessage(result))
            }
        }
    }

    private fun exportAudio(file: RecordingFile) {
        syncManager.exportAudio(file) { result ->
            runOnUiThread {
                result.onSuccess { outputFile ->
                    val uri = FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        outputFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.detail_export_audio)))
                }
                result.onFailure {
                    org.plaudbridge.app.common.AppLog.w("FileDetail", "audio export failed", it)
                    showAlert(getString(R.string.export_failed_title), getString(R.string.detail_export_failed_body))
                }
            }
        }
    }

    /**
     * Copy [text] and confirm. Android 13+ shows its own clipboard chip for every copy, so a
     * confirmation of our own would double up there; older versions get a snackbar.
     */
    private fun copyToClipboard(label: String, text: String, confirmation: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snack(confirmation)
    }

    private fun copySummary(model: DetailModel) {
        val summary = model.summary ?: return
        copyToClipboard("Summary", summary, getString(R.string.detail_summary_copied))
    }

    /**
     * Copies the transcript as speaker paragraphs (no timestamps, no stars): the same paragraphs
     * as on screen, in the server's `paragraphs_plain` layout.
     */
    private fun copyTranscript() {
        val text = transcriptCopyText ?: transcriptPlainText ?: return
        copyToClipboard("Transcript", text, getString(R.string.transcript_copied))
    }

    /**
     * Clipboard text for a transcript document, in the server's clipboard layout ("Speaker 1:
     * text" paragraphs, one blank line between, nothing else): the document's own `paragraphs`
     * when it has them; for older documents the server's flat `text` (one "Speaker N: ..." line
     * per turn), else the paragraphs derived from the segments. Null when none is available.
     */
    private fun copyTextFor(json: String): String? {
        val paragraphs = exportParagraphs(json)
        if (paragraphs != null) return TranscriptMarkdown.plainParagraphs(paragraphs)
        return transcriptExportFields(json).first?.let { TranscriptMarkdown.plainParagraphs(it) }
    }

    /**
     * The paragraphs Copy and Export render, or null to fall back to the flat `text`: the server's
     * own `paragraphs` when the document carries them (the server exports exactly these), else a
     * locally derived grouping only for documents that have segments but no flat text either.
     */
    private fun exportParagraphs(json: String): List<TranscriptParagraph>? =
        TranscriptParagraph.fromDocument(json)?.takeIf { it.isNotEmpty() }
            ?: if (transcriptExportFields(json).first == null) TranscriptParagraph.derive(json)?.takeIf { it.isNotEmpty() } else null

    // MARK: - Markdown export

    /**
     * Export the transcript as markdown via the share sheet. The body is the document's reader
     * paragraphs (bold speaker, "★ " on bookmarked paragraphs, no timestamps) when the server
     * wrote them, so the file matches the server's own export byte for byte; older documents
     * render their flat `text` field as bold-speaker paragraphs the way the server does for them.
     */
    private fun exportMarkdown(model: DetailModel) {
        val json = model.transcriptJSON ?: return
        val (text, summary) = transcriptExportFields(json)
        val paragraphs = exportParagraphs(json)
        val body = text ?: transcriptPlainText ?: return
        val markdown = TranscriptMarkdown.build(
            title = model.title,
            recordedAtMillis = model.recordedAtMillis,
            durationSeconds = model.durationSeconds,
            transcript = body,
            summary = summary ?: model.summary,
            highlights = TranscriptHighlight.parse(json),
            paragraphs = paragraphs
        )
        try {
            TranscriptShare.share(this, ExportFileName.sanitize(model.title), model.title, markdown)
        } catch (e: Exception) {
            org.plaudbridge.app.common.AppLog.w("FileDetail", "markdown export failed", e)
            showAlert(getString(R.string.export_failed_title), getString(R.string.detail_export_failed_body))
        }
    }

    /** (`text`, `summary`) from the server transcript object; both null for non-object shapes. */
    private fun transcriptExportFields(json: String): Pair<String?, String?> = try {
        val obj = org.json.JSONObject(json)
        Pair(
            obj.optString("text").takeIf { it.isNotBlank() },
            obj.optString("summary").takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        Pair(null, null)
    }
}
