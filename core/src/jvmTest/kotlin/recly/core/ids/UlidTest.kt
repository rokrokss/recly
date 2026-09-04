@file:OptIn(ExperimentalTime::class)

package recly.core.ids

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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

    @Test
    fun rejectsMalformedUlids() {
        assertTrue(!Ulid.isValid("not-a-ulid"))
        assertTrue(!Ulid.isValid("01J9ABCDEF0123456789ABCDE"))
    }
}
