package recly.core.job

import kotlin.math.roundToLong
import kotlin.random.Random
import recly.core.model.Retry

object Backoff {
    /**
     * `initialDelaySec * 2^(attempt-1)` capped at `maxDelaySec`, then ±20% jitter so a fleet of
     * devices that failed on the same outage does not retry in lockstep. [attempt] is 1-based.
     */
    fun delaySec(attempt: Int, retry: Retry, random: Random): Long {
        require(attempt >= 1) { "attempt must be 1-based, was $attempt" }
        val base = minOf(
            retry.initialDelaySec.toLong() shl minOf(attempt - 1, 32),
            retry.maxDelaySec.toLong(),
        )
        return maxOf(1L, (base * (0.8 + 0.4 * random.nextDouble())).roundToLong())
    }
}
