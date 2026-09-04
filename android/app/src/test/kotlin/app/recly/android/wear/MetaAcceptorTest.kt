package app.recly.android.wear

import app.recly.datalayer.TransferPath
import app.recly.datalayer.WearJson
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import recly.core.job.EnqueueResult
import recly.core.model.Part
import recly.core.model.Track
import recly.core.platform.Logger
import recly.core.transfer.AcceptMetaResult

private const val ID = "01J9ABCDEF0123456789ABCDEF"
private const val OTHER = "01J9ZZZZZZ0123456789ABCDEF"
private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/**
 * The order of the meta handshake, on the JVM. `ack-meta ok:true` is the watch's licence to delete
 * the only copy of the audio (docs/03 "워치 → 폰 전송 계약"), so what is under test is that nothing
 * acks before the recording is filed, queued and the executor woken — and that a body that names a
 * different recording never reaches the core at all.
 */
class MetaAcceptorTest {

    /** Every core call and every ack, in the order they happened. */
    private val ops = mutableListOf<String>()

    /** Everything in order, including the ack and logging — for ordering assertions only. */
    private val timeline = mutableListOf<String>()

    private val acks = mutableListOf<String>()

    private val logs = mutableListOf<Pair<Logger.Level, String>>()

    private val path = TransferPath.Meta(ID)

    private inner class FakeMetaFacade(
        private val result: AcceptMetaResult = AcceptMetaResult.Complete(ID),
        private val workflowId: String? = "wf-1",
        private val enqueued: EnqueueResult = EnqueueResult.Enqueued("job-1"),
        private val failEnqueue: Throwable? = null,
        private val failAccept: Throwable? = null,
        private val failWake: Throwable? = null,
    ) : MetaFacade {

        /** What `enqueue` was asked to run, once it has been asked. */
        var enqueueArgs: Pair<String, String?>? = null
            private set

        override suspend fun acceptMeta(json: String): AcceptMetaResult {
            ops += "acceptMeta"
            failAccept?.let { throw it }
            return result
        }

        override suspend fun workflowId(recordingId: String): String? = workflowId

        override suspend fun enqueue(recordingId: String, workflowId: String?): EnqueueResult {
            ops += "enqueue"
            enqueueArgs = recordingId to workflowId
            failEnqueue?.let { throw it }
            return enqueued
        }

        override suspend fun onJobsDue() {
            ops += "onJobsDue"
            failWake?.let { throw it }
        }

        override suspend fun log(
            level: Logger.Level,
            event: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) {
            timeline += "log"
            logs += level to event
        }
    }

    private val ack = AckSender { messagePath, payload ->
        assertEquals(WearJson.ACK_META, messagePath)
        ops += "ack"
        timeline += "ack"
        acks += payload
    }

    private fun meta(recordingId: String): String =
        """{"schema":1,"recordingId":"$recordingId","source":"watch","parts":[]}"""

    @Test
    fun `the ok ack comes only after the recording is filed queued and the executor woken`() = runTest {
        val core = FakeMetaFacade()

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue", "onJobsDue", "ack"), ops)
        assertEquals(listOf("""{"recordingId":"$ID","ok":true}"""), acks)
        assertEquals(ID to "wf-1", core.enqueueArgs)
    }

    @Test
    fun `a meta that names another recording is nacked without touching the core`() = runTest {
        val core = FakeMetaFacade()

        MetaAcceptor(core, ack).accept(path, meta(OTHER))

        assertEquals(listOf("ack"), ops, "no core call may run on a body the path does not vouch for")
        assertEquals(listOf("ack", "log"), timeline, "the nack goes out before even the log")
        assertEquals(
            listOf("""{"recordingId":"$ID","ok":false,"reason":"RECORDING_ID_MISMATCH"}"""),
            acks,
            "the ack names the id of the PATH — the only id both sides agreed on",
        )
    }

    @Test
    fun `a body that is not readable json is a mismatch too`() = runTest {
        val core = FakeMetaFacade()

        MetaAcceptor(core, ack).accept(path, "not json at all")

        assertEquals(listOf("ack"), ops)
        assertEquals(listOf("ack", "log"), timeline, "the nack goes out before even the log")
        assertTrue(acks.single().contains("RECORDING_ID_MISMATCH"))
    }

    @Test
    fun `an enqueue that blows up leaves no ack so the watch keeps its copy and resends`() = runTest {
        val core = FakeMetaFacade(failEnqueue = IOException("db gone"))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue"), ops)
        assertTrue(acks.isEmpty(), "an ok ack would let the watch delete audio that is not queued")
        assertEquals(listOf(Logger.Level.ERROR to "wear.meta.failed"), logs)
    }

    @Test
    fun `a wake that blows up leaves no ack either`() = runTest {
        val core = FakeMetaFacade(failWake = IllegalStateException("no WorkManager"))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue", "onJobsDue"), ops)
        assertTrue(acks.isEmpty())
        assertEquals(listOf(Logger.Level.ERROR to "wear.meta.failed"), logs)
    }

    @Test
    fun `an accept that blows up leaves no ack`() = runTest {
        val core = FakeMetaFacade(failAccept = IOException("disk"))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta"), ops)
        assertTrue(acks.isEmpty())
    }

    @Test
    fun `a recording nothing is configured to run is still filed and still acked`() = runTest {
        val core = FakeMetaFacade(enqueued = EnqueueResult.NoWorkflow)

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue", "onJobsDue", "ack"), ops)
        assertEquals(listOf("""{"recordingId":"$ID","ok":true}"""), acks)
        assertEquals(listOf(Logger.Level.WARN to "transfer.enqueued"), logs)
    }

    @Test
    fun `a recording whose parts are already uploaded acks ok and warns`() = runTest {
        val core = FakeMetaFacade(enqueued = EnqueueResult.PartsPurged)

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue", "onJobsDue", "ack"), ops)
        assertTrue(acks.single().contains(""""ok":true"""))
        assertEquals(listOf(Logger.Level.WARN to "transfer.enqueued"), logs)
    }

    @Test
    fun `a resent meta that finds the job already there is still acked ok`() = runTest {
        val core = FakeMetaFacade(enqueued = EnqueueResult.AlreadyDone("job-1"))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "enqueue", "onJobsDue", "ack"), ops)
        assertEquals(listOf("""{"recordingId":"$ID","ok":true}"""), acks)
        assertEquals(listOf(Logger.Level.INFO to "transfer.enqueued"), logs)
    }

    @Test
    fun `an incomplete meta names the parts to resend and never enqueues`() = runTest {
        val missing = Part(
            part = 2,
            track = Track.MONO,
            file = "20260826T010000Z_watch_01J9ABCD_p002_mono.m4a",
            bytes = 1024,
            sha256 = SHA,
            startOffsetSec = 0.0,
            durationSec = 900.0,
        )
        val core = FakeMetaFacade(result = AcceptMetaResult.Incomplete(listOf(missing)))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "ack"), ops)
        assertEquals(
            listOf("""{"recordingId":"$ID","ok":false,"missing":[{"part":2,"track":"mono"}]}"""),
            acks,
        )
    }

    @Test
    fun `an invalid meta is nacked under the path's id`() = runTest {
        val core = FakeMetaFacade(result = AcceptMetaResult.Invalid("malformed meta: boom"))

        MetaAcceptor(core, ack).accept(path, meta(ID))

        assertEquals(listOf("acceptMeta", "ack"), ops)
        assertEquals(
            listOf("""{"recordingId":"$ID","ok":false,"reason":"malformed meta: boom"}"""),
            acks,
        )
    }
}
