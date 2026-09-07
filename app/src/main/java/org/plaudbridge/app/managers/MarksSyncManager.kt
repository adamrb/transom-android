package org.plaudbridge.app.managers

import com.tinnotech.penblesdk.TntAgent
import com.tinnotech.penblesdk.entity.AgentCallback
import com.tinnotech.penblesdk.entity.bean.blepkg.response.GetRecMarkingRsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.models.DeviceConnectionState
import org.plaudbridge.app.models.RecordingFile
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Carries the recorder's button-press marks to the bridge server, where they become transcript
 * highlights.
 *
 * The Note Pro stores a mark each time its button is pressed during a recording (the official
 * app calls these highlights). They live only on the device, so the app has to ask for them over
 * BLE (IBleAgent.getRecMarkings) and hand them to the server, either inside the upload metadata
 * or, when the audio was uploaded before the marks were read, with a PATCH afterwards. Two work
 * lists drive a pass:
 *
 *  1. read: recordings of the CONNECTED device with marks == null (never read). Reads are bounded
 *     per pass and a session that fails (timeout, exception) is not retried in this process: a
 *     device that does not answer once will usually not answer the next second either, and the
 *     next launch or connect starts fresh. A successful read stores a list (possibly empty), and
 *     the recording leaves this list for good.
 *  2. push: recordings with a serverId, known marks and marksSynced == false. One PATCH each; 404
 *     and 401/403 are not retried here (repeating the same request cannot change the answer),
 *     transient failures are simply picked up by the next kick.
 *
 * Triggers: SyncManager right after each download (so the marks usually ride along in the upload
 * metadata and no PATCH is needed), DeviceManager shortly after connect (recordings downloaded
 * while BLE was down during a WiFi transfer, or before this feature existed), and UploadManager
 * after a pass that uploaded something. All of them are cheap when the work lists are empty.
 */
object MarksSyncManager {

    private const val TAG = "MarksSync"

    /** Upper bound on BLE reads per pass so a large backlog cannot hog the link after connect. */
    const val MAX_READS_PER_PASS = 20

    /** How long to wait for the device to answer one getRecMarkings before treating it as unknown. */
    internal var readTimeoutMs = 10_000L

    /**
     * Seam over the BLE SDK (a static facade) so passes are unit-testable. Production uses
     * [SdkMarksReader]; tests substitute a fake.
     */
    interface MarksReader {
        /** SN of the connected device, or null when nothing is connected. */
        fun connectedDeviceSN(): String?

        /**
         * The device's raw mark values for [sessionId], or null when it did not answer (timeout,
         * not connected, SDK error). Null must stay distinguishable from an empty list: only a
         * real answer may end the retries for a session.
         */
        suspend fun readRawMarks(sessionId: Long): List<Long>?
    }

    private object SdkMarksReader : MarksReader {
        override fun connectedDeviceSN(): String? = try {
            if (DeviceManager.shared.connectionState.value !is DeviceConnectionState.Connected) null
            else DeviceManager.shared.connectedDevice.value?.serialNumber?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }

        override suspend fun readRawMarks(sessionId: Long): List<Long>? {
            if (DeviceManager.shared.connectionState.value !is DeviceConnectionState.Connected) return null
            return withTimeoutOrNull(readTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    fun finish(value: List<Long>?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                    }
                    try {
                        TntAgent.getInstant().getBleAgent().getRecMarkings(
                            sessionId,
                            object : AgentCallback.OnRequest {
                                // false = the SDK could not even send the command; do not wait
                                // out the timeout for an answer that will never come.
                                override fun onCallback(sent: Boolean) { if (!sent) finish(null) }
                            },
                            object : AgentCallback.OnResponse<GetRecMarkingRsp> {
                                override fun onCallback(rsp: GetRecMarkingRsp?) {
                                    // getFileList() is the SDK's (misnamed) accessor for the
                                    // marking list; the private field behind it is markingList.
                                    finish(rsp?.fileList?.toList())
                                }
                            }
                        )
                    } catch (e: Exception) {
                        AppLog.w(TAG, "getRecMarkings failed for sessionId=$sessionId", e)
                        finish(null)
                    }
                }
            }
        }
    }

    /** Seam over ApiClient.patchMarks so passes are unit-testable without a live server. */
    fun interface MarksSink {
        fun patchMarks(recordingId: String, marks: List<Double>): ApiClient.PatchMarksResult
    }

    /** Replaceable for unit tests only. */
    internal var reader: MarksReader = SdkMarksReader

    /** Replaceable for unit tests only. */
    internal var sink: MarksSink = MarksSink { id, marks -> ApiClient.patchMarks(id, marks) }

    /** Outcome of one [runPass]. */
    data class PassResult(
        /** Sessions whose marks were read off the device and stored. */
        val read: Int,
        /** Device reads that produced no answer (left null, not retried in this process). */
        val readFailed: Int,
        /** Recordings whose marks the server now has. */
        val pushed: Int,
        /** PATCH attempts that did not succeed (transient or permanent, see the class KDoc). */
        val pushFailed: Int,
        /** Another pass held the guard; nothing was attempted. */
        val alreadyRunning: Boolean = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Single-pass guard: two passes would issue the same BLE reads and PATCHes twice. */
    private val running = AtomicBoolean(false)

    /** Work arrived while a run was in flight; loop again instead of dropping the wakeup. */
    private val dirty = AtomicBoolean(false)

    /** Sessions (deviceSN to sessionId) whose device read failed in this process. */
    private val readFailedThisProcess = HashSet<Pair<String, Long>>()

    /** Test hook: forget which device reads already failed. */
    internal fun resetForTest() {
        synchronized(readFailedThisProcess) { readFailedThisProcess.clear() }
    }

    /**
     * Read and store the marks for one just-downloaded recording. Called by SyncManager at both
     * download-complete sites so the marks are usually in the store before UploadManager builds
     * the upload metadata. Does nothing when the recording's device is not the connected one
     * (WiFi fast transfer with BLE down); the next connect's [kick] covers that.
     */
    fun fetchForSession(deviceSN: String, sessionId: Long) {
        scope.launch {
            val rec = RecordingStore.allFiles.find { it.deviceSN == deviceSN && it.sessionId == sessionId }
                ?: return@launch
            if (rec.marks != null) return@launch
            if (reader.connectedDeviceSN() != deviceSN) return@launch
            if (!readAndStore(rec)) return@launch
            // UploadManager usually starts the upload before this BLE round trip is back, so the
            // metadata went out without marks. If the audio is already on the server, send them
            // now instead of waiting for the next connect. Push only: reading every other unread
            // session here would compete with the download that is starting right now.
            if (RecordingStore.isServerConfigured && RecordingStore.awaitingMarksSync.isNotEmpty()) {
                runPass(readsAllowed = false)
            }
        }
    }

    /**
     * Run a pass now. Cheap with nothing to do: with no unread recordings for the connected device
     * and nothing awaiting a PATCH it costs one store read.
     */
    fun kick() {
        scope.launch {
            if (!hasWork()) return@launch
            runPass()
        }
    }

    private fun hasWork(): Boolean {
        val connected = reader.connectedDeviceSN()
        val toRead = connected?.let { readCandidates(it) } ?: emptyList()
        return toRead.isNotEmpty() || (RecordingStore.isServerConfigured && RecordingStore.awaitingMarksSync.isNotEmpty())
    }

    private fun readCandidates(deviceSN: String): List<RecordingFile> = synchronized(readFailedThisProcess) {
        RecordingStore.awaitingMarksRead(deviceSN).filter { (it.deviceSN to it.sessionId) !in readFailedThisProcess }
    }

    /**
     * One pass (device reads, then PATCHes), callable from any coroutine. Returns
     * [PassResult.alreadyRunning] without touching anything when another pass holds the guard;
     * that pass sees the dirty flag and loops once more, so the kick is not lost.
     *
     * @param readsAllowed false skips the BLE read phase (PATCH only), see [fetchForSession]
     */
    suspend fun runPass(readsAllowed: Boolean = true): PassResult {
        dirty.set(true)
        if (!running.compareAndSet(false, true)) {
            return PassResult(0, 0, 0, 0, alreadyRunning = true)
        }
        var read = 0
        var readFailed = 0
        var pushed = 0
        var pushFailed = 0
        try {
            while (dirty.getAndSet(false)) {
                val r = if (readsAllowed) readPhase() else 0 to 0
                read += r.first
                readFailed += r.second
                val p = pushPhase()
                pushed += p.first
                pushFailed += p.second
            }
        } finally {
            running.set(false)
        }
        return PassResult(read = read, readFailed = readFailed, pushed = pushed, pushFailed = pushFailed)
    }

    /** Read marks for up to [MAX_READS_PER_PASS] unread recordings of the connected device. */
    private suspend fun readPhase(): Pair<Int, Int> {
        val connected = reader.connectedDeviceSN() ?: return 0 to 0
        val work = readCandidates(connected).take(MAX_READS_PER_PASS)
        if (work.isEmpty()) return 0 to 0
        AppLog.i(TAG, "Reading marks for ${work.size} recording(s) on $connected")
        var read = 0
        var failed = 0
        for (rec in work) {
            // The device may have disconnected mid-pass; a read then only burns the timeout.
            if (reader.connectedDeviceSN() != connected) break
            if (readAndStore(rec)) read++ else failed++
        }
        return read to failed
    }

    /**
     * One device read: raw list logged (this is what tells us the unit on the first real
     * recording), normalized against the session id and duration, stored. Returns false when
     * the device gave no answer, in which case the session is remembered as failed for this
     * process and its marks stay null.
     */
    private suspend fun readAndStore(rec: RecordingFile): Boolean {
        val raw = try {
            reader.readRawMarks(rec.sessionId)
        } catch (t: Throwable) {
            AppLog.w(TAG, "marks read threw for sessionId=${rec.sessionId}", t)
            null
        }
        if (raw == null) {
            synchronized(readFailedThisProcess) { readFailedThisProcess.add(rec.deviceSN to rec.sessionId) }
            AppLog.w(TAG, "No marks answer for sessionId=${rec.sessionId} (timeout or not connected); left unknown")
            return false
        }
        val marks = MarkNormalizer.toOffsetsSeconds(raw, rec.sessionId, rec.duration)
        AppLog.i(
            TAG,
            "Raw marks for sessionId=${rec.sessionId} duration=${rec.duration}s: $raw -> offsets(s)=$marks"
        )
        RecordingStore.updateMarks(rec.id, marks)
        return true
    }

    /** PATCH the marks of every recording the server knows but has not received marks for. */
    private fun pushPhase(): Pair<Int, Int> {
        if (!RecordingStore.isServerConfigured) return 0 to 0
        val work = RecordingStore.awaitingMarksSync
        if (work.isEmpty()) return 0 to 0
        AppLog.i(TAG, "Pushing marks for ${work.size} recording(s)")
        var pushed = 0
        var failed = 0
        for (rec in work) {
            val serverId = rec.serverId ?: continue
            val marks = rec.marks ?: continue
            // Same guard as uploads: a PATCH accepted by the OLD server must not flag the marks
            // as synced after the user switched servers.
            val configGen = RecordingStore.serverConfigGeneration
            val result = try {
                sink.patchMarks(serverId, marks)
            } catch (t: Throwable) {
                ApiClient.PatchMarksResult.Error(t.message ?: "network error")
            }
            if (RecordingStore.serverConfigGeneration != configGen) {
                failed++
                AppLog.w(TAG, "Marks result discarded, server config changed mid-request (serverId=$serverId)")
                continue
            }
            when (result) {
                ApiClient.PatchMarksResult.Ok -> {
                    RecordingStore.markMarksSynced(rec.id, marks)
                    pushed++
                    AppLog.i(TAG, "Marks synced for serverId=$serverId (${marks.size} mark(s))")
                }
                ApiClient.PatchMarksResult.NotFound -> {
                    failed++
                    AppLog.w(TAG, "Server has no recording $serverId; marks not sent")
                }
                is ApiClient.PatchMarksResult.AuthError -> {
                    // Every further request in this pass carries the same rejected token.
                    failed++
                    AppLog.w(TAG, "Marks PATCH rejected (HTTP ${result.code}); aborting pass")
                    break
                }
                is ApiClient.PatchMarksResult.Error -> {
                    failed++
                    AppLog.w(TAG, "Marks PATCH failed for serverId=$serverId: ${result.message}")
                }
            }
        }
        return pushed to failed
    }
}
