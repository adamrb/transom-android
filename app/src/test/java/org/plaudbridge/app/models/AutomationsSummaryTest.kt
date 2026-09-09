package org.plaudbridge.app.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationsSummaryTest {

    @Test
    fun parsesTheServerShape() {
        val s = AutomationsSummary.fromJson(JSONObject(
            """{"state":"working","line":"Work meetings: Created: x.md · Ask Claude: Working",
                "items":[{"route_name":"Work meetings","state":"done","summary":"Created: x.md"},
                         {"route_name":"Ask Claude","state":"working","summary":"Working"}],
                "run_id":"run-1","run_at":"2026-09-09T15:28:33Z"}"""
        ))!!
        assertEquals("working", s.state)
        assertTrue(s.isWorking)
        assertFalse(s.isFailure)
        assertEquals(2, s.items.size)
        assertEquals("Ask Claude", s.items[1].routeName)
        assertEquals("run-1", s.runId)
        assertEquals(ServerRecording.parseIso("2026-09-09T15:28:33Z"), s.runAt)
    }

    @Test
    fun nullOrShapelessIsNull() {
        assertNull(AutomationsSummary.fromJson(null))
        assertNull(AutomationsSummary.fromJson(JSONObject("""{"line":"x"}""")))
        val rec = ServerRecording.fromJson(JSONObject("""{"id":"a","filename":"a.mp3","status":"done","automations":null}"""))
        assertNull(rec.automations)
    }

    @Test
    fun failureStates() {
        assertTrue(AutomationsSummary.fromJson(JSONObject("""{"state":"failed","line":"x"}"""))!!.isFailure)
        assertTrue(AutomationsSummary.fromJson(JSONObject("""{"state":"unknown","line":"x"}"""))!!.isFailure)
        assertFalse(AutomationsSummary.fromJson(JSONObject("""{"state":"skipped","line":"No automation matched"}"""))!!.isFailure)
    }

    @Test
    fun recordingCarriesIt() {
        val rec = ServerRecording.fromJson(JSONObject(
            """{"id":"a","filename":"a.mp3","status":"done","automations":{"state":"done","line":"Vault notes: Filed: y.md","items":[]}}"""
        ))
        assertEquals("Vault notes: Filed: y.md", rec.automations?.line)
    }
}
