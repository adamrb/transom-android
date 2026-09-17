package io.github.adamrb.transom.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall watchdog behind the sync timeout: fires once when nothing re-arms it within the
 * timeout, starts over instead of firing while local work is running, and never fires after
 * being disarmed. This is what turns a sync with no answer from the recorder into a Failed
 * state (and so a cleared banner plus a Retry) rather than a banner that stays up for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StallWatchdogTest {

    @Test
    fun firesOnceAfterTheTimeoutWithNoReArm() = runTest {
        var stalls = 0
        val watchdog = StallWatchdog(backgroundScope, timeoutMs = 1_000) { stalls++ }
        watchdog.arm()
        advanceTimeBy(999)
        assertEquals(0, stalls)
        assertTrue(watchdog.isArmed)
        advanceTimeBy(2)
        assertEquals(1, stalls)
        assertFalse(watchdog.isArmed)
        advanceTimeBy(5_000)
        assertEquals(1, stalls)
    }

    @Test
    fun reArmingRestartsTheCountdown() = runTest {
        var stalls = 0
        val watchdog = StallWatchdog(backgroundScope, timeoutMs = 1_000) { stalls++ }
        watchdog.arm()
        advanceTimeBy(800)
        watchdog.arm() // progress arrived
        advanceTimeBy(800)
        assertEquals(0, stalls)
        advanceTimeBy(201)
        assertEquals(1, stalls)
    }

    @Test
    fun disarmCancelsIt() = runTest {
        var stalls = 0
        val watchdog = StallWatchdog(backgroundScope, timeoutMs = 1_000) { stalls++ }
        watchdog.arm()
        watchdog.disarm()
        advanceTimeBy(5_000)
        assertEquals(0, stalls)
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun localWorkPostponesTheVerdictUntilItEnds() = runTest {
        var stalls = 0
        var transcoding = true
        val watchdog = StallWatchdog(backgroundScope, timeoutMs = 1_000, isBusyLocally = { transcoding }) { stalls++ }
        watchdog.arm()
        // Two full timeouts of silence while the SDK transcodes: expected quiet, no failure.
        advanceTimeBy(2_500)
        assertEquals(0, stalls)
        assertTrue(watchdog.isArmed)
        transcoding = false
        // The next check after the local work ends is the one that counts.
        advanceTimeBy(1_000)
        assertEquals(1, stalls)
    }
}
