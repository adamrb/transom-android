package org.plaudbridge.app.ui.filedetail

import android.content.ClipData
import android.content.ClipboardManager
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
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.models.ServerRecording
import org.plaudbridge.app.net.ApiClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * File detail page
 * Header (name/date/duration/status) + Summary + Highlights + Transcript + More Menu
 *
 * Two sources feed the same screen:
 *  - a LOCAL file (`file_id` extra): a [RecordingFile] from RecordingStore, the Files tab path;
 *  - a SERVER recording (`server_recording_id` extra): fetched live from the bridge server, the
 *    Library tab path. Nothing from this mode is ever written into RecordingStore; the server
 *    owns its recordings and the phone's index must stay the phone's.
 * Both map onto [DetailModel], the handful of fields the header, summary, highlights and
 * transcript blocks actually render, so the two paths cannot drift apart visually.
 */
class FileDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileDetailBinding
    private val syncManager get() = (application as PlaudBridgeApp).syncManager

    private var currentFile: RecordingFile? = null

    /** Server-mode state; null in local mode. */
    private var serverRecordingId: String? = null
    private var serverRecording: ServerRecording? = null
    private val isServerMode get() = serverRecordingId != null

    /** What the content blocks currently show, whichever source it came from. */
    private var currentModel: DetailModel? = null

    // Audio player (media3 ExoPlayer)
    private var preparedKey: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    /**
     * The fields the content blocks render. [transcriptJSON] is the server transcript document
     * (text, segments, summary, highlights) in both modes; local files cache the same document.
     */
    data class DetailModel(
        val title: String,
        val recordedAtMillis: Long,
        val durationSeconds: Long,
        val summary: String?,
        val transcriptJSON: String?
    )

    /**
     * The server calls the screen makes in server mode. An interface (with the real client as
     * the default) so a Robolectric test can drive the screen without a network stack; the
     * default runs the blocking ApiClient calls on Dispatchers.IO.
     */
    interface ServerDetailSource {
        suspend fun recording(id: String): ApiClient.RecordingResult
        suspend fun transcript(id: String): ApiClient.TranscriptResult
        suspend fun rename(id: String, title: String): ApiClient.RecordingResult
        suspend fun retranscribe(id: String): ApiClient.ActionResult
        suspend fun delete(id: String): ApiClient.ActionResult
    }

    private object ApiServerDetailSource : ServerDetailSource {
        override suspend fun recording(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchRecording(id) }
        override suspend fun transcript(id: String) = withContext(Dispatchers.IO) { ApiClient.fetchTranscript(id) }
        override suspend fun rename(id: String, title: String) =
            withContext(Dispatchers.IO) { ApiClient.renameRecording(id, title) }
        override suspend fun retranscribe(id: String) = withContext(Dispatchers.IO) { ApiClient.retranscribe(id) }
        override suspend fun delete(id: String) = withContext(Dispatchers.IO) { ApiClient.deleteRecording(id) }
    }

    companion object {
        /** Intent extra: open a recording that lives on the bridge server (Library tab). */
        const val EXTRA_SERVER_RECORDING_ID = "server_recording_id"

        /** Swapped by tests; production always uses the ApiClient-backed default. */
        @VisibleForTesting
        var serverSource: ServerDetailSource = ApiServerDetailSource
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

        val serverId = intent.getStringExtra(EXTRA_SERVER_RECORDING_ID)
        if (serverId != null) {
            serverRecordingId = serverId
            loadServerRecording(serverId)
            return
        }
        val fileId = intent.getStringExtra("file_id") ?: run { finish(); return }
        loadFile(fileId)
    }

    override fun onResume() {
        super.onResume()
        // Refresh data (after returning from a rename). Server mode re-renders in place after
        // its own writes, so nothing to reload there.
        if (!isServerMode) currentFile?.let { loadFile(it.id) }
    }

    private fun loadFile(fileId: String) {
        // Read from the persistent store FIRST: updateTranscript/updateDuration write to disk,
        // while syncManager.files is an in-memory snapshot that may still hold stale objects
        // (e.g. transcriptJSON = null right after a transcription completes).
        val file = org.plaudbridge.app.storage.RecordingStore.allFiles.find { it.id == fileId }
            ?: syncManager.files.value.find { it.id == fileId }
            ?: run { finish(); return }
        currentFile = file
        bindFile(file)
    }

    /** On-screen transcript ("Speaker N · HH:MM:SS" blocks); null when nothing parseable. */
    private var transcriptPlainText: String? = null

    /** What Copy transcript puts on the clipboard: speaker paragraphs, no timestamps. */
    private var transcriptCopyText: String? = null

    private fun bindFile(file: RecordingFile) {
        val model = DetailModel(
            title = file.displayName,
            recordedAtMillis = file.createdAt,
            durationSeconds = file.duration,
            summary = file.summaryText,
            transcriptJSON = file.transcriptJSON
        )
        bindContent(model)

        // Self-heal: entries synced before the duration fix have duration 0 stored; recompute
        // from the local audio and backfill so old files show the real length too.
        val localPath = file.localPath
        if (file.duration <= 0 && localPath != null && File(localPath).exists()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val d = org.plaudbridge.app.managers.SyncManager.shared.audioDurationSec(localPath)
                if (d > 0) {
                    org.plaudbridge.app.storage.RecordingStore.updateDuration(file.id, d)
                    runOnUiThread { bindMetaLine(file.createdAt, d) }
                }
            }
        }

        // Status badge: upload state against the self-hosted bridge server
        when {
            file.uploaded -> setStatusBadge(getString(R.string.uploaded), R.color.green, R.drawable.bg_status_synced)
            file.isSynced -> setStatusBadge(getString(R.string.upload_pending), R.color.orange, R.drawable.bg_status_pending)
            else -> setStatusBadge(getString(R.string.on_device), R.color.orange, R.drawable.bg_status_pending)
        }

        // Transcript empty state (only when bindContent found no transcript)
        if (transcriptPlainText == null) {
            when {
                file.uploaded -> {
                    showEmptyState(getString(R.string.transcript), getString(R.string.transcription_pending),
                        getString(R.string.check_transcript)) { fetchTranscriptFromServer(file, userInitiated = true) }
                    // Auto-check once whenever the detail page opens for an uploaded file.
                    fetchTranscriptFromServer(file, userInitiated = false)
                }
                file.isSynced -> showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_upload))
                else -> showEmptyState(getString(R.string.no_transcript), getString(R.string.transcript_needs_sync))
            }
        }

        // Audio player: only available once the file has a local audio file
        bindLocalAudioPlayer(file)
    }

    /**
     * Header, summary, highlights and transcript blocks from a [DetailModel]. Shared by both
     * sources. Leaves the empty state hidden; callers decide what it says because the reason a
     * transcript is missing differs per source.
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

    private fun setStatusBadge(text: String, colorRes: Int, backgroundRes: Int) {
        binding.statusBadge.text = text
        binding.statusBadge.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.statusBadge.setBackgroundResource(backgroundRes)
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

    // MARK: - Server mode (Library)

    /**
     * GET the recording, then its transcript. The header renders as soon as the recording
     * arrives so the screen is not blank while a long transcript downloads; 409 (still
     * transcribing) and the other outcomes become the empty state's wording.
     */
    private fun loadServerRecording(id: String) {
        binding.fileNameLabel.text = ""
        binding.fileDateLabel.text = getString(R.string.checking_transcript)
        lifecycleScope.launch {
            when (val result = serverSource.recording(id)) {
                is ApiClient.RecordingResult.Ok -> {
                    bindServerRecording(result.recording, transcriptJSON = null)
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
                is ApiClient.TranscriptResult.Ready -> bindServerRecording(rec, outcome.rawJson)
                is ApiClient.TranscriptResult.Pending -> bindServerRecording(rec, null)
                is ApiClient.TranscriptResult.NotFound ->
                    if (rec.status == ServerRecording.STATUS_DONE) failServer(getString(R.string.recording_not_on_server))
                    else bindServerRecording(rec, null)
                is ApiClient.TranscriptResult.AuthError -> failServer(getString(R.string.transcript_auth_error))
                is ApiClient.TranscriptResult.Error -> failServer(getString(R.string.transcript_server_error))
            }
        }
    }

    /** Render a server recording; the empty state reflects the server's status word. */
    private fun bindServerRecording(rec: ServerRecording, transcriptJSON: String?) {
        serverRecording = rec
        // The transcript's own summary/title win when present (they are the freshest), else the
        // list object's fields. Same precedence the Files path applies via TitleSyncManager.
        val transcriptSummary = transcriptJSON?.let { transcriptExportFields(it).second }
        bindContent(
            DetailModel(
                title = rec.displayTitle,
                recordedAtMillis = rec.recordedAt,
                durationSeconds = rec.durationSeconds,
                summary = transcriptSummary ?: rec.summary,
                transcriptJSON = transcriptJSON
            )
        )
        when (rec.status) {
            ServerRecording.STATUS_DONE -> setStatusBadge(getString(R.string.status_done), R.color.green, R.drawable.bg_status_synced)
            ServerRecording.STATUS_FAILED -> setStatusBadge(getString(R.string.status_failed), R.color.red, R.drawable.bg_status_pending)
            ServerRecording.STATUS_TRANSCRIBING -> setStatusBadge(getString(R.string.status_transcribing), R.color.orange, R.drawable.bg_status_pending)
            ServerRecording.STATUS_STORED -> setStatusBadge(getString(R.string.status_stored), R.color.orange, R.drawable.bg_status_pending)
            else -> setStatusBadge(getString(R.string.status_pending), R.color.orange, R.drawable.bg_status_pending)
        }
        if (transcriptPlainText == null) {
            when (rec.status) {
                ServerRecording.STATUS_FAILED -> showEmptyState(
                    getString(R.string.no_transcript),
                    rec.error?.takeIf { it.isNotBlank() } ?: getString(R.string.transcription_failed),
                    getString(R.string.retranscribe)
                ) { retranscribeOnServer(rec) }
                ServerRecording.STATUS_STORED -> showEmptyState(
                    getString(R.string.no_transcript), getString(R.string.transcript_not_started),
                    getString(R.string.transcribe)
                ) { retranscribeOnServer(rec) }
                else -> showEmptyState(
                    getString(R.string.transcript), getString(R.string.transcription_pending),
                    getString(R.string.check_transcript)
                ) { refreshServerRecording(rec.id) }
            }
        }
        bindServerAudioPlayer(rec)
    }

    /** Re-fetch both the recording (status may have moved on) and its transcript. */
    private fun refreshServerRecording(id: String) {
        binding.generateButton.isEnabled = false
        binding.emptySubtitle.text = getString(R.string.checking_transcript)
        loadServerRecording(id)
    }

    private fun failServer(message: String) {
        if (serverRecording == null) {
            binding.fileDateLabel.text = ""
            showEmptyState(getString(R.string.transcript), message)
        } else {
            showEmptyState(getString(R.string.transcript), message, getString(R.string.retry)) {
                serverRecordingId?.let { refreshServerRecording(it) }
            }
        }
    }

    private fun retranscribeOnServer(rec: ServerRecording) {
        binding.generateButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = serverSource.retranscribe(rec.id)) {
                is ApiClient.ActionResult.Ok -> {
                    Toast.makeText(this@FileDetailActivity, R.string.retranscribe_queued, Toast.LENGTH_SHORT).show()
                    loadServerRecording(rec.id)
                }
                else -> {
                    binding.generateButton.isEnabled = true
                    showTranscriptAlert(actionErrorMessage(result))
                }
            }
        }
    }

    private fun actionErrorMessage(result: ApiClient.ActionResult): String = when (result) {
        is ApiClient.ActionResult.Ok -> ""
        is ApiClient.ActionResult.NotFound -> getString(R.string.recording_not_on_server)
        is ApiClient.ActionResult.AuthError -> getString(R.string.transcript_auth_error)
        is ApiClient.ActionResult.Error -> getString(R.string.server_request_failed_fmt, result.message)
    }

    private fun renameOnServer(rec: ServerRecording, newTitle: String) {
        lifecycleScope.launch {
            when (val result = serverSource.rename(rec.id, newTitle)) {
                is ApiClient.RecordingResult.Ok -> {
                    // Keep the transcript we already have; only the header changes.
                    bindServerRecording(result.recording, currentModel?.transcriptJSON)
                }
                is ApiClient.RecordingResult.NotFound -> showTranscriptAlert(getString(R.string.recording_not_on_server))
                is ApiClient.RecordingResult.AuthError -> showTranscriptAlert(getString(R.string.transcript_auth_error))
                is ApiClient.RecordingResult.Error ->
                    showTranscriptAlert(getString(R.string.server_request_failed_fmt, result.message))
            }
        }
    }

    private fun deleteOnServer(rec: ServerRecording) {
        lifecycleScope.launch {
            when (val result = serverSource.delete(rec.id)) {
                is ApiClient.ActionResult.Ok, is ApiClient.ActionResult.NotFound -> {
                    Toast.makeText(this@FileDetailActivity, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
                    finish() // the Library reloads on resume
                }
                else -> showTranscriptAlert(actionErrorMessage(result))
            }
        }
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

    // MARK: - Transcript (fetched from the self-hosted bridge server)

    /** Guard so the automatic check on open runs only once per page view. */
    private var transcriptChecked = false

    /**
     * Fetch the transcript from the bridge server:
     * resolve the server-side recording id (lookup by device_sn + session_id when not cached),
     * then GET /recordings/{id}/transcript. 404/409 means the transcription is still pending.
     */
    private fun fetchTranscriptFromServer(file: RecordingFile, userInitiated: Boolean) {
        if (!userInitiated) {
            if (transcriptChecked) return
            transcriptChecked = true
        }
        if (!org.plaudbridge.app.storage.RecordingStore.isServerConfigured) return
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
                                org.plaudbridge.app.storage.RecordingStore.updateServerId(file.id, lookup.id)
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

            binding.generateButton.isEnabled = true
            when (outcome) {
                is ApiClient.TranscriptResult.Ready -> {
                    // Stores the transcript AND its AI title, then refreshes the list screens.
                    org.plaudbridge.app.managers.TitleSyncManager.storeTranscript(file.id, outcome.rawJson)
                    loadFile(file.id) // re-render: transcript body plus the title label
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

    private fun bindLocalAudioPlayer(file: RecordingFile) {
        val path = file.localPath
        val exists = path != null && File(path).exists()
        if (!exists) {
            binding.audioPlayer.visibility = View.GONE
            releasePlayer()
            return
        }
        // Self-heal legacy files exported with the SDK's corrupt OpusTags header
        if (path!!.endsWith(".opus", ignoreCase = true)) {
            org.plaudbridge.app.common.OpusRepair.repairIfNeeded(path)
        }
        preparePlayer(key = path, mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(File(path))), httpFactory = null)
    }

    /**
     * Stream the recording from the server. The audio endpoint is behind the same bearer token
     * as the API, so ExoPlayer's HTTP source is given the Authorization header up front; it also
     * sends Range requests, which the server honors, so seeking does not re-download the file.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_detail, popup.menu)

        val model = currentModel ?: return
        val file = currentFile
        val rec = serverRecording

        // Hide options that do not apply (Export Audio stays visible like iOS; failure alerts)
        popup.menu.findItem(R.id.action_copy_summary)?.isVisible = model.summary != null
        popup.menu.findItem(R.id.action_copy_transcript)?.isVisible = transcriptPlainText != null
        popup.menu.findItem(R.id.action_export_markdown)?.isVisible = transcriptPlainText != null
        // Local-only vs server-only items
        popup.menu.findItem(R.id.action_export)?.isVisible = !isServerMode
        popup.menu.findItem(R.id.action_retranscribe)?.isVisible = isServerMode
        popup.menu.findItem(R.id.action_delete)?.isVisible = !isServerMode
        popup.menu.findItem(R.id.action_delete_server)?.isVisible = isServerMode

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    file?.let { exportAudio(it) }
                    true
                }
                R.id.action_rename -> {
                    if (rec != null) showServerRenameDialog(rec) else file?.let { showRenameDialog(it) }
                    true
                }
                R.id.action_copy_summary -> {
                    copySummary(model)
                    true
                }
                R.id.action_copy_transcript -> {
                    copyTranscript()
                    true
                }
                R.id.action_export_markdown -> {
                    exportMarkdown(model)
                    true
                }
                R.id.action_retranscribe -> {
                    rec?.let { retranscribeOnServer(it) }
                    true
                }
                R.id.action_delete -> {
                    file?.let { showDeleteConfirmation(it) }
                    true
                }
                R.id.action_delete_server -> {
                    rec?.let { showServerDeleteConfirmation(it) }
                    true
                }
                else -> false
            }
        }
        popup.show()
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

    private fun showRenameDialog(file: RecordingFile) {
        val editText = EditText(this).apply {
            setText(file.displayName)
            selectAll()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(editText)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    syncManager.renameFile(file, newName)
                    loadFile(file.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showServerRenameDialog(rec: ServerRecording) {
        val editText = EditText(this).apply {
            setText(rec.displayTitle)
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
                    renameOnServer(rec, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun showDeleteConfirmation(file: RecordingFile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_recording))
            // Accurate scope: this removes ONLY the phone's downloaded copy; any copy still on
            // the recorder and anything already uploaded to your server are untouched.
            .setMessage(
                "This removes the downloaded copy of \"${file.displayName}\" from this phone. " +
                    "Copies on the recorder or on your server are not deleted."
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                syncManager.deleteFile(file)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showServerDeleteConfirmation(rec: ServerRecording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_from_server)
            .setMessage(getString(R.string.delete_from_server_confirm_fmt, rec.displayTitle))
            .setPositiveButton(R.string.delete) { _, _ -> deleteOnServer(rec) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
