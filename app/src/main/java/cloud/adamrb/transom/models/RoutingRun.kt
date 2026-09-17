package cloud.adamrb.transom.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * One route the server's AI router picked for a recording ("obsidian-inbox", "meetings",
 * "ask-claude") and the router's one-paragraph justification.
 */
data class MatchedRoute(
    val name: String,
    val reason: String?
)

/**
 * One hand-off of a recording to an automation, as the server records it. Two layers of state
 * on purpose: [status] is the hand-off itself (did the webhook fire, did the agent runner accept
 * the job), [resultStatus] and [resultSummary] are what the agent reported back afterwards, and
 * stay null until (and unless) it does. The screen prefers the result when there is one, because
 * "the note was saved" is what the user wants to know, not "the webhook returned 200".
 */
data class Delivery(
    val id: String,
    val routerRunId: String?,
    val routeName: String,
    /** webhook, markdown, none (anything else is tolerated and shown as a plain hand-off). */
    val actionType: String,
    /** ok, failed, pending. */
    val status: String,
    val attempts: Int,
    val lastError: String?,
    /** Epoch millis, or null when unparseable. */
    val createdAt: Long?,
    /**
     * queued, done, failed, unknown (the server's verdict on a queued job that never reported
     * within its deadline), or null while the agent has not reported.
     */
    val resultStatus: String?,
    val resultSummary: String?,
    val resultAt: Long?
) {
    /** The agent is still working (or the hand-off has not happened yet): worth polling for. */
    val isInProgress: Boolean
        get() = resultStatus == RESULT_QUEUED || (resultStatus == null && status == STATUS_PENDING)

    /**
     * Retry applies, mirroring the server's rule: the hand-off is failed (an agent-reported
     * failure flips it too), or the job went quiet past its deadline and is stale.
     */
    val canRetry: Boolean
        get() = status == STATUS_FAILED || resultStatus == RESULT_FAILED || resultStatus == RESULT_UNKNOWN

    /** When the line's state last changed: the agent's report when there is one, else the hand-off. */
    val effectiveAt: Long? get() = resultAt ?: createdAt

    companion object {
        const val STATUS_OK = "ok"
        const val ACTION_WEBHOOK = "webhook"
        const val STATUS_FAILED = "failed"
        const val STATUS_PENDING = "pending"
        const val RESULT_QUEUED = "queued"
        const val RESULT_DONE = "done"
        const val RESULT_FAILED = "failed"
        const val RESULT_UNKNOWN = "unknown"

        /** Parse one delivery. Throws on a missing/blank id; everything else has a default. */
        fun fromJson(obj: JSONObject): Delivery {
            val id = obj.opt("id") as? String
            require(!id.isNullOrBlank()) { "delivery id must be a non-blank string" }
            return Delivery(
                id = id,
                routerRunId = obj.optStringOrNull("router_run_id"),
                routeName = obj.optStringOrNull("route_name") ?: "",
                actionType = obj.optStringOrNull("action_type") ?: "",
                status = obj.optStringOrNull("status") ?: "",
                attempts = obj.optInt("attempts", 0),
                lastError = obj.optStringOrNull("last_error")?.takeIf { it.isNotBlank() },
                createdAt = ServerRecording.parseIso(obj.optStringOrNull("created_at")),
                resultStatus = obj.optStringOrNull("result_status")?.takeIf { it.isNotBlank() },
                resultSummary = obj.optStringOrNull("result_summary")?.takeIf { it.isNotBlank() },
                resultAt = ServerRecording.parseIso(obj.optStringOrNull("result_at"))
            )
        }
    }
}

/**
 * One pass of the server's AI router over a recording: which routes matched (possibly none),
 * why, and the deliveries it produced. GET /api/v1/recordings/{id}/routing returns these
 * newest first.
 *
 * Pure Kotlin plus org.json so the mapping is unit-testable; no android.* imports.
 */
data class RoutingRun(
    val id: String,
    val recordingId: String?,
    /** Epoch millis, or null when unparseable. */
    val createdAt: Long?,
    val model: String?,
    /** Set when the router itself failed (model error, bad JSON); the routes are then empty. */
    val error: String?,
    val routes: List<MatchedRoute>,
    val deliveries: List<Delivery>,
    /** What the user told the automations to do when starting this run by hand; null otherwise. */
    val instructions: String? = null
) {
    val hasInProgressDelivery: Boolean get() = deliveries.any { it.isInProgress }

    /** Deliveries for one route, in server order. */
    fun deliveriesFor(routeName: String): List<Delivery> = deliveries.filter { it.routeName == routeName }

    companion object {
        /**
         * Parse one run. [fallbackDeliveries] is the response's top-level delivery list; used only
         * when the run carries no `deliveries` array of its own, so either server shape works.
         * Deliveries that fail to parse are dropped, not fatal.
         */
        fun fromJson(obj: JSONObject, fallbackDeliveries: List<Delivery> = emptyList()): RoutingRun {
            val id = obj.opt("id") as? String
            require(!id.isNullOrBlank()) { "run id must be a non-blank string" }
            val decision = obj.optJSONObject("decision")
            val routesArr = decision?.optJSONArray("routes")
            val routes = if (routesArr == null) emptyList() else (0 until routesArr.length()).mapNotNull { i ->
                val route = routesArr.optJSONObject(i) ?: return@mapNotNull null
                val name = route.optStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MatchedRoute(name, route.optStringOrNull("reason")?.takeIf { it.isNotBlank() })
            }
            // Union of the run's own list and the top-level rows that name it, deduplicated by
            // id, so neither an absent nor an empty nested array can hide a delivery.
            val own = obj.optJSONArray("deliveries")?.let { deliveryList(it) } ?: emptyList()
            val deliveries = (own + fallbackDeliveries.filter { it.routerRunId == id }).distinctBy { it.id }
            return RoutingRun(
                id = id,
                recordingId = obj.optStringOrNull("recording_id"),
                createdAt = ServerRecording.parseIso(obj.optStringOrNull("created_at")),
                model = obj.optStringOrNull("model"),
                error = obj.optStringOrNull("error")?.takeIf { it.isNotBlank() },
                routes = routes,
                deliveries = deliveries,
                instructions = obj.optStringOrNull("instructions")?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        /** Id of the synthetic run that carries deliveries no run claims, see [listFromJson]. */
        const val UNATTRIBUTED_RUN_ID = "deliveries-without-run"

        /**
         * The `runs` of a routing response, newest first. Rows that fail to parse are dropped
         * rather than failing the whole section. The server already orders newest first; sorting
         * here (stable, so ties keep the server's order) only guards against a server that does
         * not. Top-level deliveries that no run claims (rows from before runs were recorded have
         * a null router_run_id) are kept under one synthetic run rather than dropped: a failed
         * hand-off and its Retry must not vanish because of a schema migration.
         */
        fun listFromJson(json: String): List<RoutingRun> {
            val root = JSONObject(json)
            val topLevel = root.optJSONArray("deliveries")?.let { deliveryList(it) } ?: emptyList()
            val arr: JSONArray? = root.optJSONArray("runs")
            val runs = if (arr == null) mutableListOf() else (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                try { fromJson(obj, topLevel) } catch (e: Exception) { null }
            }.toMutableList()
            val claimed = runs.flatMap { it.deliveries }.map { it.id }.toSet()
            val unattributed = topLevel.filter { it.id !in claimed }
            if (unattributed.isNotEmpty()) {
                runs += RoutingRun(
                    id = UNATTRIBUTED_RUN_ID,
                    recordingId = null,
                    createdAt = unattributed.mapNotNull { it.createdAt }.maxOrNull(),
                    model = null,
                    error = null,
                    routes = emptyList(),
                    deliveries = unattributed
                )
            }
            return runs.sortedByDescending { it.createdAt ?: Long.MIN_VALUE }
        }

        private fun deliveryList(arr: JSONArray): List<Delivery> = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            try { Delivery.fromJson(obj) } catch (e: Exception) { null }
        }
    }
}

/** optString returns "null" for JSON null; this returns Kotlin null instead. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() || has(key) }
