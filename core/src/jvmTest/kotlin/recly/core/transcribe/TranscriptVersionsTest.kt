package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine

/** docs/08 "Versions". */
class TranscriptVersionsTest {
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true))

    private suspend fun kinds(recordingId: String) = f.core.transcriptVersions(recordingId).map { it.kind }

    @Test
    fun `every change keeps a version, and a restore brings one back as a version of its own`() = runBlocking {
        val meta = f.recordAndRun()
        val first = f.core.transcriptVersions(meta.recordingId).single()
        assertEquals(TranscriptVersionKind.TRANSCRIBED, first.kind)
        assertTrue(first.current)

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "edited once"))
        f.core.editTranscript(meta.recordingId, TranscriptEdit.RenameSpeaker("S2", "Minsu"))
        val versions = f.core.transcriptVersions(meta.recordingId)
        assertEquals(listOf(TranscriptVersionKind.EDITED, TranscriptVersionKind.EDITED, TranscriptVersionKind.TRANSCRIBED), versions.map { it.kind })
        assertEquals(listOf(true, false, false), versions.map { it.current })

        val restored = assertIs<EditResult.Edited>(f.core.restoreTranscript(meta.recordingId, first.id)).transcript
        assertEquals("hello 1", restored.segments[0].text)
        assertTrue(restored.speakers.all { it.name == null })
        assertEquals(TranscriptVersionKind.RESTORED, kinds(meta.recordingId).first())
        f.core.awaitPushes()
        val base = MetaWriter.baseName(meta)
        val folder = recJson.decodeFromString<Transcript>(f.drive.byName(TranscribeRunner.jsonFileName(base))!!.content.decodeToString())
        assertEquals("hello 1", folder.segments[0].text, "the folder's copy follows")
        assertEquals(restored.editedAt, f.drive.byName(base)!!.appProperties["transcriptAt"], "and is the newest there")

        assertIs<EditResult.Edited>(f.core.restoreTranscript(meta.recordingId, first.id))
        assertEquals(4, f.core.transcriptVersions(meta.recordingId).size, "bringing back what is shown keeps nothing new")
        assertIs<EditResult.Invalid>(f.core.restoreTranscript(meta.recordingId, "999"))
        assertIs<EditResult.Invalid>(f.core.restoreTranscript(meta.recordingId, "../index"))
        Unit
    }

    @Test
    fun `a transcription again keeps the edited transcript it replaces`() = runBlocking {
        val meta = f.recordAndRun()
        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "my words"))
        assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        f.drain()

        assertEquals(
            listOf(TranscriptVersionKind.TRANSCRIBED, TranscriptVersionKind.EDITED, TranscriptVersionKind.TRANSCRIBED),
            kinds(meta.recordingId),
        )
        val edited = f.core.transcriptVersions(meta.recordingId)[1]
        assertEquals("my words", assertIs<EditResult.Edited>(f.core.restoreTranscript(meta.recordingId, edited.id)).transcript.segments[0].text)
    }

    @Test
    fun `a transcript from before versions is kept at the first change, and only the newest twenty stay`() = runBlocking {
        val meta = f.recordAndRun()
        val versions = f.dirOf(meta) / TranscriptVersions.DIR
        f.fs.deleteRecursively(versions)
        assertEquals(TranscriptVersionKind.TRANSCRIBED, kinds(meta.recordingId).single(), "the copy shown is kept when listed")
        f.fs.deleteRecursively(versions)

        repeat(TranscriptVersions.MAX + 5) { f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "edit $it")) }

        val kept = f.core.transcriptVersions(meta.recordingId)
        assertEquals(TranscriptVersions.MAX, kept.size)
        assertEquals("edit ${TranscriptVersions.MAX + 4}", TranscriptVersions.read(f.fs, f.dirOf(meta), kept.first().id)!!.segments[0].text)
        assertTrue(kept.none { it.kind == TranscriptVersionKind.TRANSCRIBED }, "the oldest went")
        assertEquals(TranscriptVersions.MAX + 1, f.fs.list(versions).size, "one file a version, and the index")
        assertNull(TranscriptVersions.read(f.fs, f.dirOf(meta), "1"))
    }
}
