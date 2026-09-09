package org.plaudbridge.app.managers

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.plaudbridge.app.PlaudBridgeApp
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.AppNotifications
import org.plaudbridge.app.models.Delivery
import org.plaudbridge.app.models.RoutingRun
import org.plaudbridge.app.net.ApiClient
import org.plaudbridge.app.storage.RecordingStore
import org.plaudbridge.app.work.AutomationWatchScheduler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Follows a recording's automations after its transcript lands (or after the user runs them by
 * hand) and announces each outcome once with a notification: "Work meetings done: Created
 * Work/Meetings/…", "Ask Claude failed: …", "No automation matched".
 *
 * The server does not push, so this polls GET /recordings/{id}/routing for every watched
 * recording: in-app for a while after each [watch] (agents usually answer within a few
 * minutes), with a durable WorkManager request ([AutomationWatchScheduler]) as the fallback
 * that survives process death. A watch ends when the router has run for it and no hand-off is
 * still working, when the server no longer knows the recording, or after [WATCH_TTL_MS].
 *
 * Announcements are deduplicated by delivery id and router-run id in a persisted, bounded list,
 * so a recording watched twice (a re-transcribe, a manual rerun) never repeats an old result.
 * [watch] takes the ids of hand-offs the caller already knows so a rerun does not announce the
 * previous run's outcomes; for a fresh transcript, runs clearly older than the watch are
 * treated the same way.
 *
 * Seams (test-only): [routingSource], [notifier], [scheduler], [clock], [inAppPollDelayMs].
 */
object AutomationWatcher {

    private const val TAG = "AutomationWatcher"

    /**
     * Give up on a recording whose hand-offs never settle. Filing agents are done within
     * minutes; an "Ask Claude" session reports when the user's task is finished, which can take
     * hours. The server itself declares a silent hand-off unknown after six hours; with the
     * app in the background WorkManager's exponential backoff makes its last polls at about
     * 4h15 and 8h30 after the watch started, so nine hours lets that verdict be announced too.
     */
    const val WATCH_TTL_MS = 9 * 60 * 60 * 1000L

    /**
     * A router run this much older than the watch is presumed to be history (an earlier
     * transcription's run) rather than the run being waited for, UNLESS it still holds an
     * outcome this phone has never announced: a transcript fetched hours late (the phone was
     * offline) brings an old-looking run that is exactly the news the user is waiting for.
     */
    const val OLD_RUN_SLACK_MS = 10 * 60 * 1000L

    /** In-app polling: bounded like TitleSyncManager, then WorkManager's backoff takes over. */
    internal var inAppPollDelayMs = 15_000L
    const val MAX_IN_APP_POLLS = 24

    /** How many announced ids are remembered; older ones can no longer come back. */
    const val ANNOUNCED_LIMIT = 300

    /** Seam over ApiClient.fetchRouting. */
    fun interface RoutingSource {
        fun fetchRouting(recordingId: String): ApiClient.RoutingResult
    }

    internal var routingSource: RoutingSource = RoutingSource { ApiClient.fetchRouting(it) }

    /** Where announcements go; the default posts notifications through [AppNotifications]. */
    interface Notifier {
        fun finished(watch: Watch, delivery: Delivery)
        fun skipped(watch: Watch, run: RoutingRun)
    }

    internal var notifier: Notifier = object : Notifier {
        private fun context(): Context? = try { PlaudBridgeApp.instance } catch (e: UninitializedPropertyAccessException) { null }

        override fun finished(watch: Watch, delivery: Delivery) {
            val ctx = context() ?: return
            AppNotifications.automationFinished(ctx, watch.fileId, watch.serverId, currentTitle(watch), delivery)
        }

        override fun skipped(watch: Watch, run: RoutingRun) {
            val ctx = context() ?: return
            AppNotifications.automationsSkipped(ctx, watch.fileId, watch.serverId, currentTitle(watch), run.id, run.error)
        }
    }

    /** Enqueues the durable WorkManager retry; same shape as TitleSyncManager.scheduler. */
    internal var scheduler: () -> Unit = {
        val context = try { PlaudBridgeApp.instance } catch (e: UninitializedPropertyAccessException) { null }
        if (context != null) AutomationWatchScheduler.enqueue(context)
    }

    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** What [watch] does after persisting (test seam: tests drive passes by hand instead). */
    internal var kicker: () -> Unit = { kick() }

    /**
     * Runs after a pass announced something: the list rows carry the server's automations
     * line, so the shared list snapshot is re-read and a visible "Working" turns into the
     * outcome without a pull. Best-effort; test seam.
     */
    internal var onAnnounced: () -> Unit = {
        scope.launch {
            try {
                org.plaudbridge.app.ui.recordings.RecordingsRepository.refresh()
            } catch (t: Throwable) {
                AppLog.w(TAG, "list refresh after announcement failed", t)
            }
        }
    }

    /** One recording being followed. */
    data class Watch(
        val serverId: String,
        /** The phone's index entry, when the recording is one of its own; opens the detail screen. */
        val fileId: String?,
        /** The recording's name when the watch started, for the notification header. */
        val title: String,
        /** Epoch millis when the watch started (phone clock). */
        val since: Long,
        /** Hand-offs that existed before this watch; never announced by it. */
        val knownDeliveryIds: Set<String>,
        /** Router runs that existed before this watch; history, not what it waits for. */
        val knownRunIds: Set<String> = emptySet()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("server_id", serverId)
            .put("file_id", fileId)
            .put("title", title)
            .put("since", since)
            .put("known", JSONArray(knownDeliveryIds.toList()))
            .put("known_runs", JSONArray(knownRunIds.toList()))

        companion object {
            fun fromJson(obj: JSONObject): Watch? {
                val serverId = obj.optString("server_id").takeIf { it.isNotBlank() } ?: return null
                return Watch(
                    serverId = serverId,
                    fileId = if (obj.isNull("file_id")) null else obj.optString("file_id").takeIf { it.isNotBlank() },
                    title = obj.optString("title"),
                    since = obj.optLong("since", 0L),
                    knownDeliveryIds = idSet(obj.optJSONArray("known")),
                    knownRunIds = idSet(obj.optJSONArray("known_runs"))
                )
            }

            private fun idSet(arr: JSONArray?): Set<String> =
                if (arr == null) emptySet() else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
    }

    /** Outcome of one [runPass]. */
    data class PassResult(
        /** Notifications posted during this pass. */
        val announced: Int,
        /** Recordings still being followed after the pass. */
        val watching: Int,
        /** Transient fetch failures; retried later. */
        val failed: Int,
        val alreadyRunning: Boolean = false
    ) {
        /** Work a later pass could still resolve. Drives the worker's retry decision. */
        val remaining: Int get() = watching + failed
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val lock = Any()

    /** The one in-app polling loop, see [kick]. Guarded by [lock]. */
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Polls left in the active loop; every kick refills it so new work gets the full budget. */
    private val pollBudget = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Start (or restart) following [serverId]. [knownDeliveryIds] and [knownRunIds] are the
     * hand-offs and router runs the caller has already seen; they are never announced by this
     * watch and do not count as the run it waits for. Merged into an existing watch for the same
     * recording, which also gets a fresh clock. [forgetDeliveryIds] / [forgetRunIds] take ids
     * back OUT of the merged known sets: a retried hand-off keeps its id (and its run's), so the
     * caller that retried it must make the watch look at it again.
     */
    fun watch(
        serverId: String, fileId: String?, title: String?,
        knownDeliveryIds: Set<String> = emptySet(), knownRunIds: Set<String> = emptySet(),
        forgetDeliveryIds: Set<String> = emptySet(), forgetRunIds: Set<String> = emptySet()
    ) {
        if (!RecordingStore.isServerConfigured) return
        synchronized(lock) {
            val watches = loadWatches().toMutableList()
            val existing = watches.indexOfFirst { it.serverId == serverId }
            val previous = watches.getOrNull(existing)
            val merged = Watch(
                serverId = serverId,
                fileId = fileId ?: previous?.fileId,
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.title ?: "",
                since = clock(),
                knownDeliveryIds = (knownDeliveryIds + (previous?.knownDeliveryIds ?: emptySet())) - forgetDeliveryIds,
                knownRunIds = (knownRunIds + (previous?.knownRunIds ?: emptySet())) - forgetRunIds
            )
            if (existing >= 0) watches[existing] = merged else watches += merged
            saveWatches(watches)
        }
        AppLog.i(TAG, "Watching automations for $serverId")
        kicker()
    }

    /** Stop following [serverId] (the hand-off request it was started for definitely failed). */
    fun unwatch(serverId: String) {
        synchronized(lock) {
            val watches = loadWatches()
            if (watches.any { it.serverId == serverId }) saveWatches(watches.filterNot { it.serverId == serverId })
        }
    }

    /** Recordings currently followed (for tests and the detail screen). */
    fun watched(): List<Watch> = synchronized(lock) { loadWatches() }

    /**
     * Poll now and make sure the durable retry is scheduled. Cheap when nothing is watched:
     * neither the network nor WorkManager is touched. One polling loop at a time: a kick while
     * it runs refills its budget (the next pass reads the fresh watch list) instead of starting
     * a second loop that would double every request.
     */
    fun kick() {
        if (!RecordingStore.isServerConfigured) return
        if (watched().isEmpty()) return
        // KEEP makes this idempotent; calling it on every kick covers a watch that arrives after
        // an earlier worker already finished with nothing left to do.
        scheduler()
        pollBudget.set(MAX_IN_APP_POLLS)
        synchronized(lock) {
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                while (true) {
                    val pass = runPass()
                    if (!pass.alreadyRunning && pass.remaining == 0) break
                    if (pollBudget.decrementAndGet() < 0) break
                    delay(inAppPollDelayMs)
                }
            }
        }
    }

    /**
     * One pass over every watched recording, callable from any coroutine. Returns
     * [PassResult.alreadyRunning] without touching anything when another pass holds the guard;
     * that pass loops once more (dirty flag), so nothing is lost.
     */
    suspend fun runPass(): PassResult {
        dirty.set(true)
        if (!running.compareAndSet(false, true)) {
            return PassResult(announced = 0, watching = watched().size, failed = 0, alreadyRunning = true)
        }
        var announced = 0
        var watching = 0
        var failed = 0
        try {
            while (dirty.getAndSet(false)) {
                val r = processWatches()
                announced += r.announced
                watching = r.watching
                failed = r.failed
            }
        } finally {
            running.set(false)
        }
        if (announced > 0) {
            try {
                onAnnounced()
            } catch (t: Throwable) {
                AppLog.w(TAG, "post-announcement hook failed", t)
            }
        }
        return PassResult(announced = announced, watching = watching, failed = failed)
    }

    private fun processWatches(): PassResult {
        val watches = watched()
        if (watches.isEmpty()) return PassResult(0, 0, 0)
        AppLog.i(TAG, "Checking automations for ${watches.size} recording(s)")
        var announced = 0
        var failed = 0
        val keep = mutableListOf<Watch>()
        val now = clock()
        for (watch in watches) {
            if (now - watch.since > WATCH_TTL_MS) {
                AppLog.w(TAG, "Automations for ${watch.serverId} never settled; no longer watching")
                continue
            }
            val configGen = RecordingStore.serverConfigGeneration
            val result = try {
                routingSource.fetchRouting(watch.serverId)
            } catch (t: Throwable) {
                ApiClient.RoutingResult.Error(t.message ?: "network error")
            }
            if (RecordingStore.serverConfigGeneration != configGen) {
                // Server switched mid-fetch: clearServerState dropped the watches; answer is moot.
                return PassResult(announced, 0, 0)
            }
            when (result) {
                is ApiClient.RoutingResult.Ok -> {
                    val outcome = announce(watch, result.runs)
                    announced += outcome.announced
                    if (!outcome.settled) keep += watch
                }
                ApiClient.RoutingResult.NotFound -> AppLog.w(TAG, "Server has no recording ${watch.serverId}; no longer watching")
                is ApiClient.RoutingResult.AuthError -> {
                    // Every further request in this pass carries the same rejected token.
                    AppLog.w(TAG, "Routing fetch rejected (HTTP ${result.code}); aborting pass")
                    keep += watch
                    keep += watches.drop(watches.indexOf(watch) + 1)
                    break
                }
                is ApiClient.RoutingResult.Error -> {
                    failed++
                    keep += watch
                    AppLog.w(TAG, "Routing fetch failed for ${watch.serverId}: ${result.message}")
                }
            }
        }
        val saved = synchronized(lock) {
            // Merge against the list as it is NOW: a watch added or refreshed while this pass
            // was on the wire (a new transcript, a rerun) wins over what this pass decided about
            // the stale copy it read; everything else follows this pass's keep/drop verdict.
            val processed = watches.associateBy { it.serverId }
            val current = loadWatches()
            val result = current.filter { w ->
                val before = processed[w.serverId]
                before == null || before.since != w.since || keep.any { it.serverId == w.serverId }
            }
            saveWatches(result)
            result
        }
        return PassResult(announced = announced, watching = saved.size, failed = failed)
    }

    private data class Outcome(val announced: Int, val settled: Boolean)

    /**
     * Announce what is new in [runs] for [watch] and decide whether the watch is over.
     *
     * Runs (and hand-offs) the caller already knew are never announced and never count. Every
     * other outcome not announced before is announced, however old its run looks: dedupe by id
     * is what stops repeats, not the clock. The clock decides only what the watch is WAITING
     * for: a run newer than the watch (minus slack), or an older one that still yielded news,
     * is the run being waited for; an old run that had nothing new to say is history, and the
     * watch keeps polling for the run that has not appeared yet. Settled once a waited-for run
     * exists and none of its hand-offs is still working.
     */
    private fun announce(watch: Watch, runs: List<RoutingRun>): Outcome {
        var announced = 0
        var relevantRuns = 0
        var inProgress = 0
        val announcedIds = loadAnnounced().toMutableList()
        for (run in runs) {
            if (run.id in watch.knownRunIds) continue
            val fresh = run.deliveries.filter { it.id !in watch.knownDeliveryIds }
            if (run.deliveries.isNotEmpty() && fresh.isEmpty()) continue
            // Hand-offs no run claims (rows from before runs were recorded) sit under one
            // synthetic run: their outcomes count (a retried legacy hand-off is followed like
            // any other), the run itself has nothing to say.
            val synthetic = run.id == RoutingRun.UNATTRIBUTED_RUN_ID
            val old = run.createdAt != null && run.createdAt < watch.since - OLD_RUN_SLACK_MS
            var news = 0
            var working = 0
            var seenBefore = 0
            for (delivery in fresh) {
                if (delivery.isInProgress || !isTerminal(delivery)) {
                    working++
                    continue
                }
                // A retry keeps the delivery id and bumps its attempt count, so each attempt's
                // outcome is its own announcement ("failed", then "done" after the retry).
                val key = announcementKey(delivery)
                if (key in announcedIds) {
                    seenBefore++
                    continue
                }
                try {
                    notifier.finished(watch, delivery)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "announcing ${delivery.id} failed", t)
                }
                announcedIds += key
                news++
            }
            if (!synthetic && run.error == null) {
                // The server inserts a hand-off row as each matched route's action starts, so a
                // matched route without a row yet is work still to come, not silence.
                working += run.routes.count { route -> run.deliveries.none { it.routeName == route.name } }
            }
            if (!synthetic && run.deliveries.isEmpty() && (run.routes.isEmpty() || run.error != null)) {
                // The router ran and applied nothing (or could not run): say so, once per run.
                val key = "r:" + run.id
                if (key in announcedIds) {
                    seenBefore++
                } else {
                    try {
                        notifier.skipped(watch, run)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "announcing run ${run.id} failed", t)
                    }
                    announcedIds += key
                    news++
                }
            }
            announced += news
            // The run this watch is waiting for is one that still has something to tell: news
            // now, or work in progress. A run whose every outcome was announced before this
            // watch began is history, however recent (a re-transcribe minutes after the last
            // run), and the watch keeps waiting for the run that has not appeared yet. A run
            // that is not old and has said nothing yet (rows still coming) is waited on too.
            val fullyAnnounced = news == 0 && working == 0 && seenBefore > 0
            if (news > 0 || working > 0 || (!old && !fullyAnnounced && !synthetic)) {
                relevantRuns++
                inProgress += working
            }
        }
        if (announced > 0) saveAnnounced(announcedIds)
        return Outcome(announced, settled = relevantRuns > 0 && inProgress == 0)
    }

    /** Dedupe key of one hand-off attempt's outcome. */
    internal fun announcementKey(delivery: Delivery): String = "d:" + delivery.id + "#" + delivery.attempts

    /**
     * The hand-off has an outcome to announce: the agent reported (done, failed, or the server
     * gave up waiting), the hand-off itself failed, or it is an action that completes on the
     * server without a report (markdown, none) and went through.
     */
    internal fun isTerminal(delivery: Delivery): Boolean {
        if (delivery.resultStatus in setOf(Delivery.RESULT_DONE, Delivery.RESULT_FAILED, Delivery.RESULT_UNKNOWN)) return true
        if (delivery.status == Delivery.STATUS_FAILED) return true
        return delivery.status == Delivery.STATUS_OK && delivery.resultStatus == null && delivery.actionType != Delivery.ACTION_WEBHOOK
    }

    /** The recording's current name (a rename or a title that arrived later wins over the snapshot). */
    internal fun currentTitle(watch: Watch): String {
        val file = try {
            RecordingStore.allFiles.firstOrNull { it.id == watch.fileId } ?: RecordingStore.allFiles.firstOrNull { it.serverId == watch.serverId }
        } catch (t: Throwable) {
            null
        }
        return file?.displayName?.takeIf { it.isNotBlank() } ?: watch.title
    }

    // MARK: - persistence

    private fun loadWatches(): List<Watch> {
        val raw = RecordingStore.watchedAutomationsJson ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Watch.fromJson(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveWatches(watches: List<Watch>) {
        RecordingStore.watchedAutomationsJson = if (watches.isEmpty()) null else JSONArray(watches.map { it.toJson() }).toString()
    }

    private fun loadAnnounced(): List<String> {
        val raw = RecordingStore.announcedAutomationsJson ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAnnounced(ids: List<String>) {
        RecordingStore.announcedAutomationsJson = JSONArray(ids.takeLast(ANNOUNCED_LIMIT)).toString()
    }

    /** Test hook: forget every watch and announcement, and stop a polling loop a test left running. */
    internal fun resetForTest() {
        val job = synchronized(lock) { pollJob.also { pollJob = null } }
        if (job != null) {
            job.cancel()
            kotlinx.coroutines.runBlocking { job.join() }
        }
        RecordingStore.watchedAutomationsJson = null
        RecordingStore.announcedAutomationsJson = null
    }
}
