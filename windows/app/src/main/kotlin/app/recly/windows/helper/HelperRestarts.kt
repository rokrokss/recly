@file:OptIn(ExperimentalTime::class)

package app.recly.windows.helper

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How often the detection helper may be brought back after it dies (docs/lanes/M6-L3 deliverable 3).
 *
 * A helper that crashes once is a bad frame; a helper that crashes ten times in a row is a machine
 * where it cannot run at all, and restarting it forever would mean a process spawned every few
 * seconds for the rest of the session with nothing to show for it. So: a few tries, then stop and
 * say so.
 *
 * The **recording** helper has no policy here on purpose. Its death ends the recording (docs/14
 * "헬퍼가 죽으면 앱이 마지막 파트까지를 finalize한다"), and a restart that silently began a second
 * recording is exactly the thing ADR-011 forbids — the shell offers, the user decides.
 */
class HelperRestarts(
    private val max: Int = MAX,
    private val window: Duration = WINDOW,
) {
    private val attempts = ArrayDeque<Instant>()

    /** Records the attempt and answers whether it may be made. */
    fun allow(now: Instant): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first() > window) attempts.removeFirst()
        if (attempts.size >= max) return false
        attempts.addLast(now)
        return true
    }

    companion object {
        const val MAX: Int = 3
        val WINDOW: Duration = 10.minutes
    }
}
