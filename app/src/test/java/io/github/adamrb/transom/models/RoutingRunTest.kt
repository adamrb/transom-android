package io.github.adamrb.transom.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JSON mapping of the routing endpoint: runs with nested deliveries, the top-level delivery list
 * as a fallback, nulls, unknown fields, and the in-progress / failed semantics the screen and its
 * polling rely on.
 */
@RunWith(RobolectricTestRunner::class)
class RoutingRunTest {

    private val sample = """{"runs":[{"id":"run-1","recording_id":"rec-1","created_at":"2026-09-07T06:39:00Z",
        "model":"claude-acp","error":null,
        "decision":{"routes":[{"name":"meetings","reason":"The speaker explicitly directs how this recording should be filed."}]},
        "deliveries":[{"id":"d-1","router_run_id":"run-1","route_name":"meetings","action_type":"markdown","status":"ok",
            "attempts":1,"last_error":null,"created_at":"2026-09-07T06:39:05Z","result_status":"done",
            "result_summary":"Saved to Inbox/Garage Inventory Note Request.md","result_at":"2026-09-07T06:40:10.5Z",
            "payload":{"ignored":true},"some_future_field":42}]}],
        "deliveries":[]}"""

    @Test
    fun mapsTheSampleResponse() {
        val runs = RoutingRun.listFromJson(sample)
        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals("run-1", run.id)
        assertEquals("rec-1", run.recordingId)
        assertEquals(ServerRecording.parseIso("2026-09-07T06:39:00Z"), run.createdAt)
        assertEquals("claude-acp", run.model)
        assertNull(run.error)
        assertEquals(listOf(MatchedRoute("meetings", "The speaker explicitly directs how this recording should be filed.")), run.routes)
        assertEquals(1, run.deliveries.size)
        val d = run.deliveries[0]
        assertEquals("d-1", d.id)
        assertEquals("run-1", d.routerRunId)
        assertEquals("meetings", d.routeName)
        assertEquals("markdown", d.actionType)
        assertEquals("ok", d.status)
        assertEquals(1, d.attempts)
        assertNull(d.lastError)
        assertEquals(ServerRecording.parseIso("2026-09-07T06:39:05Z"), d.createdAt)
        assertEquals("done", d.resultStatus)
        assertEquals("Saved to Inbox/Garage Inventory Note Request.md", d.resultSummary)
        assertEquals(ServerRecording.parseIso("2026-09-07T06:40:10.5Z"), d.resultAt)
        assertEquals(d.resultAt, d.effectiveAt)
        assertFalse(d.isInProgress)
        assertFalse(d.canRetry)
        assertFalse(run.hasInProgressDelivery)
        assertEquals(listOf(d), run.deliveriesFor("meetings"))
        assertTrue(run.deliveriesFor("ask-claude").isEmpty())
    }

    @Test
    fun topLevelDeliveriesAttachToTheirRunWhenTheRunCarriesNone() {
        val runs = RoutingRun.listFromJson(
            """{"runs":[{"id":"run-1","created_at":"2026-09-07T06:39:00Z","decision":{"routes":[{"name":"ask-claude"}]}},
                       {"id":"run-0","created_at":"2026-09-07T05:00:00Z","decision":{"routes":[]}}],
                "deliveries":[{"id":"d-1","router_run_id":"run-1","route_name":"ask-claude","status":"ok","result_status":"queued"},
                              {"id":"d-0","router_run_id":"run-0","route_name":"x","status":"ok"},
                              {"id":"d-x","router_run_id":"other","route_name":"x","status":"ok"}]}"""
        )
        // d-x names a run the response does not contain: kept, under the synthetic run.
        assertEquals(listOf("run-1", "run-0", RoutingRun.UNATTRIBUTED_RUN_ID), runs.map { it.id })
        assertEquals(listOf("d-1"), runs[0].deliveries.map { it.id })
        assertEquals(listOf("d-0"), runs[1].deliveries.map { it.id })
        assertEquals(listOf("d-x"), runs[2].deliveries.map { it.id })
        assertEquals(listOf(MatchedRoute("ask-claude", null)), runs[0].routes)
        assertTrue(runs[0].hasInProgressDelivery)
    }

    @Test
    fun emptyNestedListStillPicksUpTopLevelRowsThatNameTheRun() {
        val runs = RoutingRun.listFromJson(
            """{"runs":[{"id":"run-1","deliveries":[{"id":"d-a","router_run_id":"run-1","route_name":"m","status":"ok"}]}],
                "deliveries":[{"id":"d-a","router_run_id":"run-1","route_name":"m","status":"ok"},
                              {"id":"d-b","router_run_id":"run-1","route_name":"m","status":"failed"}]}"""
        )
        assertEquals(listOf("d-a", "d-b"), runs.single().deliveries.map { it.id })
    }

    @Test
    fun deliveriesNoRunClaimsAreKeptUnderASyntheticRun() {
        // Rows from before runs were recorded carry no router_run_id; a failed one must keep its Retry.
        val runs = RoutingRun.listFromJson(
            """{"runs":[{"id":"run-1","created_at":"2026-09-07T06:00:00Z","decision":{"routes":[]},"deliveries":[]}],
                "deliveries":[{"id":"old","router_run_id":null,"route_name":"obsidian-inbox","status":"failed",
                               "last_error":"502","created_at":"2026-09-01T10:00:00Z"}]}"""
        )
        assertEquals(listOf("run-1", RoutingRun.UNATTRIBUTED_RUN_ID), runs.map { it.id })
        val legacy = runs[1]
        assertEquals(ServerRecording.parseIso("2026-09-01T10:00:00Z"), legacy.createdAt)
        assertTrue(legacy.routes.isEmpty())
        assertEquals(listOf("old"), legacy.deliveries.map { it.id })
        assertTrue(legacy.deliveries[0].canRetry)
        // Without any runs at all the synthetic one is the whole list.
        val only = RoutingRun.listFromJson("""{"runs":[],"deliveries":[{"id":"x","route_name":"m","status":"ok"}]}""")
        assertEquals(listOf(RoutingRun.UNATTRIBUTED_RUN_ID), only.map { it.id })
    }

    @Test
    fun newestRunComesFirstEvenIfTheServerSentThemTheOtherWay() {
        val runs = RoutingRun.listFromJson(
            """{"runs":[{"id":"old","created_at":"2026-09-07T05:00:00Z"},{"id":"new","created_at":"2026-09-07T06:00:00Z"},
                        {"id":"undated"}]}"""
        )
        assertEquals(listOf("new", "old", "undated"), runs.map { it.id })
    }

    @Test
    fun nullsAndMissingPiecesAreTolerated() {
        val run = RoutingRun.fromJson(
            JSONObject("""{"id":"r","created_at":null,"model":null,"error":"router: model timed out","decision":null,
                "deliveries":[{"id":"d","route_name":null,"action_type":null,"status":"failed","last_error":"boom",
                               "result_status":null,"result_summary":null,"result_at":null,"created_at":"garbage"}]}""")
        )
        assertNull(run.createdAt)
        assertNull(run.model)
        assertEquals("router: model timed out", run.error)
        assertTrue(run.routes.isEmpty())
        val d = run.deliveries.single()
        assertEquals("", d.routeName)
        assertEquals("", d.actionType)
        assertNull(d.createdAt)
        assertNull(d.resultStatus)
        assertNull(d.effectiveAt)
        assertEquals("boom", d.lastError)
        assertTrue(d.canRetry)
        assertFalse(d.isInProgress)
    }

    @Test
    fun instructionsMapWhenPresentAndReadAsAbsentOtherwise() {
        assertNull(RoutingRun.listFromJson(sample).single().instructions)
        val given = RoutingRun.fromJson(JSONObject("""{"id":"r","instructions":"  file this as a work meeting  "}"""))
        assertEquals("file this as a work meeting", given.instructions)
        assertNull(RoutingRun.fromJson(JSONObject("""{"id":"r","instructions":null}""")).instructions)
        assertNull(RoutingRun.fromJson(JSONObject("""{"id":"r","instructions":"   "}""")).instructions)
        // The synthetic run for unclaimed deliveries carries none.
        val only = RoutingRun.listFromJson("""{"runs":[],"deliveries":[{"id":"x","route_name":"m","status":"ok"}]}""")
        assertNull(only.single().instructions)
    }

    @Test
    fun blankErrorAndBlankSummaryReadAsAbsent() {
        val run = RoutingRun.fromJson(
            JSONObject("""{"id":"r","error":"","deliveries":[{"id":"d","route_name":"m","status":"ok","result_status":"","result_summary":"  "}]}""")
        )
        assertNull(run.error)
        assertNull(run.deliveries[0].resultStatus)
        assertNull(run.deliveries[0].resultSummary)
    }

    @Test
    fun rowsWithoutIdsAreDroppedNotFatal() {
        val runs = RoutingRun.listFromJson(
            """{"runs":[{"id":"ok","deliveries":[{"route_name":"m"},{"id":"d","route_name":"m"}]},{"no_id":true},{"id":""},"junk"]}"""
        )
        assertEquals(listOf("ok"), runs.map { it.id })
        assertEquals(listOf("d"), runs[0].deliveries.map { it.id })
        assertTrue(RoutingRun.listFromJson("""{"deliveries":[]}""").isEmpty())
        assertTrue(RoutingRun.listFromJson("""{"runs":null}""").isEmpty())
    }

    @Test
    fun routesWithoutANameAreSkipped() {
        val run = RoutingRun.fromJson(
            JSONObject("""{"id":"r","decision":{"routes":[{"reason":"nameless"},{"name":"","reason":"blank"},{"name":"ok","reason":""}]}}""")
        )
        assertEquals(listOf(MatchedRoute("ok", null)), run.routes)
    }

    @Test
    fun progressAndFailureFollowTheResultFirstThenTheHandOff() {
        fun d(status: String, result: String?) = Delivery.fromJson(
            JSONObject("""{"id":"d","route_name":"m","status":"$status","result_status":${result?.let { "\"$it\"" } ?: "null"}}""")
        )
        // The agent's report wins over the hand-off status for progress.
        assertTrue(d("ok", "queued").isInProgress)
        assertFalse(d("failed", "done").isInProgress)
        assertFalse(d("ok", null).isInProgress)
        // Without a report, the hand-off status stands in.
        assertTrue(d("pending", null).isInProgress)
        // Retry mirrors the server: a failed hand-off (which an agent-reported failure also
        // sets), or a job that went quiet past its deadline. Never a finished or working one.
        assertTrue(d("failed", "failed").canRetry)
        assertTrue(d("failed", null).canRetry)
        assertTrue(d("ok", "unknown").canRetry)
        assertFalse(d("ok", "unknown").isInProgress)
        assertFalse(d("ok", "queued").canRetry)
        assertFalse(d("ok", "done").canRetry)
        assertFalse(d("ok", null).canRetry)
    }
}
