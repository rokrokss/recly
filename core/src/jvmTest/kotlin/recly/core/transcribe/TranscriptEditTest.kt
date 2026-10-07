@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import recly.core.job.JobStatus
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine

/** docs/08 "Editing". */
class TranscriptEditTest {
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true))

    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(File("../spec/transcript.schema.json").readText(), SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())

    private fun assertSchemaValid(transcript: Transcript) {
        val errors = schema.validate(recJson.encodeToString(transcript), InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    private fun driveText(name: String): Pair<String, Map<String, String>> =
        f.drive.byName(name)!!.let { it.content.decodeToString() to it.appProperties }

    @Test
    fun `an edit is saved here, observed, and sent to Drive marked edited`() = runBlocking {
        val meta = f.recordAndRun(participants = 2)
        val base = MetaWriter.baseName(meta)
        assertEquals(2, f.engine!!.requests.single().expectedSpeakers, "the head count reaches a diarizing engine")
        val seen = Channel<RecordingResult>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Unconfined) { f.core.observeResults(meta.recordingId).collect { seen.send(it) } }
        try {
            assertEquals("hello 1", withTimeout(5000) { seen.receive() }.transcript!!.segments[0].text)

            val result = f.core.editTranscript(
                meta.recordingId,
                TranscriptEdit.Batch(
                    listOf(
                        TranscriptEdit.SetText(0, "  Hello there. "),
                        TranscriptEdit.RenameSpeaker("S2", "Minsu"),
                    ),
                ),
            )

            val edited = assertIs<EditResult.Edited>(result).transcript
            assertEquals("Hello there.", edited.segments[0].text)
            assertNotNull(edited.editedAt)
            assertEquals(edited, withTimeout(5000) { seen.receive() }.transcript, "the open detail hears the edit")
        } finally {
            watching.cancel()
            seen.close()
        }
        val (text, marks) = driveText(TranscribeRunner.textFileName(base))
        assertEquals("[00:00:00] S1: Hello there.\n[00:00:01] Minsu: hello 2\n", text)
        assertEquals("edited", marks["reclyTranscript"])
        assertEquals("edited", driveText(TranscribeRunner.jsonFileName(base)).second["reclyTranscript"])
        val local = f.core.results(meta.recordingId).transcript!!
        assertEquals(local.editedAt, f.drive.byName(base)!!.appProperties["transcriptAt"])
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts())
        assertSchemaValid(local)
    }

    @Test
    fun `speakers are reassigned, added and renamed, and the unused ones leave the list`() = runBlocking {
        val meta = f.recordAndRun()

        val edited = assertIs<EditResult.Edited>(
            f.core.editTranscript(
                meta.recordingId,
                TranscriptEdit.Batch(
                    listOf(
                        TranscriptEdit.SetSpeaker(1, null),
                        TranscriptEdit.SetSpeaker(0, "S3"),
                        TranscriptEdit.RenameSpeaker("S3", "Ji-won"),
                    ),
                ),
            ),
        ).transcript

        assertEquals(listOf("S3", "S3"), edited.segments.map { it.speaker })
        assertEquals(listOf(TranscriptSpeaker("S3", "Ji-won")), edited.speakers)
        assertSchemaValid(edited)
    }

    @Test
    fun `an on-device transcript without speakers gets them from the first one named`() = runBlocking {
        f.engine!!.supportsDiarization = false
        val meta = f.recordAndRun()
        assertEquals("unavailable", f.core.results(meta.recordingId).transcript!!.speakerIdentification)

        val first = assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.SetSpeaker(1, null))).transcript
        assertEquals("identified", first.speakerIdentification)
        assertEquals(listOf("S1", "S1"), first.segments.map { it.speaker }, "the first speaker covers the whole transcript")
        assertSchemaValid(first)

        val second = assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.SetSpeaker(1, null))).transcript
        assertEquals(listOf("S1", "S2"), second.segments.map { it.speaker })
        assertSchemaValid(second)
    }

    @Test
    fun `a text edit drops that segment's word timings`() {
        val transcript = Transcript(
            recordingId = CoreFixture.ID, track = Track.MONO, language = "ko", provider = TranscriptProvider("assemblyai"),
            createdAt = "2026-08-29T03:10:00.000Z", durationSec = 10.0, speakers = listOf(TranscriptSpeaker("S1")),
            segments = listOf(
                TranscriptSegment(0.0, 1.0, "S1", "a", listOf(TranscriptWord(0.0, 1.0, "a"))),
                TranscriptSegment(1.0, 2.0, "S1", "b", listOf(TranscriptWord(1.0, 2.0, "b"))),
            ),
        )

        val edited = TranscriptEdits.apply(transcript, TranscriptEdit.SetText(0, "c"), "2026-08-29T04:00:00.000Z")

        assertNull(edited.segments[0].words)
        assertEquals(transcript.segments[1], edited.segments[1])
        assertEquals("2026-08-29T04:00:00.000Z", edited.editedAt)
        assertEquals(transcript, TranscriptEdits.apply(transcript, TranscriptEdit.SetText(1, " b "), "later"), "no change, no stamp")
    }

    @Test
    fun `a transcript with no segments keeps its one speaker through a rename`() {
        val empty = Transcript(
            recordingId = CoreFixture.ID, track = Track.MONO, language = "ko", provider = TranscriptProvider("assemblyai"),
            createdAt = "2026-08-29T03:10:00.000Z", durationSec = 10.0, speakers = listOf(TranscriptSpeaker("S1")), segments = emptyList(),
        )

        val renamed = TranscriptEdits.apply(empty, TranscriptEdit.RenameSpeaker("S1", "Minsu"), "2026-08-29T04:00:00.000Z")

        assertEquals(listOf(TranscriptSpeaker("S1", "Minsu")), renamed.speakers)
        assertSchemaValid(renamed)
    }

    @Test
    fun `an edit that does not fit the transcript saves nothing`() = runBlocking {
        val meta = f.recordAndRun()
        val before = f.core.results(meta.recordingId).transcript

        assertIs<EditResult.Invalid>(f.core.editTranscript(meta.recordingId, TranscriptEdit.SetSpeaker(0, "S9")))
        assertIs<EditResult.Invalid>(
            f.core.editTranscript(meta.recordingId, TranscriptEdit.Batch(listOf(TranscriptEdit.SetText(0, "x"), TranscriptEdit.SetText(7, "y")))),
        )

        assertEquals(before, f.core.results(meta.recordingId).transcript)
        assertEquals(EditResult.NoTranscript, f.core.editTranscript("01J9N0THERE000000000000000", TranscriptEdit.SetText(0, "x")))
    }

    @Test
    fun `an edit waits for a transcription that is queued or running`() = runBlocking {
        val meta = f.recordAndRun()
        assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))

        assertEquals(EditResult.Busy, f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "x")))
    }

    @Test
    fun `another device's transcript is edited here and in its folder`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", transcript = { meta -> sample(meta.recordingId) })
        f.core.pullRemoteRecordings(force = true)

        assertIs<EditResult.Edited>(f.core.editTranscript(other.recordingId, TranscriptEdit.RenameSpeaker("S1", "Minsu")))

        f.core.awaitPushes()

        val base = MetaWriter.baseName(other.meta)
        assertEquals("[00:00:00] Minsu: theirs\n", driveText(TranscribeRunner.textFileName(base)).first)
        assertEquals(other.folderId, f.drive.files.entries.single { it.value.name == TranscribeRunner.textFileName(base) }.value.parents.single())
    }

    @Test
    fun `an edit that cannot reach Drive stays pending and goes with the next pull`() = runBlocking {
        val meta = f.recordAndRun()
        f.drive.failNext(500) { it.query["uploadType"] == "multipart" }

        assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "offline")))

        f.core.awaitPushes()
        assertEquals(setOf(meta.recordingId), f.core.recordings.pendingTranscripts().keys)

        f.core.pullRemoteRecordings(force = true)

        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts())
        assertTrue("offline" in driveText(TranscribeRunner.textFileName(MetaWriter.baseName(meta))).first)
    }

    /** The retry of a failed publication sends the edit made since, not the result it failed to send. */
    @Test
    fun `retrying a publication that failed keeps an edit made after it`() = runBlocking {
        val meta = f.record()
        val base = MetaWriter.baseName(meta)
        f.drive.failNext(400) { it.uploadType == "multipart" && TranscribeRunner.jsonFileName(base) in it.body.decodeToString() }
        f.core.enqueue(meta.recordingId)
        f.drain()
        val job = f.core.jobs.list().single()
        assertEquals(JobStatus.FAILED, job.status)

        assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "kept")))

        f.core.awaitPushes()
        f.core.jobs.retry(job.id)
        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single().status)
        val (text, marks) = driveText(TranscribeRunner.textFileName(base))
        assertTrue(text.startsWith("[00:00:00] S1: kept"), text)
        assertEquals("edited", marks["reclyTranscript"])
    }

    private fun sample(recordingId: String) = Transcript(
        recordingId = recordingId, track = Track.MONO, language = "ko", provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-08-29T03:10:00.000Z", durationSec = 900.0, speakers = listOf(TranscriptSpeaker("S1")),
        segments = listOf(TranscriptSegment(0.0, 1.0, "S1", "theirs")),
    )
}
