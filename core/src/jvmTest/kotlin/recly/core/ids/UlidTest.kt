@file:OptIn(ExperimentalTime::class)

package recly.core.ids

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.testing.FakeClock

class UlidTest {
    @Test
    fun generatesValidUlids() {
        val id = Ulid.generate(Clock.System)
        assertEquals(26, id.length)
        assertTrue(Ulid.isValid(id), "not a valid ULID: $id")
    }

    @Test
    fun generatesDistinctUlids() {
        val ids = List(1000) { Ulid.generate(Clock.System) }
        assertEquals(1000, ids.toSet().size)
    }

    /**
     * docs/01 "식별자·시간": the first ten characters are the millisecond clock, so an id is its own
     * timestamp — what the row of a watch transfer in flight and of another device's upload are
     * dated by (docs/03), neither of which has a `meta.json` to read a start time out of yet.
     */
    @Test
    fun decodesTheTimeItWasGeneratedAt() {
        val clock = FakeClock(Instant.parse("2026-08-26T01:00:00.000Z"))

        assertEquals(clock.now(), Ulid.timestamp(Ulid.generate(clock)))
    }

    @Test
    fun decodesTheEndsOfTheRange() {
        assertEquals(Instant.fromEpochMilliseconds(0), Ulid.timestamp("0".repeat(26)))
        // 2^48 - 1 ms, the largest a ULID can say (the first character only reaches 7 — the
        // timestamp is 48 bits in 50) — the year 10889.
        assertEquals(Instant.fromEpochMilliseconds(281474976710655), Ulid.timestamp("7ZZZZZZZZZ" + "0".repeat(16)))
    }

    @Test
    fun hasNoTimeForSomethingThatIsNotAUlid() {
        assertNull(Ulid.timestamp("not-a-ulid"))
        assertNull(Ulid.timestamp(""))
        assertNull(Ulid.timestamp("01J9ABCDEF0123456789ABCDE"))
    }

    @Test
    fun rejectsMalformedUlids() {
        assertTrue(!Ulid.isValid("not-a-ulid"))
        assertTrue(!Ulid.isValid("01J9ABCDEF0123456789ABCDE"))
    }
}
