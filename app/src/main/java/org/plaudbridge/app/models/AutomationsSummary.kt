package org.plaudbridge.app.models

import org.json.JSONObject

/**
 * The server's one-line answer to "what did the automations do with this recording?" (the
 * `automations` field of a recording, null when the router never ran for it). The detail
 * screen has the full history (RoutingRun); list rows show [line], coloured by [state].
 *
 * Pure Kotlin plus org.json so the mapping is unit-testable; no android.* imports.
 */
data class AutomationsSummary(
    /** working, done, failed, unknown (never reported), skipped (nothing matched). */
    val state: String,
    /** "Vault notes: Filed: Life/Topics/Dogs.md", or "No automation matched". */
    val line: String,
    val items: List<Item>,
    val runId: String?,
    /** Epoch millis of the run, or null when unparseable. */
    val runAt: Long?
) {
    data class Item(val routeName: String, val state: String, val summary: String)

    val isWorking: Boolean get() = state == STATE_WORKING
    val isFailure: Boolean get() = state == STATE_FAILED || state == STATE_UNKNOWN

    companion object {
        const val STATE_WORKING = "working"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        const val STATE_UNKNOWN = "unknown"
        const val STATE_SKIPPED = "skipped"

        /** Parse the field; null for JSON null, a missing object, or one without a state. */
        fun fromJson(obj: JSONObject?): AutomationsSummary? {
            if (obj == null) return null
            val state = obj.optStringOrNull("state")?.takeIf { it.isNotBlank() } ?: return null
            val arr = obj.optJSONArray("items")
            val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    routeName = it.optStringOrNull("route_name") ?: "",
                    state = it.optStringOrNull("state") ?: state,
                    summary = it.optStringOrNull("summary") ?: ""
                )
            }
            return AutomationsSummary(
                state = state,
                line = obj.optStringOrNull("line") ?: "",
                items = items,
                runId = obj.optStringOrNull("run_id"),
                runAt = ServerRecording.parseIso(obj.optStringOrNull("run_at"))
            )
        }
    }
}
