package recly.core.job

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import recly.core.model.Retry

class BackoffTest {
    private val defaults = Retry()

    @Test
    fun followsTheDefaultSequenceWithinJitter() {
        val random = Random(7)
        val nominal = listOf(30L, 60L, 120L, 240L, 480L, 960L, 1920L, 3600L)
        nominal.forEachIndexed { index, base ->
            val delay = Backoff.delaySec(index + 1, defaults, random)
            assertTrue(
                delay >= (base * 0.8).toLong() && delay <= (base * 1.2).toLong() + 1,
                "attempt ${index + 1}: $delay is not within 20% of $base",
            )
        }
    }

    @Test
    fun capsAtMaxDelay() {
        // 30 * 2^19 would be 15,728,640s; the cap is what the jitter is applied to.
        val delay = Backoff.delaySec(20, defaults, Random(1))
        assertTrue(delay <= (3600 * 1.2).toLong() + 1, "$delay exceeds the capped maximum")
    }

    @Test
    fun jitterLandsOnBothSidesOfTheNominalDelay() {
        val random = Random(3)
        val samples = List(200) { Backoff.delaySec(1, defaults, random) }
        assertTrue(samples.min() < 30, "no sample below the nominal 30s: ${samples.min()}")
        assertTrue(samples.max() > 30, "no sample above the nominal 30s: ${samples.max()}")
        assertEquals(0, samples.count { it < 24 || it > 36 }, "jitter left the ±20% band")
    }

    @Test
    fun honoursCustomRetrySettings() {
        val retry = Retry(maxAttempts = 3, initialDelaySec = 5, maxDelaySec = 12)
        assertTrue(Backoff.delaySec(1, retry, Random(11)) in 4..6)
        assertTrue(Backoff.delaySec(3, retry, Random(11)) in 9..15)
    }

    @Test
    fun rejectsZeroBasedAttempts() {
        assertFailsWith<IllegalArgumentException> { Backoff.delaySec(0, defaults, Random(1)) }
    }
}
