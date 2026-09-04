package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Part

/**
 * `MediaDuration` cannot read anything on a bare JVM, so every duration here comes from the
 * fallbacks — which is exactly the path that decides what a crashed or truncated segment is worth.
 */
class SegmentCloserTest {
    private val base = "20260826T010000Z_phone_01J9ABCD"
    private val fs = FakeFileSystem()
    private val dir = "/data/recordings/$base".toPath()
    private val ledger = SegmentLedger(base)
    private val closer = SegmentCloser(fs, dir, ledger, bytesPerSec = 4_000)
    private val registered = mutableListOf<Part>()

    init {
        fs.createDirectories(dir)
    }

    private fun write(part: Int, bytes: Int) {
        fs.write(dir / ledger.fileName(part)) { write(ByteArray(bytes)) }
    }

    private fun drain(lastPart: Int, hintSec: Double? = null): Int = runBlocking {
        closer.drain(lastPart, hintSec) { registered += it }
    }

    @Test
    fun `the armed next file counts as a part when the encoder got as far as filling it`() {
        // The boundary fired between the last callback and the stop: part 2 is real audio.
        write(1, 3_600_000)
        write(2, 40_000)

        assertEquals(2, drain(lastPart = 2))

        assertEquals(listOf(1, 2), registered.map { it.part })
        assertEquals(40_000L, registered[1].bytes)
    }

    @Test
    fun `an armed file the encoder never touched is deleted, not registered`() {
        write(1, 3_600_000)
        write(2, 0)

        assertEquals(1, drain(lastPart = 2))

        assertEquals(listOf(1), registered.map { it.part })
        assertFalse(fs.exists(dir / ledger.fileName(2)))
        assertTrue(fs.exists(dir / ledger.fileName(1)))
    }

    @Test
    fun `offsets stay contiguous across a drained pair`() {
        write(1, 3_600_000)
        write(2, 40_000)

        drain(lastPart = 2)

        assertEquals(0.0, registered[0].startOffsetSec)
        assertEquals(900.0, registered[0].durationSec)
        assertEquals(900.0, registered[1].startOffsetSec)
        assertEquals(10.0, registered[1].durationSec)
        assertEquals(910.0, ledger.recordedSec)
    }

    @Test
    fun `the wall-clock hint wins over the bitrate guess, and only for the first part`() {
        write(1, 3_600_000)
        write(2, 40_000)

        drain(lastPart = 2, hintSec = 899.4)

        assertEquals(899.4, registered[0].durationSec)
        // The armed file has no wall clock of its own; its bytes are all there is to go on.
        assertEquals(10.0, registered[1].durationSec)
    }

    @Test
    fun `a stop with nothing written registers nothing and leaves nothing behind`() {
        write(1, 0)

        assertEquals(0, drain(lastPart = 2))

        assertTrue(registered.isEmpty())
        assertFalse(fs.exists(dir / ledger.fileName(1)))
    }

    @Test
    fun `a part carries the hash of what is actually on disk`() {
        write(1, 16)

        drain(lastPart = 1)

        // sha256 of sixteen zero bytes.
        assertEquals(
            "374708fff7719dd5979ec875d56cd2286f6d3cf7ec317a3b25632aab28ec37bb",
            registered.single().sha256,
        )
    }
}
