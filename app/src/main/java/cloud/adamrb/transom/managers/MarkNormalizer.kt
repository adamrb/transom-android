package cloud.adamrb.transom.managers

/**
 * Turns the raw mark values the recorder returns for a session into offsets in seconds from the
 * recording start, which is what the bridge server expects (a "marks" list of seconds).
 *
 * The SDK does not document the unit of GetRecMarkingRsp's values. Session ids on the Note Pro
 * are the unix epoch seconds of the recording start, so the marks are most likely absolute epoch
 * timestamps (seconds or milliseconds) or offsets (seconds or milliseconds). Rather than guess
 * once and be wrong for every recording, each value is classified by magnitude against the
 * session id and the recording duration; values that fit no interpretation are dropped. Pure
 * Kotlin so the heuristic is unit-testable branch by branch, and so MarksSyncManager can log the
 * raw list next to the normalized one until a real recording settles the unit.
 */
object MarkNormalizer {

    /**
     * Slack added to the duration for the offset/epoch window checks. The stored duration comes
     * from the exported audio and may be a little shorter than the device's own clock (paused
     * stretches, header rounding), and the device may register a press right after the stop.
     */
    const val SLACK_SEC = 120L

    /**
     * @param raw values as returned by the device
     * @param sessionIdEpochSec the session id, i.e. the recording start in epoch seconds
     * @param durationSec the recording length in seconds (0 when unknown; the windows then cover
     *   only the slack, which still classifies epoch values correctly)
     * @return offsets in seconds from the start: non-negative, de-duplicated, ascending
     */
    fun toOffsetsSeconds(raw: List<Long>, sessionIdEpochSec: Long, durationSec: Long): List<Double> {
        val window = durationSec.coerceAtLeast(0) + SLACK_SEC
        return raw.mapNotNull { v -> classify(v, sessionIdEpochSec, window) }
            .filter { it >= 0.0 }
            .distinct()
            .sorted()
    }

    /** One value through the heuristic, in order; null when no interpretation is plausible. */
    private fun classify(v: Long, sessionId: Long, window: Long): Double? = when {
        // Epoch milliseconds: nothing else is anywhere near 1e12.
        v > EPOCH_MS_FLOOR -> (v - sessionId * 1000) / 1000.0
        // Epoch seconds inside the recording (plus slack).
        v >= sessionId && v <= sessionId + window -> (v - sessionId).toDouble()
        // Millisecond offset: too large to be a seconds offset, small enough to be inside the
        // recording when read as milliseconds.
        v > window && v <= window * 1000 -> v / 1000.0
        // Seconds offset.
        v >= 0 && v <= window -> v.toDouble()
        else -> null
    }

    private const val EPOCH_MS_FLOOR = 1_000_000_000_000L
}
