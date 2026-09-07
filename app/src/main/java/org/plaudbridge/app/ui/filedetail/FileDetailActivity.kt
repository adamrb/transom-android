package org.plaudbridge.app.ui.filedetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
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
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.ui.recordings.ApiServerRecordingActions
import org.plaudbridge.app.ui.recordings.RecordingActions
import org.plaudbridge.app.ui.recordings.RecordingItem
import org.plaudbridge.app.ui.recordings.ServerRecordingActions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Recording detail page
 * Header (name/date/duration/status) + Summary + Highlights + Transcript + More Menu
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

    /** What the content blocks currently show, whichever source it came from. */
    private var currentModel: DetailModel? = null

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
    }

    private object ApiServerDetailSource : ServerDetailSource, ServerRecordingActions by ApiServerRecordingActions {
        override suspend fun recording(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRecording(id) }
        override suspend fun transcript(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchTranscript(id) }
    }

    companion object {
        /** Intent extra: the phone's RecordingFile id. */
        const val EXTRA_FILE_ID = "file_id"

        /** Intent extra: the bridge server's recording id. */
        const val EXTRA_SERVER_RECORDING_ID = "server_recording_id"

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var serverSource: ServerDetailSource = ApiServerDetailSource

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
            serverId != null && RecordingStore.isServerConfigured -> loadServerRecording(serverId)
            // Uploaded before the phone learned the server id (legacy index): resolve it by
            // (device, session) and fetch the transcript the old way.
            file != null && file.uploaded && transcriptPlainText == null ->
                fetchTranscriptFromServer(file, userInitiated = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up what changed while we were away (a rename, a transcript stored by the
        // background title sync); the server side re-renders in place after its own writes.
        val file = currentFile ?: return
        currentFile = findFile(file.id) ?: file
        if (currentModel != null) render()
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
        val transcriptJSON = serverTranscriptJSON ?: file?.transcriptJSON
        val transcriptSummary = transcriptJSON?.let { transcriptExportFields(it).second }
        bindContent(
            DetailModel(
                title = item.title,
                recordedAtMillis = item.recordedAt,
                durationSeconds = item.durationSeconds,
                summary = transcriptSummary ?: rec?.summary ?: file?.summaryText,
                transcriptJSON = transcriptJSON
            )
        )
        bindStatusBadge(item.status)
        if (transcriptPlainText == null) bindEmptyState(item)
        bindAudio(file, rec)
    }

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
        if (hasSummary) binding.summaryText.text = model.summary

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
        val dateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
        binding.fileDateLabel.text =
            "${dateFormat.format(Date(recordedAtMillis))} · ${formatMetaDuration(durationSeconds)}"
    }

    /**
     * The badge only speaks while the server is still working on the recording or gave up on
     * it. A finished recording wears no badge: "Transcribed" or "Uploaded" would just restate
     * that the transcript below exists.
     */
    private fun bindStatusBadge(status: RecordingItem.Status) {
        when (status) {
            RecordingItem.Status.TRANSCRIBING ->
                setStatusBadge(getString(R.string.status_transcribing), R.color.orange, R.drawable.bg_status_pending)
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
     * Why there is no transcript yet, and the one button that can change that. "Check for
     * transcript" appears whenever the server has (or should have) this recording; a recording
     * that has not left the phone or the recorder yet gets an explanation and no button.
     */
    private fun bindEmptyState(item: RecordingItem) {
        val rec = item.server
        val file = item.local
        when {
            item.serverId != null || file?.uploaded == true -> {
                val subtitle = when (rec?.status) {
                    ServerRecording.STATUS_FAILED ->
                        rec.error?.takeIf { it.isNotBlank() } ?: getString(R.string.transcription_failed)
                    ServerRecording.STATUS_STORED -> getString(R.string.transcript_not_started)
                    else -> getString(R.string.transcription_pending)
                }
                showEmptyState(getString(R.string.transcript), subtitle, getString(R.string.check_transcript)) {
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
                is ApiClient.RecordingResult.Ok -> {
                    serverRecording = result.recording
                    render()
                    if (transcriptPlainText == null) binding.emptySubtitle.text = getString(R.string.checking_transcript)
                    loadServerTranscript(result.recording)
                }
                is ApiClient.RecordingResult.NotFound -> failServer(getString(R.string.recording_not_on_server))
                is ApiClient.RecordingResult.AuthError -> failServer(getString(R.string.transcript_auth_error))
                is ApiClient.RecordingResult.Error -> failServer(getString(R.string.transcript_server_error))
            }
        }
    }

    private fun loadServerTranscript(rec: ServerRecording) {
        lifecycleScope.launch {
            when (val outcome = serverSource.transcript(rec.id)) {
                is ApiClient.TranscriptResult.Ready -> {
                    storeServerTranscript(outcome.rawJson)
                    render()
                }
                is ApiClient.TranscriptResult.Pending -> render()
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
        val file = currentFile ?: return
        if (file.transcriptJSON == rawJson) return
        TitleSyncManager.storeTranscript(file.id, rawJson)
        currentFile = findFile(file.id) ?: file
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
                                RecordingStore.updateServerId(file.id, lookup.id)
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

            // The lookup may have stored a server id; from here on the server path owns it.
            currentFile = findFile(file.id) ?: file
            if (serverRecordingId == null) serverRecordingId = currentFile?.serverId?.takeIf { it.isNotBlank() }
            binding.generateButton.isEnabled = true
            when (outcome) {
                is ApiClient.TranscriptResult.Ready -> {
                    storeServerTranscript(outcome.rawJson)
                    render()
                }
                is ApiClient.TranscriptResult.Pending -> {
                    binding.emptySubtitle.text = getString(R.string.transcription_pending)
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
        }
    }

    private fun showTranscriptAlert(message: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transcript))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

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
            .setMessage(getString(R.string.remove_from_phone_confirm_fmt, item.title))
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
        val message = if (item.serverId != null) {
            getString(R.string.delete_everywhere_confirm_fmt, item.title)
        } else {
            getString(R.string.delete_phone_only_confirm_fmt, item.title)
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
