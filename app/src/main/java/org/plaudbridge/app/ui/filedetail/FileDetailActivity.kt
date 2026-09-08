package org.plaudbridge.app.ui.filedetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.R
import org.plaudbridge.app.databinding.ActivityFileDetailBinding
import org.plaudbridge.app.export.ExportFileName
import org.plaudbridge.app.export.TranscriptHighlight
import org.plaudbridge.app.export.TranscriptMarkdown
import org.plaudbridge.app.export.TranscriptShare
import org.plaudbridge.app.managers.TitleSyncManager
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.RoutingRun
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.common.MarkdownRenderer
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
 * Header (name/date/duration/status) + Summary + Highlights + Automations + Transcript + More Menu
 *
 * One screen for one recording, wherever it lives. The intent carries whichever ids are known:
 *  - `file_id`: the phone's [RecordingFile] (offline audio, cached transcript, upload state);
 *  - `server_recording_id`: the bridge server's copy (fresh title, status, transcript).
 * A phone copy renders instantly from its cache; a server id then refreshes title, status and
 * transcript from the server. Both sides are combined through [RecordingItem], the same merge
 * the Recordings tab draws its rows from, so the header cannot disagree with the list, and
 * rendered through [DetailModel], the handful of fields the content blocks actually use.
 *
 * The only thing written back into RecordingStore is a transcript fetched for a recording the
 * phone already indexes, which is exactly what TitleSyncManager stores in the background.
 */
class FileDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileDetailBinding
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

    // Audio player (media3 ExoPlayer)
    private var preparedKey: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

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
    }

    private object ApiServerDetailSource : ServerDetailSource, ServerRecordingActions by ApiServerRecordingActions {
        override suspend fun recording(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRecording(id) }
        override suspend fun transcript(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchTranscript(id) }
        override suspend fun routing(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRouting(id) }
        override suspend fun rerunRouting(id: String, idempotencyKey: String, instructions: String?) =
            withContext(Dispatchers.IO) { ApiClient.rerunRouting(id, idempotencyKey, instructions) }
        override suspend fun retryDelivery(deliveryId: String) = withContext(Dispatchers.IO) { ApiClient.retryDelivery(deliveryId) }
    }

    companion object {
        /** Intent extra: the phone's RecordingFile id. */
        const val EXTRA_FILE_ID = "file_id"

        /** Intent extra: the bridge server's recording id. */
        const val EXTRA_SERVER_RECORDING_ID = "server_recording_id"

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var serverSource: ServerDetailSource = ApiServerDetailSource

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

        /** How often the recording is re-read while the server is still transcribing it. */
        @VisibleForTesting
        const val TRANSCRIPTION_POLL_INTERVAL_MS = 5_000L

        /** A route's reason is a paragraph; show its opening lines and unfold on tap. */
        private const val REASON_COLLAPSED_LINES = 3

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

        /** An answer that leaves open whether the server ran the router anyway (timeout, 5xx, lost response). */
        private fun isAmbiguousRerunFailure(result: ApiClient.ActionResult): Boolean =
            result is ApiClient.ActionResult.Error && result.message != "HTTP 409"

        /** Tests share one process: clear the process-wide rerun state between them. */
        @VisibleForTesting
        internal fun resetProcessStateForTests() {
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

        binding.backButton.setOnClickListener { finish() }
        binding.moreButton.setOnClickListener { showMoreMenu(it) }
        binding.copyTranscriptButton.setOnClickListener { copyTranscript() }
        binding.exportMarkdownButton.setOnClickListener { currentModel?.let { m -> exportMarkdown(m) } }
        binding.automationsShowEarlier.setOnClickListener {
            showEarlierRuns = true
            bindAutomations()
        }
        setupAudioPlayerControls()

        intent.getStringExtra(EXTRA_FILE_ID)?.let { currentFile = findFile(it) }
        serverRecordingId = intent.getStringExtra(EXTRA_SERVER_RECORDING_ID)
            ?: currentFile?.serverId?.takeIf { it.isNotBlank() }
        if (currentFile == null && serverRecordingId == null) {
            finish()
            return
        }

        val file = currentFile
        if (file != null) {
            backfillDuration(file)
            render() // instant: cached title, transcript and local audio
        } else {
            binding.fileNameLabel.text = ""
            binding.fileDateLabel.text = getString(R.string.checking_transcript)
        }

        val serverId = serverRecordingId
        when {
            // Also loads the Automations section once the recording (and so its age) is known.
            serverId != null && RecordingStore.isServerConfigured -> loadServerRecording(serverId)
            // Uploaded before the phone learned the server id (legacy index): resolve it by
            // (device, session) and fetch the transcript the old way. Also when a transcript is
            // already cached: the id is what unlocks the server-side content (automations, the
            // server menu actions), and once stored the next open takes the server path above.
            file != null && file.uploaded -> fetchTranscriptFromServer(file, userInitiated = false)
        }
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
                }
            }
        }
    }

    /** On-screen transcript ("Speaker N · HH:MM:SS" blocks); null when nothing parseable. */
    private var transcriptPlainText: String? = null

    /** What Copy transcript puts on the clipboard: speaker paragraphs, no timestamps. */
    private var transcriptCopyText: String? = null

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
        if (transcriptPlainText == null) bindEmptyState(item)
        bindAudio(file, rec)
        bindAutomations()
        syncTranscriptionPolling()
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
        bindMetaLine(model.recordedAtMillis, model.durationSeconds)

        // Summary block (flat, only when a summary exists)
        val hasSummary = !model.summary.isNullOrBlank()
        binding.summaryHeader.visibility = if (hasSummary) View.VISIBLE else View.GONE
        binding.summaryText.visibility = if (hasSummary) View.VISIBLE else View.GONE
        if (hasSummary) MarkdownRenderer.setMarkdown(binding.summaryText, MarkdownRenderer.withoutSummaryHeading(model.summary!!))

        // Highlights: the server's transcript-around-each-button-press rows, above the transcript
        bindHighlights(model.transcriptJSON?.let { TranscriptHighlight.parse(it) } ?: emptyList())

        // Transcript: parsed segments from the bridge server, or (caller-defined) empty state
        transcriptPlainText = model.transcriptJSON?.let { parseTranscript(it) }
        transcriptCopyText = model.transcriptJSON?.let { copyTextFor(it) } ?: transcriptPlainText
        binding.transcriptActions.visibility = if (transcriptPlainText != null) View.VISIBLE else View.GONE
        if (transcriptPlainText != null) {
            binding.transcriptText.text = transcriptPlainText
            binding.transcriptText.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        } else {
            binding.transcriptText.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }
    }

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
                setStatusBadge(RecordingsAdapter.transcribingText(this, item.server), R.color.orange, R.drawable.bg_status_pending)
            RecordingItem.Status.FAILED ->
                setStatusBadge(getString(R.string.status_failed), R.color.red, R.drawable.bg_status_pending)
            else -> binding.statusBadge.visibility = View.GONE
        }
    }

    private fun setStatusBadge(text: String, colorRes: Int, backgroundRes: Int) {
        binding.statusBadge.visibility = View.VISIBLE
        binding.statusBadge.text = text
        binding.statusBadge.setTextColor(ContextCompat.getColor(this, colorRes))
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
                // A finished recording with nothing in it: say so, and offer no transcript
                // button (there is nothing to check for; Re-transcribe stays in the menu).
                if (rec?.isTranscribing != true && isNoSpeech) {
                    showEmptyState(getString(R.string.no_speech_title), getString(R.string.no_speech_detected))
                    return
                }
                // While the server works, the title carries the stage (the badge's wording).
                val title = if (rec?.isTranscribing == true) RecordingsAdapter.transcribingText(this, rec)
                    else getString(R.string.transcript)
                val subtitle = when (rec?.status) {
                    ServerRecording.STATUS_FAILED ->
                        rec.error?.takeIf { it.isNotBlank() } ?: getString(R.string.transcription_failed)
                    ServerRecording.STATUS_STORED -> getString(R.string.transcript_not_started)
                    else -> getString(R.string.transcription_pending)
                }
                showEmptyState(title, subtitle, getString(R.string.check_transcript)) {
                    checkForTranscript()
                }
            }
            file != null && file.isSynced ->
                showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_upload))
            else -> showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_sync))
        }
    }

    /** Centered empty state under the Transcript tab; [buttonText] null hides the button. */
    private fun showEmptyState(title: String, subtitle: String, buttonText: String? = null, onButton: (() -> Unit)? = null) {
        binding.transcriptText.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyTitle.text = title
        binding.emptySubtitle.text = subtitle
        if (buttonText != null) {
            binding.generateButton.visibility = View.VISIBLE
            binding.generateButton.text = buttonText
            binding.generateButton.isEnabled = true
            binding.generateButton.setOnClickListener { onButton?.invoke() }
        } else {
            binding.generateButton.visibility = View.GONE
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
        if (transcriptPlainText == null && !rec.noSpeech) binding.emptySubtitle.text = getString(R.string.checking_transcript)
        loadServerTranscript(rec)
    }

    private fun loadServerTranscript(rec: ServerRecording) {
        lifecycleScope.launch {
            when (val outcome = serverSource.transcript(rec.id)) {
                is ApiClient.TranscriptResult.Ready -> {
                    val arrived = transcriptArrived()
                    storeServerTranscript(outcome.rawJson)
                    render()
                    // A transcript that just landed means the server's router is about to run
                    // (detached, after transcription); re-read the automations until its run
                    // shows up and keep polling for the agents. Not for a recording with no
                    // speech in it: there is nothing to route, so no run is coming.
                    if (isNoSpeech) clearRoutingWait()
                    if (arrived) refreshRouting(awaitRun = !isNoSpeech)
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
        TitleSyncManager.storeTranscript(file.id, rawJson)
        currentFile = findFile(file.id) ?: file
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
        binding.generateButton.isEnabled = false
        binding.emptySubtitle.text = getString(R.string.checking_transcript)
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
        binding.generateButton.isEnabled = true
        if (currentItem() == null) {
            binding.fileDateLabel.text = ""
            showEmptyState(getString(R.string.transcript), message, getString(R.string.check_transcript)) {
                checkForTranscript()
            }
        } else if (transcriptPlainText == null) {
            binding.emptySubtitle.text = message
        }
    }

    private fun actionErrorMessage(result: ApiClient.ActionResult): String = when (result) {
        is ApiClient.ActionResult.Ok -> ""
        is ApiClient.ActionResult.NotFound -> getString(R.string.recording_not_on_server)
        is ApiClient.ActionResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.ActionResult.Error -> getString(R.string.server_request_failed_fmt, result.message)
    }

    // MARK: - Highlights

    /**
     * One row per highlight: "★ m:ss" in the accent color, then the text (or the server's
     * no-speech placeholder). Rows are plain TextViews built here rather than a RecyclerView
     * because the list is short (one per button press) and lives inside the page's ScrollView.
     * Tapping a row seeks the player to the highlight's start when a local audio file exists;
     * without a player the row is display only.
     */
    private fun bindHighlights(highlights: List<TranscriptHighlight>) {
        binding.highlightsList.removeAllViews()
        val visible = highlights.isNotEmpty()
        binding.highlightsHeader.visibility = if (visible) View.VISIBLE else View.GONE
        binding.highlightsList.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val accent = ContextCompat.getColor(this, R.color.highlight_accent)
        val density = resources.displayMetrics.density
        for (h in highlights) {
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
                setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.dark_gray))
                textSize = 14f
                typeface = android.graphics.Typeface.SANS_SERIF
                setLineSpacing(4 * density, 1f)
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                tag = h
                contentDescription = "Highlight at ${TranscriptMarkdown.formatTimestamp(h.at)}"
                setOnClickListener { seekToHighlight(h) }
            }
            binding.highlightsList.addView(row)
        }
    }

    /** Jump playback to the highlight's first transcript segment; no player, no action. */
    private fun seekToHighlight(h: TranscriptHighlight) {
        val p = exoPlayer ?: return
        val duration = p.duration.coerceAtLeast(0)
        val target = (h.start * 1000).toLong().coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        p.seekTo(target)
        binding.currentTimeLabel.text = formatClock(target)
        binding.progressSlider.progress = if (duration > 0) (target * 1000 / duration).toInt() else 0
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
        binding.automationsList.removeAllViews()
        val transcribed = serverTranscriptReady || serverRecording?.isDone == true
        // A recording with no speech had nothing to route: "No automations ran" would be noise.
        val showEmpty = runs != null && runs.isEmpty() && transcribed && !routingAwaitingRun && !isNoSpeech
        val showRuns = runs != null && runs.isNotEmpty()
        binding.automationsHeader.visibility = if (showEmpty || showRuns) View.VISIBLE else View.GONE
        binding.automationsEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.automationsList.visibility = if (showRuns) View.VISIBLE else View.GONE
        if (runs == null || !showRuns) {
            binding.automationsShowEarlier.visibility = View.GONE
            return
        }
        val shown = if (showEarlierRuns) runs else runs.take(1)
        shown.forEachIndexed { index, run -> binding.automationsList.addView(buildRunBlock(run, isLatest = index == 0)) }
        binding.automationsShowEarlier.visibility = if (runs.size > 1 && !showEarlierRuns) View.VISIBLE else View.GONE
    }

    /**
     * One router run: the matched routes with their reasons and delivery outcomes, or the reason
     * nothing happened (router error, or no route matched). Plain views built here rather than a
     * RecyclerView for the same reason as the highlights: a handful of rows inside a ScrollView.
     */
    private fun buildRunBlock(run: RoutingRun, isLatest: Boolean): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val block = android.widget.LinearLayout(this).apply {
            id = R.id.automation_run_block
            orientation = android.widget.LinearLayout.VERTICAL
            tag = run
        }
        if (!isLatest) {
            block.addView(mutedText(getString(R.string.automations_earlier_run_fmt, run.createdAt?.let { formatMetaDate(it) } ?: ""))
                .apply { id = R.id.automation_run_header; setPadding(0, dp(12), 0, 0) })
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
                setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.red))
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
        val covered = mutableSetOf<String>()
        for (route in run.routes) {
            covered += route.name
            block.addView(buildRouteRow(route.name, route.reason))
            run.deliveriesFor(route.name).forEach { block.addView(buildDeliveryRow(it)) }
        }
        // A delivery whose route the decision does not list (should not happen; shown so nothing
        // the server did is invisible).
        run.deliveries.filter { it.routeName !in covered }.groupBy { it.routeName }.forEach { (name, list) ->
            block.addView(buildRouteRow(name.ifBlank { "?" }, null))
            list.forEach { block.addView(buildDeliveryRow(it)) }
        }
        return block
    }

    /** Route name in the primary style, the router's reason muted below it; tap the reason to unfold it. */
    private fun buildRouteRow(name: String, reason: String?): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(android.widget.TextView(this@FileDetailActivity).apply {
                id = R.id.automation_route_name
                text = name
                setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.text_primary))
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            if (reason != null) {
                addView(mutedText(reason).apply {
                    id = R.id.automation_route_reason
                    maxLines = REASON_COLLAPSED_LINES
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                    setOnClickListener {
                        maxLines = if (maxLines == REASON_COLLAPSED_LINES) Int.MAX_VALUE else REASON_COLLAPSED_LINES
                    }
                })
            }
        }
    }

    /**
     * "[state pill] outcome · time" plus a Retry pill when it failed. The agent's own report
     * (result_status) speaks first; only without one does the hand-off status stand in.
     */
    private fun buildDeliveryRow(d: Delivery): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val (pillText, pillColor, pillBg, detail) = deliveryPresentation(d)
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.automation_delivery_row
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            setPadding(0, dp(6), 0, dp(2))
            tag = d
        }
        row.addView(android.widget.TextView(this).apply {
            id = R.id.automation_delivery_pill
            text = pillText
            setTextColor(ContextCompat.getColor(this@FileDetailActivity, pillColor))
            setBackgroundResource(pillBg)
            textSize = 11f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            includeFontPadding = false
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); marginEnd = dp(8) })
        val textColumn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@FileDetailActivity).apply {
                id = R.id.automation_delivery_text
                text = detail
                setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.dark_gray))
                textSize = 13f
                typeface = android.graphics.Typeface.SANS_SERIF
            })
            d.effectiveAt?.let { at ->
                addView(mutedText(formatMetaDate(at)).apply {
                    id = R.id.automation_delivery_time
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }
        row.addView(textColumn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (d.canRetry) {
            row.addView(android.widget.TextView(this).apply {
                id = R.id.automation_retry
                text = getString(R.string.retry)
                setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.text_primary))
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

    /** (pill label, pill text color, pill background, detail text) for one delivery. */
    private data class DeliveryPresentation(val pill: String, val color: Int, val background: Int, val detail: String)

    private fun deliveryPresentation(d: Delivery): DeliveryPresentation = when (d.resultStatus) {
        Delivery.RESULT_QUEUED -> DeliveryPresentation(
            getString(R.string.automation_state_working), R.color.orange, R.drawable.bg_status_pending,
            getString(R.string.automation_working_text)
        )
        Delivery.RESULT_DONE -> DeliveryPresentation(
            getString(R.string.automation_state_done), R.color.green, R.drawable.bg_status_synced,
            d.resultSummary ?: getString(R.string.automation_state_done)
        )
        Delivery.RESULT_FAILED -> DeliveryPresentation(
            getString(R.string.automation_state_failed), R.color.red, R.drawable.bg_status_pending,
            d.resultSummary ?: d.lastError ?: getString(R.string.automation_failed_text)
        )
        // The job was accepted but never reported back within the server's deadline: neither
        // good nor bad news, so the neutral pill; Retry is offered because the server allows it.
        Delivery.RESULT_UNKNOWN -> DeliveryPresentation(
            getString(R.string.automation_state_no_report), R.color.gray7, R.drawable.bg_status_neutral,
            getString(R.string.automation_no_report_text)
        )
        else -> when (d.status) {
            Delivery.STATUS_FAILED -> DeliveryPresentation(
                getString(R.string.automation_state_failed), R.color.red, R.drawable.bg_status_pending,
                d.lastError ?: getString(R.string.automation_failed_text)
            )
            Delivery.STATUS_PENDING -> DeliveryPresentation(
                getString(R.string.automation_state_pending), R.color.orange, R.drawable.bg_status_pending,
                getString(R.string.automation_pending_text)
            )
            // "ok" without a report: a webhook was handed to something that has not (or will
            // not) report back; a markdown or decision-only action ran to completion right there.
            else -> if (d.actionType == Delivery.ACTION_WEBHOOK) DeliveryPresentation(
                getString(R.string.automation_state_handed_off), R.color.gray7, R.drawable.bg_status_neutral,
                getString(R.string.automation_handed_off_text)
            ) else DeliveryPresentation(
                getString(R.string.automation_state_done), R.color.green, R.drawable.bg_status_synced,
                getString(R.string.automation_state_done)
            )
        }
    }

    private fun mutedText(text: CharSequence): android.widget.TextView = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@FileDetailActivity, R.color.text_secondary))
        textSize = 13f
        typeface = android.graphics.Typeface.SANS_SERIF
    }

    /** Ask the server to run the failed delivery again, then watch the section for the outcome. */
    private fun retryDelivery(d: Delivery) {
        lifecycleScope.launch {
            when (val result = serverSource.retryDelivery(d.id)) {
                is ApiClient.RetryResult.Ok -> {
                    Toast.makeText(this@FileDetailActivity, R.string.automation_retry_queued, Toast.LENGTH_SHORT).show()
                    refreshRouting()
                }
                // The delivery moved on without us (retried elsewhere, or it succeeded after
                // all): the refreshed section is the answer, no dialog needed.
                is ApiClient.RetryResult.Conflict -> refreshRouting()
                is ApiClient.RetryResult.NotFound -> showAlert(getString(R.string.automations), getString(R.string.recording_not_on_server))
                is ApiClient.RetryResult.AuthError -> showAlert(getString(R.string.automations), getString(R.string.transcript_auth_error))
                is ApiClient.RetryResult.Error -> {
                    showAlert(getString(R.string.automations), getString(R.string.server_request_failed_fmt, result.message))
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
            Toast.makeText(this, R.string.automations_retrying_earlier, Toast.LENGTH_LONG).show()
        }
        pendingReruns[serverId] = intent
        rerunScope.launch {
            val result = try {
                serverSource.rerunRouting(serverId, intent.key, intent.instructions)
            } catch (e: Throwable) {
                rerunsInFlight.remove(serverId)
                throw e
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
                Toast.makeText(this, R.string.automations_queued, Toast.LENGTH_SHORT).show()
                for (delay in RERUN_REFRESH_DELAYS_MS) routingHandler.postDelayed({ refreshRouting() }, delay)
            }
            is ApiClient.ActionResult.Error -> {
                // 409: no transcript to route yet (the menu hides the action then, but the
                // transcript can vanish under a re-transcribe between the two).
                showAlert(
                    getString(R.string.automations),
                    if (result.message == "HTTP 409") getString(R.string.automations_need_transcript) else actionErrorMessage(result)
                )
                if (isAmbiguousRerunFailure(result)) reconcileRerun(baselineRunId, onReconciled)
            }
            else -> showAlert(getString(R.string.automations), actionErrorMessage(result))
        }
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
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        binding.generateButton.isEnabled = false
        binding.emptySubtitle.text = getString(R.string.checking_transcript)

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
                binding.generateButton.isEnabled = true
                return@launch
            }
            // The lookup may have stored a server id; from here on the server path owns it.
            currentFile = findFile(file.id) ?: file
            if (serverRecordingId == null) serverRecordingId = currentFile?.serverId?.takeIf { it.isNotBlank() }
            if (foundByLookup) serverKnowsRecording = true
            registerAsLiveScreen()
            binding.generateButton.isEnabled = true
            when (outcome) {
                is ApiClient.TranscriptResult.Ready -> {
                    val arrived = transcriptArrived()
                    storeServerTranscript(outcome.rawJson)
                    render()
                    if (arrived && serverRecordingId != null) refreshRouting(awaitRun = true)
                }
                is ApiClient.TranscriptResult.Pending -> {
                    transcriptWasPending = true
                    binding.emptySubtitle.text = getString(R.string.transcription_pending)
                    bindAutomations() // a cached transcript on screen no longer counts as routable
                    // With the id now known, the recording object can be watched until it is
                    // done (provisionally, on the strength of the 409, see transcriptionInProgress).
                    syncTranscriptionPolling()
                }
                is ApiClient.TranscriptResult.NotFound -> {
                    binding.emptySubtitle.text = getString(R.string.transcript_not_on_server)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_not_on_server))
                }
                is ApiClient.TranscriptResult.AuthError -> {
                    binding.emptySubtitle.text = getString(R.string.transcript_auth_error)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_auth_error))
                }
                is ApiClient.TranscriptResult.Error -> {
                    binding.emptySubtitle.text = getString(R.string.transcript_server_error)
                    if (userInitiated) showTranscriptAlert(getString(R.string.transcript_server_error))
                }
            }
            // With the id known this can be the very first read of the automations, whatever the
            // transcript answer was (a cached transcript is on screen when it is Pending or Error).
            // Skipped when the Ready branch above already started one.
            if (serverRecordingId != null && routingRuns == null && !routingLoading) refreshRouting()
        }
    }

    private fun showTranscriptAlert(message: String) = showAlert(getString(R.string.transcript), message)

    /**
     * Parse the cached transcript JSON into "Speaker N · HH:MM:SS" paragraphs (mirrors iOS).
     * Tolerant of both a bare segment array and an object wrapping one; returns null if nothing
     * parseable so the caller can fall back to the empty state.
     */
    private fun parseTranscript(json: String): String? {
        val segments = parseSegments(json) ?: return null
        if (segments.isEmpty()) {
            // Segments empty (or absent) but the server may still have produced flat text.
            return try {
                org.json.JSONObject(json).optString("text").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        return segments.joinToString("\n\n") { (speakerLabel, startMs, text) ->
            "$speakerLabel · ${formatClock(startMs)}\n$text"
        }
    }

    /**
     * (speaker label, start millis, text) per non-blank segment. Null when the JSON is not a
     * transcript at all; an empty list when it is an object without a usable segment array, so
     * callers can still try its flat `text`.
     */
    private fun parseSegments(json: String): List<Triple<String, Long, String>>? {
        return try {
            val arr = when {
                json.trimStart().startsWith("[") -> org.json.JSONArray(json)
                else -> {
                    val obj = org.json.JSONObject(json)
                    obj.optJSONArray("segments")
                        ?: obj.optJSONArray("transaction")
                        ?: obj.optJSONArray("list")
                        ?: obj.optJSONArray("data")
                        ?: return emptyList()
                }
            }
            (0 until arr.length()).mapNotNull { i ->
                val seg = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = seg.optString("content", seg.optString("text", seg.optString("sentence")))
                if (text.isBlank()) return@mapNotNull null
                // speaker_id is a string like "SPEAKER_00" (Plaud result shape, mirrors iOS);
                // device-cache shapes may use a numeric "speaker".
                val speakerLabel = when {
                    seg.has("speaker_id") ->
                        seg.optString("speaker_id").replace("SPEAKER_", "Speaker ").ifBlank { "Speaker" }
                    else -> "Speaker ${seg.optInt("speaker", 0) + 1}"
                }
                // start_time/startTime are milliseconds (device cache shape); "start" is SECONDS
                // (Double, possibly a numeric string) in the Plaud transcription-result shape.
                val startMs = when {
                    seg.has("start_time") -> seg.optLong("start_time")
                    seg.has("startTime") -> seg.optLong("startTime")
                    else -> (seg.optDouble("start", 0.0).takeIf { !it.isNaN() }?.times(1000))?.toLong() ?: 0L
                }
                Triple(speakerLabel, startMs, text)
            }
        } catch (e: Exception) {
            null
        }
    }

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

    // MARK: - Audio Player

    // Media3 ExoPlayer: OEM MediaPlayerNative frequently fails on Ogg/Opus (the sync/export
    // format); ExoPlayer's own extractor + platform decoder handles it reliably.
    private var exoPlayer: ExoPlayer? = null

    private fun setupAudioPlayerControls() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.rewindButton.setOnClickListener { seekBy(-5_000) }
        binding.forwardButton.setOnClickListener { seekBy(5_000) }
        binding.progressSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = exoPlayer ?: return
                    val target = (progress / 1000f * p.duration).toLong().coerceAtLeast(0)
                    p.seekTo(target)
                    binding.currentTimeLabel.text = formatClock(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /**
     * Local audio when the phone has it (works offline, no auth needed), else the server stream
     * once the server copy is known, else no player.
     */
    private fun bindAudio(file: RecordingFile?, rec: ServerRecording?) {
        val path = file?.localPath
        if (path != null && File(path).exists()) {
            bindLocalAudioPlayer(path)
            return
        }
        if (rec != null) {
            bindServerAudioPlayer(rec)
            return
        }
        binding.audioPlayer.visibility = View.GONE
        releasePlayer()
    }

    private fun bindLocalAudioPlayer(path: String) {
        // Self-heal legacy files exported with the SDK's corrupt OpusTags header
        if (path.endsWith(".opus", ignoreCase = true)) {
            org.plaudbridge.app.common.OpusRepair.repairIfNeeded(path)
        }
        preparePlayer(key = path, mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(File(path))), httpFactory = null)
    }

    private fun bindServerAudioPlayer(rec: ServerRecording) {
        val url = try { ApiClient.recordingAudioUrl(rec.id) } catch (e: IllegalStateException) {
            binding.audioPlayer.visibility = View.GONE
            releasePlayer()
            return
        }
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to ApiClient.authHeader()))
        preparePlayer(key = url, mediaItem = MediaItem.fromUri(url), httpFactory = factory)
    }

    /**
     * Build and prepare the player for [mediaItem] unless the same [key] is already prepared
     * (avoids re-preparing on every onResume / re-bind). [httpFactory] is only set for server
     * streams; local files use the default file source.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun preparePlayer(key: String, mediaItem: MediaItem, httpFactory: DataSource.Factory?) {
        binding.audioPlayer.visibility = View.VISIBLE
        if (preparedKey == key && exoPlayer != null) return

        releasePlayer()
        val player = try {
            ExoPlayer.Builder(this).apply {
                if (httpFactory != null) {
                    setMediaSourceFactory(DefaultMediaSourceFactory(this@FileDetailActivity).setDataSourceFactory(httpFactory))
                }
            }.build()
        } catch (e: Exception) {
            // No usable player on this device (or JVM); the page still works without playback.
            org.plaudbridge.app.common.AppLog.w("FileDetail", "ExoPlayer unavailable", e)
            binding.audioPlayer.visibility = View.GONE
            return
        }
        exoPlayer = player
        // Remember the key now, not at STATE_READY: a second bind of the same recording (the
        // server path renders once for the header and again when the transcript lands) must not
        // tear down a player that is still buffering. onPlayerError releases and clears it.
        preparedKey = key
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_READY -> {
                        binding.totalTimeLabel.text = formatClock(player.duration.coerceAtLeast(0))
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        stopProgressUpdates()
                        binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                        player.pause()
                        player.seekTo(0)
                        binding.progressSlider.progress = 0
                        binding.currentTimeLabel.text = formatClock(0)
                    }
                    else -> {}
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                org.plaudbridge.app.common.AppLog.w("FileDetail", "ExoPlayer error: ${error.errorCodeName}")
                binding.audioPlayer.visibility = View.GONE
                releasePlayer()
            }
        })
        player.setMediaItem(mediaItem)
        player.prepare()
        binding.currentTimeLabel.text = formatClock(0)
        binding.progressSlider.progress = 0
    }

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            stopProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        } else {
            p.play()
            startProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun seekBy(deltaMs: Int) {
        val p = exoPlayer ?: return
        val duration = p.duration.coerceAtLeast(0)
        val target = (p.currentPosition + deltaMs).coerceIn(0, duration)
        p.seekTo(target)
        binding.currentTimeLabel.text = formatClock(target)
        binding.progressSlider.progress = if (duration > 0) (target * 1000 / duration).toInt() else 0
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val p = exoPlayer ?: return
                if (p.isPlaying) {
                    val duration = p.duration.coerceAtLeast(0)
                    binding.currentTimeLabel.text = formatClock(p.currentPosition)
                    binding.progressSlider.progress =
                        if (duration > 0) (p.currentPosition * 1000 / duration).toInt() else 0
                }
                progressHandler.postDelayed(this, 200)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null
        preparedKey = null
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000).toInt().coerceAtLeast(0)
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopTranscriptionPolling()
        // Pause playback when leaving the screen
        exoPlayer?.takeIf { it.isPlaying }?.let {
            it.pause()
            stopProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        routingHandler.removeCallbacksAndMessages(null)
        // Reads in flight die with the scope; whoever waited on them must not wait forever
        // (a rerun guard held for reconciliation would otherwise pin Run automations off).
        drainRoutingSettledCallbacks()
        rerunReconciliations.toList().forEach { it.release() }
        // Only our own entry: after a rotation the replacement may already be registered.
        serverRecordingId?.let { if (liveScreens[it] === this) liveScreens.remove(it) }
    }

    // MARK: - More Menu

    private fun showMoreMenu(anchor: View) {
        if (currentModel == null || currentItem() == null) return
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_detail, popup.menu)
        applyMenuVisibility(popup.menu)
        popup.setOnMenuItemClickListener { item -> onMenuAction(item.itemId) }
        popup.show()
    }

    /**
     * Hide what does not apply: phone actions need audio on the phone (an entry that is still to
     * be downloaded, or whose audio the user removed, has nothing to export or remove), server
     * actions need a server copy.
     */
    @VisibleForTesting
    internal fun applyMenuVisibility(menu: android.view.Menu) {
        val model = currentModel ?: return
        val hasAudio = currentFile?.isSynced == true
        val hasServer = serverRecordingId != null
        menu.findItem(R.id.action_export)?.isVisible = hasAudio
        menu.findItem(R.id.action_copy_summary)?.isVisible = model.summary != null
        menu.findItem(R.id.action_copy_transcript)?.isVisible = transcriptPlainText != null
        menu.findItem(R.id.action_export_markdown)?.isVisible = transcriptPlainText != null
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

    /** Menu dispatch, separate from the PopupMenu so tests can drive it. */
    @VisibleForTesting
    internal fun onMenuAction(itemId: Int): Boolean {
        val model = currentModel ?: return false
        val item = currentItem() ?: return false
        when (itemId) {
            R.id.action_export -> currentFile?.let { exportAudio(it) }
            R.id.action_rename -> showRenameDialog(item)
            R.id.action_copy_summary -> copySummary(model)
            R.id.action_copy_transcript -> copyTranscript()
            R.id.action_export_markdown -> exportMarkdown(model)
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
        binding.generateButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = RecordingActions.retranscribe(serverId, serverSource)) {
                is ApiClient.ActionResult.Ok -> {
                    Toast.makeText(this@FileDetailActivity, R.string.retranscribe_queued, Toast.LENGTH_SHORT).show()
                    transcriptWasPending = true
                    loadServerRecording(serverId)
                }
                else -> {
                    binding.generateButton.isEnabled = true
                    showTranscriptAlert(actionErrorMessage(result))
                }
            }
        }
    }

    private fun showRenameDialog(item: RecordingItem) {
        val editText = EditText(this).apply {
            setText(item.title)
            selectAll()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(editText)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.title_required, Toast.LENGTH_SHORT).show()
                } else {
                    rename(item, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun confirmRemoveFromPhone(item: RecordingItem) {
        AlertDialog.Builder(this)
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
        Toast.makeText(this, R.string.removed_from_phone, Toast.LENGTH_SHORT).show()
        currentFile = currentFile?.let { findFile(it.id) }
        if (serverRecording != null) render() else finish()
    }

    private fun confirmDelete(item: RecordingItem) {
        val shownTitle = RecordingsAdapter.rowTitle(this, item)
        val message = if (item.serverId != null) {
            getString(R.string.delete_everywhere_confirm_fmt, shownTitle)
        } else {
            getString(R.string.delete_phone_only_confirm_fmt, shownTitle)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ -> delete(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Server first (when it has the recording), then the phone copy; see [RecordingActions.delete]. */
    private fun delete(item: RecordingItem) {
        lifecycleScope.launch {
            when (val result = RecordingActions.delete(item, serverSource, syncManager)) {
                is ApiClient.ActionResult.Ok -> {
                    Toast.makeText(this@FileDetailActivity, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
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
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.export_audio)))
                }
                result.onFailure {
                    // Alert instead of toast (mirrors iOS "Export Failed")
                    AlertDialog.Builder(this)
                        .setTitle("Export Failed")
                        .setMessage(it.message ?: "Could not export this recording.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun copySummary(model: DetailModel) {
        // Silent copy (no toast, mirrors iOS convention)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Summary", model.summary))
    }

    /**
     * Copies the transcript as speaker paragraphs (no timestamps) and confirms with a toast. The
     * on-screen segment blocks stay as they are: they exist for following along with playback,
     * while a pasted transcript wants to read like a document.
     */
    private fun copyTranscript() {
        val text = transcriptCopyText ?: transcriptPlainText ?: return
        TranscriptShare.copyToClipboard(this, text)
    }

    /**
     * Clipboard text for a transcript document: the server's `text` field (one "Speaker N: ..."
     * line per turn, consecutive same-speaker segments already merged) as blank-line-separated
     * paragraphs. Legacy shapes without `text` fall back to the segments merged per speaker turn,
     * still without timestamps. Null when neither is available.
     */
    private fun copyTextFor(json: String): String? {
        transcriptExportFields(json).first?.let { return TranscriptMarkdown.plainParagraphs(it) }
        val segments = parseSegments(json)?.takeIf { it.isNotEmpty() } ?: return null
        val turns = mutableListOf<Pair<String, StringBuilder>>()
        for ((speaker, _, text) in segments) {
            val last = turns.lastOrNull()
            if (last != null && last.first == speaker) last.second.append(' ').append(text.trim())
            else turns += speaker to StringBuilder(text.trim())
        }
        return turns.joinToString("\n\n") { (speaker, text) -> "$speaker: $text" }
    }

    // MARK: - Markdown export

    /**
     * Export the transcript as markdown via the share sheet. The body is the server's flat
     * `text` field (already "Speaker N: ..." when diarized), which [TranscriptMarkdown] renders
     * as bold-speaker paragraphs so the file matches the server's own export byte for byte; the
     * on-screen paragraph rendering is only the fallback for legacy shapes that carry no `text`.
     */
    private fun exportMarkdown(model: DetailModel) {
        val json = model.transcriptJSON ?: return
        val (text, summary) = transcriptExportFields(json)
        val body = text ?: transcriptPlainText ?: return
        val markdown = TranscriptMarkdown.build(
            title = model.title,
            recordedAtMillis = model.recordedAtMillis,
            durationSeconds = model.durationSeconds,
            transcript = body,
            summary = summary ?: model.summary,
            highlights = TranscriptHighlight.parse(json)
        )
        try {
            TranscriptShare.share(this, ExportFileName.sanitize(model.title), model.title, markdown)
        } catch (e: Exception) {
            org.plaudbridge.app.common.AppLog.w("FileDetail", "markdown export failed", e)
            AlertDialog.Builder(this)
                .setTitle("Export Failed")
                .setMessage(e.message ?: "Could not export this transcript.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
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
