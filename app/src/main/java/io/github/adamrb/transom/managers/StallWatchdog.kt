package io.github.adamrb.transom.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A resettable timer for "nothing happened for too long". [arm] (re)starts the countdown; when
 * it runs out, [onStall] fires once, unless [isBusyLocally] says the quiet spell is expected
 * (work on the phone that reports no progress, e.g. the SDK transcoding a finished download),
 * in which case the countdown simply starts over. [disarm] cancels it.
 *
 * SyncManager uses it to turn a BLE sync that stopped getting answers from the recorder (no
 * file list, no download progress) into a failure instead of a banner that never clears. Pure
 * coroutines so the timing can be unit-tested with a test dispatcher.
 */
class StallWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val isBusyLocally: () -> Boolean = { false },
    private val onStall: () -> Unit
) {
    private var job: Job? = null

    val isArmed: Boolean get() = job?.isActive == true

    fun arm() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(timeoutMs)
                if (!isBusyLocally()) break
            }
            job = null
            onStall()
        }
    }

    fun disarm() {
        job?.cancel()
        job = null
    }
}
