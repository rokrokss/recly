@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** D27: an imported file starts at its creation date when the provider has one, else at its modification date. */
class ImportDateTest {

    @Test
    fun `the creation date wins over the modification date`() {
        assertEquals(Instant.fromEpochMilliseconds(1_000_000), importDate(createdMs = 1_000_000, modifiedMs = 2_000_000, modifiedSec = 3_000))
    }

    @Test
    fun `without a creation date the modification date is used, in milliseconds or in seconds`() {
        assertEquals(Instant.fromEpochMilliseconds(2_000_000), importDate(createdMs = null, modifiedMs = 2_000_000, modifiedSec = 3_000))
        assertEquals(Instant.fromEpochMilliseconds(3_000_000), importDate(createdMs = 0, modifiedMs = null, modifiedSec = 3_000))
    }

    @Test
    fun `a provider with no date leaves the core's now`() {
        assertNull(importDate(createdMs = null, modifiedMs = 0, modifiedSec = null))
    }
}
