package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.model.Track

class SegmentLedgerTest {

    private val base = "20260826T010000Z_phone_01J9ABCD"

    @Test
    fun `names parts from one, zero padded, on the mono track`() {
        val ledger = SegmentLedger(base)

        assertEquals("${base}_p001_mono.m4a", ledger.openFileName())
        assertEquals("${base}_p012_mono.m4a", ledger.fileName(12))
    }

    @Test
    fun `closing a segment opens the next one`() {
        val ledger = SegmentLedger(base)
        ledger.close(bytes = 1, sha256 = "a", durationSec = 900.0)

        assertEquals(2, ledger.openPart)
        assertEquals("${base}_p002_mono.m4a", ledger.openFileName())
    }

    @Test
    fun `offsets accumulate the durations actually written, not the nominal segment length`() {
        val ledger = SegmentLedger(base)

        val first = ledger.close(bytes = 3_600_000, sha256 = "a", durationSec = 899.8)
        val second = ledger.close(bytes = 3_601_000, sha256 = "b", durationSec = 900.2)
        val third = ledger.close(bytes = 41_000, sha256 = "c", durationSec = 10.5)

        assertEquals(0.0, first.startOffsetSec)
        assertEquals(899.8, second.startOffsetSec)
        // Not 1800.0: the short first segment moves everything after it.
        assertEquals(1800.0, third.startOffsetSec)
        assertEquals(1810.5, ledger.recordedSec)
    }

    @Test
    fun `a closed part carries what the meta needs`() {
        val ledger = SegmentLedger(base)
        ledger.close(bytes = 1, sha256 = "a", durationSec = 900.0)

        val part = ledger.close(bytes = 3_601_234, sha256 = "cafe", durationSec = 899.5)

        assertEquals(2, part.part)
        assertEquals(Track.MONO, part.track)
        assertEquals("${base}_p002_mono.m4a", part.file)
        assertEquals(3_601_234L, part.bytes)
        assertEquals("cafe", part.sha256)
        assertEquals(900.0, part.startOffsetSec)
        assertEquals(899.5, part.durationSec)
    }

    @Test
    fun `an empty ledger has recorded nothing`() {
        assertEquals(0.0, SegmentLedger(base).recordedSec)
        assertEquals(1, SegmentLedger(base).openPart)
    }
}
