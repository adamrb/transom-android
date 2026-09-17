package cloud.adamrb.transom.service

/**
 * Pure decisions behind the background-sync foreground service, kept free of Android types so
 * they can be unit-tested on the JVM. The service, the boot receiver and the Settings toggle all
 * route through [shouldRun] so there is exactly one definition of "the service belongs running".
 */
object BackgroundSyncPolicy {

    /** First retry delay after the link drops; the recorder usually reappears quickly. */
    const val INITIAL_BACKOFF_MS = 15_000L

    /** Longest pause between reconnect attempts while the device stays out of range. */
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /**
     * The service should be running only when there is a device to reconnect to, the SDK has a
     * user id to handshake with, and the user has not switched background sync off.
     */
    fun shouldRun(paired: Boolean, userConfigured: Boolean, enabled: Boolean): Boolean =
        paired && userConfigured && enabled

    /**
     * Delay before reconnect attempt number [attempt] (0-based, counted since the last successful
     * connection): 15s, 30s, 60s, 120s, 240s, then capped at 5 minutes. Doubling keeps the first
     * retries snappy for a device that just went out of range for a moment, while the cap stops
     * a recorder left at home from costing a BLE scan every few seconds all day.
     */
    fun reconnectDelayMs(attempt: Int): Long {
        val step = attempt.coerceAtLeast(0)
        // Past 2^5 the doubled value is already above the cap; returning early also keeps large
        // attempt counts from overflowing the shift.
        if (step >= 5) return MAX_BACKOFF_MS
        return (INITIAL_BACKOFF_MS shl step).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** The full schedule as a lazy sequence, handy for tests and logging. */
    fun reconnectSchedule(): Sequence<Long> = generateSequence(0) { it + 1 }.map(::reconnectDelayMs)
}
