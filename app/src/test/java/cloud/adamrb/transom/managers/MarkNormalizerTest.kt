package cloud.adamrb.transom.managers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MarkNormalizer: every branch of the unit heuristic (epoch ms, epoch s, ms offset, s offset,
 * drop), then the post-processing (negatives dropped, duplicates collapsed, ascending order).
 * The session id is a real Note Pro one (epoch seconds of the recording start).
 */
class MarkNormalizerTest {

    private val session = 1_788_758_851L // 2026-09-07T05:27:31Z
    private val duration = 600L

    private fun norm(vararg raw: Long, dur: Long = duration) =
        MarkNormalizer.toOffsetsSeconds(raw.toList(), session, dur)

    @Test
    fun epochMillisecondsBecomeOffsetsFromSessionStart() {
        assertEquals(listOf(6.0, 125.5), norm(session * 1000 + 6_000, session * 1000 + 125_500))
    }

    @Test
    fun epochSecondsInsideTheRecordingBecomeOffsets() {
        assertEquals(listOf(0.0, 42.0, 600.0), norm(session, session + 42, session + duration))
    }

    @Test
    fun epochSecondsWithinSlackAfterTheEndAreKept() {
        // The stored duration can undershoot the device clock; 120 s of slack absorbs that.
        assertEquals(listOf((duration + 120).toDouble()), norm(session + duration + 120))
    }

    @Test
    fun epochSecondsBeyondSlackAreDropped() {
        // Neither inside the recording nor plausible as a ms offset (~1.79e9 > 720 * 1000).
        assertEquals(emptyList<Double>(), norm(session + duration + 121))
    }

    @Test
    fun millisecondOffsetsAreScaled() {
        // 721 is just above the seconds window (600 + 120), so it must be milliseconds.
        assertEquals(listOf(0.721, 90.0, 720.0), norm(721, 90_000, 720_000))
    }

    @Test
    fun millisecondOffsetUpperBoundIsWindowTimesThousand() {
        assertEquals(emptyList<Double>(), norm(720_001))
    }

    @Test
    fun secondOffsetsPassThrough() {
        assertEquals(listOf(0.0, 7.0, 720.0), norm(0, 7, 720))
    }

    @Test
    fun negativeValuesAreDropped() {
        assertEquals(emptyList<Double>(), norm(-1, -600))
    }

    @Test
    fun epochMillisBeforeSessionStartIsDroppedAsNegative() {
        assertEquals(emptyList<Double>(), norm(session * 1000 - 1_000))
    }

    @Test
    fun duplicatesCollapseAndOrderIsAscending() {
        // Same moment expressed as seconds offset, ms offset and epoch seconds: one entry.
        assertEquals(listOf(5.0, 30.0), norm(30, 5, session + 5, 5, 30_000))
    }

    @Test
    fun zeroDurationStillClassifiesEpochValues() {
        // Duration unknown (0): the window is just the slack, which is enough for epoch values
        // near the start; larger offsets cannot be told apart and are dropped.
        assertEquals(listOf(3.0), norm(session + 3, dur = 0))
        assertEquals(listOf(4.0), norm(session * 1000 + 4_000, dur = 0))
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(emptyList<Double>(), norm())
    }
}
