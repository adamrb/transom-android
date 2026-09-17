package cloud.adamrb.transom.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy behind DeviceConnectionService: when it should run, and how the reconnect backoff
 * schedule grows. No Android runtime involved.
 */
class BackgroundSyncPolicyTest {

    // MARK: - shouldRun

    @Test
    fun runsOnlyWhenPairedConfiguredAndEnabled() {
        assertTrue(BackgroundSyncPolicy.shouldRun(paired = true, userConfigured = true, enabled = true))
    }

    @Test
    fun doesNotRunWithoutAPairedDevice() {
        assertFalse(BackgroundSyncPolicy.shouldRun(paired = false, userConfigured = true, enabled = true))
    }

    @Test
    fun doesNotRunWithoutAUserId() {
        // No user id means the SDK cannot handshake, so scanning would be pointless.
        assertFalse(BackgroundSyncPolicy.shouldRun(paired = true, userConfigured = false, enabled = true))
    }

    @Test
    fun doesNotRunWhenTheUserTurnedItOff() {
        assertFalse(BackgroundSyncPolicy.shouldRun(paired = true, userConfigured = true, enabled = false))
    }

    @Test
    fun everyOtherCombinationIsOff() {
        val combos = listOf(true, false)
        for (p in combos) for (u in combos) for (e in combos) {
            val expected = p && u && e
            assertEquals("paired=$p configured=$u enabled=$e", expected, BackgroundSyncPolicy.shouldRun(p, u, e))
        }
    }

    // MARK: - reconnect backoff

    @Test
    fun scheduleDoublesFromFifteenSecondsAndCapsAtFiveMinutes() {
        val expected = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L)
        assertEquals(expected, BackgroundSyncPolicy.reconnectSchedule().take(expected.size).toList())
    }

    @Test
    fun firstAttemptUsesInitialBackoff() {
        assertEquals(BackgroundSyncPolicy.INITIAL_BACKOFF_MS, BackgroundSyncPolicy.reconnectDelayMs(0))
    }

    @Test
    fun negativeAttemptIsTreatedAsFirst() {
        assertEquals(BackgroundSyncPolicy.INITIAL_BACKOFF_MS, BackgroundSyncPolicy.reconnectDelayMs(-3))
    }

    @Test
    fun scheduleNeverDecreasesAndNeverExceedsCap() {
        var previous = 0L
        BackgroundSyncPolicy.reconnectSchedule().take(50).forEach { delay ->
            assertTrue("delay $delay < previous $previous", delay >= previous)
            assertTrue("delay $delay above cap", delay <= BackgroundSyncPolicy.MAX_BACKOFF_MS)
            previous = delay
        }
    }

    @Test
    fun hugeAttemptCountsDoNotOverflow() {
        assertEquals(BackgroundSyncPolicy.MAX_BACKOFF_MS, BackgroundSyncPolicy.reconnectDelayMs(Int.MAX_VALUE))
        assertEquals(BackgroundSyncPolicy.MAX_BACKOFF_MS, BackgroundSyncPolicy.reconnectDelayMs(63))
        assertEquals(BackgroundSyncPolicy.MAX_BACKOFF_MS, BackgroundSyncPolicy.reconnectDelayMs(64))
    }
}
