@file:OptIn(ExperimentalCoroutinesApi::class)

package app.recly.wear.transfer

import app.recly.datalayer.AckMessage
import app.recly.datalayer.PartRef
import app.recly.datalayer.TransferPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord

private const val ID = "01J9WATCH"
private val ROOT = "/data/rec".toPath()

private object SilentLogger : Logger {
    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) = Unit
}

/** The core, as the sender uses it: two calls, and a `delete` that really removes the directory. */
private class FakeRecordings(private val fs: FileSystem) : Recordings {
    val records: MutableMap<String, RecordingRecord> = mutableMapOf()
    val deleted: MutableList<String> = mutableListOf()

    override suspend fun get(recordingId: String): RecordingRecord? = records[recordingId]

    override suspend fun delete(recordingId: String) {
        deleted += recordingId
        records.remove(recordingId)?.let { fs.deleteRecursively(it.dir) }
    }
}

/**
 * The Data Layer, scripted. [reply] decides what the phone would say to each path, which is the
 * only thing the sender's decisions turn on — and returning nothing is exactly what a phone that
 * went out of range looks like, so the timeout falls out of the same fake.
 */
private class FakeLink(private val reply: (String) -> List<AckMessage>) : TransferLink {
    var channel: FakeChannel? = null
    var phone: Boolean = true
    var closed: Boolean = false

    override suspend fun open(): TransferChannel? {
        if (!phone) return null
        return FakeChannel(reply) { closed = true }.also { channel = it }
    }

    val sent: List<String> get() = channel?.sent.orEmpty()
}

private class FakeChannel(
    private val reply: (String) -> List<AckMessage>,
    private val onClose: () -> Unit,
) : TransferChannel {
    val sent: MutableList<String> = mutableListOf()
    val files: MutableList<Path> = mutableListOf()
    private val inbox = Channel<AckMessage>(Channel.UNLIMITED)

    override suspend fun send(path: String, file: Path) {
        sent += path
        files += file
        reply(path).forEach { inbox.send(it) }
    }

    override suspend fun nextAck(): AckMessage = inbox.receive()

    override suspend fun close() = onClose()
}

/**
 * The same link with the acks taken off the auto-pilot: nothing comes back until the test says so,
 * which is the only way to stand inside the window between a file going out and its ack landing and
 * ask what is still on disk. [FakeChannel] answers before the sender has even suspended, so a test
 * written on it can only ever see the state *after* an ack — the state a deletion bug hides in.
 */
private class GatedLink : TransferLink {
    val channel = GatedChannel()
    override suspend fun open(): TransferChannel = channel
    val sent: List<String> get() = channel.sent
}

private class GatedChannel : TransferChannel {
    val sent: MutableList<String> = mutableListOf()
    var closed: Boolean = false
        private set

    /** Every file put on the wire, with the promise the sender is about to block on. */
    private val wire = Channel<Pair<String, CompletableDeferred<AckMessage>>>(Channel.UNLIMITED)
    private var gate: CompletableDeferred<AckMessage>? = null

    override suspend fun send(path: String, file: Path) {
        sent += path
        // One file is in flight at a time (the sender waits for each ack), so one gate is enough.
        gate = CompletableDeferred<AckMessage>().also { wire.send(path to it) }
    }

    override suspend fun nextAck(): AckMessage = checkNotNull(gate).await()

    override suspend fun close() {
        closed = true
    }

    /** Suspends until the sender has sent a file and is waiting on its ack. */
    suspend fun inFlight(): Pair<String, CompletableDeferred<AckMessage>> = wire.receive()
}

/** Every delete the sender and the core made, in order — what "nothing was deleted yet" is read from. */
private class WatchingFileSystem(
    delegate: FileSystem,
    val deletes: MutableList<Path> = mutableListOf(),
) : okio.ForwardingFileSystem(delegate) {

    override fun delete(path: Path, mustExist: Boolean) {
        deletes += path
        super.delete(path, mustExist)
    }
}

/**
 * The transfer, as a state machine, off the wrist. Every case here is one where the wrong answer
 * deletes audio that exists nowhere else, or leaves audio on a watch that nothing will ever look
 * at again — which is the whole reason `TransferLink` is an interface.
 */
class TransferSenderTest {

    private val fs = FakeFileSystem()
    private val watched = WatchingFileSystem(fs)
    private val recordings = FakeRecordings(watched)
    private val queue = FileTransferQueue(fs, ROOT / "transfer-queue.json", SilentLogger) { emptyList() }

    private val dir = ROOT / "recordings" / ID
    private val base = "20260827T090000Z_watch_${ID.take(8)}"

    private fun sender(link: TransferLink) =
        TransferSender(queue, recordings, watched, link, SilentLogger)

    private fun partFile(number: Int) = dir / MetaWriter.partFileName(base, number, Track.MONO)

    /** Two parts on disk, a `meta.json` next to them, and a row waiting for all three. */
    private suspend fun recording(parts: Int = 2) {
        val list = (1..parts).map { number ->
            Part(
                part = number,
                track = Track.MONO,
                file = MetaWriter.partFileName(base, number, Track.MONO),
                bytes = 4,
                sha256 = "%064x".format(number),
                startOffsetSec = 0.0,
                durationSec = 900.0,
            )
        }
        fs.createDirectories(dir)
        list.forEach { fs.write(dir / it.file) { writeUtf8("part") } }
        fs.write(dir / MetaWriter.metaFileName(base)) { writeUtf8("{}") }
        recordings.records[ID] = RecordingRecord(ID, meta(list), dir)
        queue.add(ID)
    }

    private fun meta(parts: List<Part>) = RecordingMeta(
        schema = 1,
        recordingId = ID,
        source = Source.WATCH,
        platform = Platform.WEAROS,
        deviceId = "device",
        deviceName = "Galaxy Watch",
        startedAt = "2026-08-27T09:00:00Z",
        timezone = "Asia/Seoul",
        audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
        tracks = listOf(Track.MONO),
        parts = parts,
        status = RecordingStatus.FINALIZED,
    )

    private fun partPath(number: Int) = TransferPath.PartFile(
        recordingId = ID,
        part = number,
        track = Track.MONO,
        sha256 = "%064x".format(number),
        file = MetaWriter.partFileName(base, number, Track.MONO),
    ).serialize()

    /**
     * The happy path, and the two things it has to get exactly right: the paths are the grammar the
     * phone parses (docs/03), and *nothing at all* leaves the disk before `ack-meta ok:true`.
     *
     * Every assertion below is taken while the sender is blocked on an ack it has not been given
     * yet, which is the only place the second one can be checked: a part ack the sender acted on
     * and a part ack it has not seen look identical once the ack has been delivered.
     */
    @Test
    fun `parts then meta, and nothing is deleted before the ack-meta`() = runTest {
        recording()
        val link = GatedLink()
        val pass = async { sender(link).run() }

        val (first, ackPart1) = link.channel.inFlight()
        assertEquals(partPath(1), first)
        assertTrue(fs.exists(partFile(1)), "part 1 must still be on disk while its ack is pending")
        assertEquals(emptyList(), watched.deletes)
        ackPart1.complete(ackFor(first))

        val (second, ackPart2) = link.channel.inFlight()
        assertEquals(partPath(2), second)
        // Part 1 is acked and the row says so — and its file is still here, because the phone has a
        // loose part it purges after 24 hours until the meta arrives (docs/03).
        assertEquals(setOf(PartRef(1, Track.MONO)), queue.all().single().acked)
        assertTrue(fs.exists(partFile(1)), "an acked part must survive until the meta is acked")
        assertEquals(emptyList(), watched.deletes)
        ackPart2.complete(ackFor(second))

        val (meta, ackMeta) = link.channel.inFlight()
        assertEquals("/rec/meta/$ID", meta)
        assertTrue(fs.exists(partFile(1)))
        assertTrue(fs.exists(partFile(2)))
        assertTrue(fs.exists(dir / MetaWriter.metaFileName(base)))
        assertEquals(emptyList(), watched.deletes)
        ackMeta.complete(AckMessage.Meta(ID, ok = true))

        assertEquals(TransferOutcome.DONE, pass.await())
        assertEquals(listOf(partPath(1), partPath(2), "/rec/meta/$ID"), link.sent)
        // ok ack-meta: directory and row both gone, and the core was the one asked to delete it.
        assertEquals(listOf(ID), recordings.deleted)
        assertFalse(fs.exists(dir))
        assertEquals(emptyList(), queue.all())
        assertTrue(link.channel.closed)
    }

    /**
     * The immediate worker and the six-hour periodic one cannot share a unique name, so two passes
     * really can start at once — and two passes sending the same recording would open two channels
     * on the same path. The second one must see nothing and do nothing; [TransferWorker] turns that
     * into a 60-second follow-up rather than a retry.
     */
    @Test
    fun `a pass that finds another in flight sends nothing`() = runTest {
        recording()
        val busy = GatedLink()
        val pass = async { sender(busy).run() }
        // The first pass is inside the lock: a part is on the wire and its ack has not come.
        val (first, ackPart1) = busy.channel.inFlight()

        val second = FakeLink { path -> listOf(ackFor(path)) }
        assertEquals(TransferOutcome.ALREADY_RUNNING, sender(second).run())

        assertNull(second.channel, "the second pass must not even open a channel")
        assertEquals(emptyList(), second.sent)

        // And the pass that was running is untouched by it: it finishes the recording on its own.
        ackPart1.complete(ackFor(first))
        repeat(2) {
            val (path, gate) = busy.channel.inFlight()
            gate.complete(ackFor(path))
        }
        assertEquals(TransferOutcome.DONE, pass.await())
        assertEquals(listOf(partPath(1), partPath(2), "/rec/meta/$ID"), busy.sent)
        assertEquals(emptyList(), queue.all())
    }

    /** No phone is not a failure and must not touch a thing. */
    @Test
    fun `nothing happens without a phone`() = runTest {
        recording()
        val link = FakeLink { emptyList() }.apply { phone = false }

        assertEquals(TransferOutcome.NO_PHONE, sender(link).run())

        assertTrue(fs.exists(dir / MetaWriter.partFileName(base, 1, Track.MONO)))
        assertEquals(1, queue.pending.value)
    }

    @Test
    fun `an empty queue does not even open a channel`() = runTest {
        val link = FakeLink { emptyList() }

        assertEquals(TransferOutcome.IDLE, sender(link).run())

        assertNull(link.channel)
    }

    /**
     * docs/11 W4: five minutes with no ack ends the pass. The part that was acked before it stays
     * acked — re-sending it would be the phone hashing bytes it already has — and every file is
     * still here, the acked one included: the meta was never acked, so nothing licenses a delete.
     */
    @Test
    fun `a silent phone times out and keeps everything`() = runTest {
        recording()
        val link = FakeLink { path -> if (path == partPath(1)) listOf(ackFor(path)) else emptyList() }

        assertEquals(TransferOutcome.STALLED, sender(link).run())

        assertTrue(fs.exists(partFile(1)))
        assertTrue(fs.exists(partFile(2)))
        assertEquals(emptyList(), watched.deletes)
        assertEquals(setOf(PartRef(1, Track.MONO)), queue.all().single().acked)
        assertEquals(1, queue.pending.value)
    }

    /** And the next pass picks up exactly where it stopped: part 2, then the meta. */
    @Test
    fun `the next pass sends only what is left`() = runTest {
        recording()
        sender(FakeLink { path -> if (path == partPath(1)) listOf(ackFor(path)) else emptyList() }).run()

        val link = FakeLink { path -> listOf(ackFor(path)) }
        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(listOf(partPath(2), "/rec/meta/$ID"), link.sent)
        assertEquals(emptyList(), queue.all())
    }

    /**
     * A refusal the watch cannot retry its way out of. The row is marked so the screen can say so,
     * and the audio is *kept* — nothing else on this watch will ever look at it again.
     */
    @Test
    fun `a fatal part nack keeps the audio and marks the row`() = runTest {
        recording()
        val link = FakeLink { path ->
            if (path == partPath(1)) {
                listOf(AckMessage.Part(ID, PartRef(1, Track.MONO), ok = false, reason = "UNSUPPORTED_TRACK"))
            } else {
                listOf(ackFor(path))
            }
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals("UNSUPPORTED_TRACK", queue.all().single().failedReason)
        assertEquals(0, queue.pending.value)
        assertEquals(1, queue.failed.value)
        assertTrue(fs.exists(partFile(1)))
        assertEquals(emptyList(), recordings.deleted)
        // And a later pass leaves it alone rather than sending it forever.
        val second = FakeLink { path -> listOf(ackFor(path)) }
        assertEquals(TransferOutcome.IDLE, sender(second).run())
    }

    /**
     * The case the deletion policy exists for: a recording that fails *after* the phone has acked
     * a part. Under an ack-per-part delete, part 1 would already be gone and the recording the
     * screen now says failed would be half a recording. Nothing is deleted, so it is all still here.
     */
    @Test
    fun `a fatal nack after an acked part keeps that part too`() = runTest {
        recording()
        val link = FakeLink { path ->
            if (path == partPath(2)) {
                listOf(AckMessage.Part(ID, PartRef(2, Track.MONO), ok = false, reason = "UNSUPPORTED_TRACK"))
            } else {
                listOf(ackFor(path))
            }
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals("UNSUPPORTED_TRACK", queue.all().single().failedReason)
        assertEquals(1, queue.failed.value)
        assertTrue(fs.exists(partFile(1)), "the acked part must survive a recording that then failed")
        assertTrue(fs.exists(partFile(2)))
        assertTrue(fs.exists(dir / MetaWriter.metaFileName(base)))
        assertEquals(emptyList(), watched.deletes)
    }

    /**
     * `SHA256_MISMATCH` is the one nack worth retrying: the phone deletes the bytes it could not
     * verify, so a resend cannot duplicate anything, and a corrupted Bluetooth transfer is far more
     * likely than a corrupted file. One resend, and the transfer carries on as if it never happened.
     */
    @Test
    fun `a mismatched part is sent again once`() = runTest {
        recording()
        var mismatches = 0
        val link = FakeLink { path ->
            listOf(
                if (path == partPath(1) && mismatches++ == 0) {
                    AckMessage.Part(ID, PartRef(1, Track.MONO), ok = false, reason = "SHA256_MISMATCH")
                } else {
                    ackFor(path)
                },
            )
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(listOf(partPath(1), partPath(1), partPath(2), "/rec/meta/$ID"), link.sent)
        assertFalse(fs.exists(dir))
        assertEquals(emptyList(), queue.all())
    }

    /** Twice on the same part is the file, not the wire. Fatal, and the audio stays. */
    @Test
    fun `a second mismatch on the same part is fatal`() = runTest {
        recording()
        val link = FakeLink { path ->
            listOf(
                if (path == partPath(1)) {
                    AckMessage.Part(ID, PartRef(1, Track.MONO), ok = false, reason = "SHA256_MISMATCH")
                } else {
                    ackFor(path)
                },
            )
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(listOf(partPath(1), partPath(1)), link.sent)
        assertEquals(TransferSender.SHA256_MISMATCH, queue.all().single().failedReason)
        assertTrue(fs.exists(partFile(1)))
    }

    @Test
    fun `a fatal meta nack is fatal too`() = runTest {
        recording()
        val link = FakeLink { path ->
            listOf(
                if (path.startsWith(TransferPath.META_PREFIX)) {
                    AckMessage.Meta(ID, ok = false, reason = "RECORDING_ID_MISMATCH")
                } else {
                    ackFor(path)
                },
            )
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals("RECORDING_ID_MISMATCH", queue.all().single().failedReason)
        assertTrue(fs.exists(dir / MetaWriter.metaFileName(base)))
    }

    /**
     * `Incomplete(missing)` for a part the watch still has: the ack for it was lost, so send it
     * again and follow it with the meta again. The part file is back on the wire, not skipped.
     */
    @Test
    fun `a missing part the watch still has is re-sent`() = runTest {
        recording()
        var metas = 0
        val link = FakeLink { path ->
            listOf(
                if (path.startsWith(TransferPath.META_PREFIX)) {
                    if (metas++ == 0) {
                        AckMessage.Meta(ID, ok = false, missing = listOf(PartRef(2, Track.MONO)))
                    } else {
                        AckMessage.Meta(ID, ok = true)
                    }
                } else {
                    ackFor(path)
                },
            )
        }
        assertEquals(TransferOutcome.DONE, sender(link).run())

        // Part 2 goes out again, and the meta after it — the phone never sees a meta it has not
        // just been given every part for.
        assertEquals(
            listOf(partPath(1), partPath(2), "/rec/meta/$ID", partPath(2), "/rec/meta/$ID"),
            link.sent,
        )
        assertEquals(emptyList(), queue.all())
    }

    /**
     * A negative ack-meta never completes anything. Nothing leaves this watch before `ack-meta
     * ok:true`, so a part the phone asks for again that is genuinely not on disk can only have been
     * deleted from outside this app — and the phone has *just said* it does not have the recording,
     * which makes deleting the rest of it the one thing that would lose the audio for good. The row
     * is marked so the screen can say so, and every other file stays exactly where it is.
     */
    @Test
    fun `a missing part the watch no longer has fails the transfer and keeps the rest`() = runTest {
        recording()
        // The row says part 1 was acked and its file is gone — something outside deleted it, since
        // no ok ack-meta ever licensed this watch to.
        queue.acked(ID, PartRef(1, Track.MONO))
        fs.delete(dir / MetaWriter.partFileName(base, 1, Track.MONO))
        val link = FakeLink { path ->
            listOf(
                if (path.startsWith(TransferPath.META_PREFIX)) {
                    AckMessage.Meta(ID, ok = false, missing = listOf(PartRef(1, Track.MONO)))
                } else {
                    ackFor(path)
                },
            )
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(listOf(partPath(2), "/rec/meta/$ID"), link.sent)
        assertEquals(emptyList(), recordings.deleted, "a negative ack-meta must never complete")
        assertTrue(fs.exists(dir))
        assertEquals(TransferSender.PART_MISSING_LOCALLY, queue.all().single().failedReason)
        assertEquals(1, queue.failed.value)
        assertTrue(fs.exists(partFile(2)), "the part that is still here must not be deleted with it")
        assertTrue(fs.exists(dir / MetaWriter.metaFileName(base)))
        assertEquals(emptyList(), watched.deletes)
    }

    /**
     * The case the old "treat it as complete" branch was written for, converging the way it should:
     * the ok ack-meta was lost and the phone — which purged its orphan parts — comes back asking for
     * *every* part. The watch still has all of them, because only that lost ack could have deleted
     * them, so it sends them all again and the meta after, and the phone (whose `acceptPart`
     * overwrites what it staged) files it and acks ok. That ok is what deletes the audio.
     */
    @Test
    fun `a lost ok ack-meta converges - all parts reported missing, all sent again, then complete`() = runTest {
        recording()
        var metas = 0
        val link = FakeLink { path ->
            listOf(
                if (path.startsWith(TransferPath.META_PREFIX)) {
                    if (metas++ == 0) {
                        AckMessage.Meta(
                            ID,
                            ok = false,
                            missing = listOf(PartRef(1, Track.MONO), PartRef(2, Track.MONO)),
                        )
                    } else {
                        AckMessage.Meta(ID, ok = true)
                    }
                } else {
                    ackFor(path)
                },
            )
        }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        // Both parts again — the row calling them acked does not stop a part the phone says it lost
        // going back out — and only then the meta.
        assertEquals(
            listOf(partPath(1), partPath(2), "/rec/meta/$ID", partPath(1), partPath(2), "/rec/meta/$ID"),
            link.sent,
        )
        assertEquals(listOf(ID), recordings.deleted)
        assertFalse(fs.exists(dir))
        assertEquals(emptyList(), queue.all())
    }

    /** A phone that answers every resend with the same list is not going to stop. */
    @Test
    fun `an endless missing list ends as a failure, not a loop`() = runTest {
        recording()
        val link = FakeLink { path ->
            listOf(
                if (path.startsWith(TransferPath.META_PREFIX)) {
                    AckMessage.Meta(ID, ok = false, missing = listOf(PartRef(1, Track.MONO)))
                } else {
                    ackFor(path)
                },
            )
        }
        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(TransferSender.RESEND_LOOP, queue.all().single().failedReason)
        assertEquals(emptyList(), recordings.deleted)
    }

    /** A row whose recording is gone from the core sends nothing and stops being a row. */
    @Test
    fun `a row without a recording is dropped`() = runTest {
        queue.add("01J9GONE")
        val link = FakeLink { emptyList() }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(emptyList(), queue.all())
        assertEquals(emptyList(), link.sent)
    }

    /** Every part acked and no `meta.json` to end the transfer with: not retryable, so say so. */
    @Test
    fun `a recording with no meta on disk fails rather than spinning`() = runTest {
        recording()
        fs.delete(dir / MetaWriter.metaFileName(base))
        val link = FakeLink { path -> listOf(ackFor(path)) }

        assertEquals(TransferOutcome.DONE, sender(link).run())

        assertEquals(TransferSender.META_MISSING, queue.all().single().failedReason)
    }

    private fun ackFor(path: String): AckMessage = when (val parsed = TransferPath.parse(path)) {
        is TransferPath.PartFile -> AckMessage.Part(ID, PartRef(parsed.part, parsed.track), ok = true)
        is TransferPath.Meta -> AckMessage.Meta(ID, ok = true)
        null -> error("the sender opened a channel the phone would reject: $path")
    }
}
