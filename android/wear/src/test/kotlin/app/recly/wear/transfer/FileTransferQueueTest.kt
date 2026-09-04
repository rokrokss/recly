package app.recly.wear.transfer

import app.recly.datalayer.PartRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Track
import recly.core.platform.Logger

private class CapturingLogger : Logger {
    val events: MutableList<String> = mutableListOf()

    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        events += event
    }
}

/**
 * The queue's job is to never lose a recording and never send one twice, across a process that can
 * die at any point — which on a watch it does, often, and always while a phone is out of range.
 * Every test here is a moment where getting it wrong costs the user audio that exists nowhere else.
 */
class FileTransferQueueTest {

    private val fs = FakeFileSystem()
    private val file = "/data/rec/transfer-queue.json".toPath()
    private val logger = CapturingLogger()
    private var finalized = emptyList<String>()

    private fun queue() = FileTransferQueue(fs, file, logger) { finalized }

    @Test
    fun `two recordings are two, and the same one twice is one`() = runBlocking {
        val queue = queue()

        queue.add("01J9A")
        queue.add("01J9B")
        queue.add("01J9A")

        assertEquals(2, queue.pending.value)
        // And it only says so once: a repeat hand-over is not news.
        assertEquals(listOf("transfer.queue.add", "transfer.queue.add"), logger.events)
    }

    /**
     * The whole reason this is a file. The phone acked two parts of three, the watch was taken off
     * the wrist and the process died — and the next one must not start the recording over.
     */
    @Test
    fun `rows and their acked parts survive the process`() = runBlocking {
        val first = queue()
        first.add("01J9A")
        first.acked("01J9A", PartRef(1, Track.MONO))
        first.acked("01J9A", PartRef(2, Track.MONO))

        val second = queue()

        assertEquals(
            listOf(TransferRow("01J9A", acked = setOf(PartRef(1, Track.MONO), PartRef(2, Track.MONO)))),
            second.all(),
        )
        assertEquals(1, second.pending.value)
    }

    /** A failure is not a deletion: the row stays, the badge changes, and the audio is untouched. */
    @Test
    fun `a fatal nack moves a row out of pending and stays there`() = runBlocking {
        val first = queue()
        first.add("01J9A")
        first.add("01J9B")

        first.fail("01J9A", "RECORDING_ID_MISMATCH")

        assertEquals(1, first.pending.value)
        assertEquals(1, first.failed.value)

        val second = queue()
        assertEquals("RECORDING_ID_MISMATCH", second.all().first { it.recordingId == "01J9A" }.failedReason)
        assertEquals(1, second.failed.value)
    }

    @Test
    fun `a completed transfer leaves no row behind`() = runBlocking {
        val first = queue()
        first.add("01J9A")
        first.acked("01J9A", PartRef(1, Track.MONO))

        first.remove("01J9A")

        assertEquals(0, first.pending.value)
        assertEquals(emptyList(), queue().all())
    }

    /** `ack-meta Incomplete`: the parts the phone asked for again stop counting as acked. */
    @Test
    fun `unack puts a part back`() = runBlocking {
        val queue = queue()
        queue.add("01J9A")
        queue.acked("01J9A", PartRef(1, Track.MONO))
        queue.acked("01J9A", PartRef(2, Track.MONO))

        queue.unack("01J9A", listOf(PartRef(2, Track.MONO)))

        assertEquals(setOf(PartRef(1, Track.MONO)), queue.all().single().acked)
        assertEquals(setOf(PartRef(1, Track.MONO)), queue().all().single().acked)
    }

    /**
     * The crash this covers: the recording was finalized and the process died before anything
     * handed it over. Nothing else would ever look at it again.
     */
    @Test
    fun `reconcile picks up what nothing handed over`() = runBlocking {
        finalized = listOf("01J9CRASHED")
        val queue = queue()

        queue.reconcile()

        assertEquals(1, queue.pending.value)
        assertEquals(listOf("01J9CRASHED"), queue.all().map { it.recordingId })
    }

    /** And never twice — not the row, and above all not the ack state on it. */
    @Test
    fun `reconcile does not re-queue what is already waiting`() = runBlocking {
        val first = queue()
        first.add("01J9A")
        first.acked("01J9A", PartRef(1, Track.MONO))
        finalized = listOf("01J9A")

        val second = queue()
        second.reconcile()

        assertEquals(1, second.pending.value)
        assertEquals(setOf(PartRef(1, Track.MONO)), second.all().single().acked)
    }

    /**
     * A half-written file is the one thing that could make the queue lie about what the phone has.
     * Starting empty costs a rescan; believing it would cost audio — so the file is dropped and
     * `reconcile` rebuilds from what is actually on disk.
     */
    @Test
    fun `a corrupt file is dropped, not half-read`() = runBlocking {
        fs.createDirectories(file.parent!!)
        fs.write(file) { writeUtf8("""{"rows":[{"recordingId":"01J9A"},""") }
        finalized = listOf("01J9A")
        val queue = queue()

        assertEquals(emptyList(), queue.all())
        assertEquals("transfer.queue.corrupt", logger.events.single())

        queue.reconcile()
        assertEquals(listOf("01J9A"), queue.all().map { it.recordingId })
    }

    /** Nothing on disk yet is the ordinary first run, not a fault. */
    @Test
    fun `no file is an empty queue and no complaint`() = runBlocking {
        assertEquals(emptyList(), queue().all())
        assertEquals(emptyList(), logger.events)
    }

    /** A row the queue does not have cannot be acked into existence by a stray ack. */
    @Test
    fun `acks for an unknown recording change nothing`() = runBlocking {
        val queue = queue()

        queue.acked("01J9GHOST", PartRef(1, Track.MONO))
        queue.fail("01J9GHOST", "whatever")

        assertEquals(emptyList(), queue.all())
        assertNull(fs.metadataOrNull(file))
    }
}
