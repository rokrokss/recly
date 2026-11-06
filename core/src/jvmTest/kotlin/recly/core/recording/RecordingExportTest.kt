@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import recly.core.job.Retention
import recly.core.model.recJson
import recly.core.notes.NoteEvidence
import recly.core.notes.NoteKind
import recly.core.notes.NotesFile
import recly.core.notes.NotesHeadings
import recly.core.notes.StoredNote
import recly.core.testing.CoreFixture
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.testMeta

/** docs/08 "Exports". */
class RecordingExportTest {
    private val f = CoreFixture()

    @Test
    fun `each transcript format is written under a name for people`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly: sync/plan")

        val srt = f.core.exportFile(meta.recordingId, ExportFormat.SRT)!!.toPath()
        assertEquals("2026-08-26 Weekly sync plan.srt", srt.name, "the date is the recording's own, in Seoul")
        assertTrue(f.fs.read(srt) { readUtf8() }.startsWith("1\n00:00:00,000 --> 00:00:01,000\nhello 1"))
        assertTrue(f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.VTT)!!.toPath()) { readUtf8() }.startsWith("WEBVTT"))
        assertEquals("[00:00:00] hello 1 hello 2\n", f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.TXT)!!.toPath()) { readUtf8() })
        assertTrue(f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.MD)!!.toPath()) { readUtf8() }.startsWith("---\ntitle: \"Weekly: sync/plan\""))
    }

    @Test
    fun `the audio is the playback track in one file, fetched back when the sweep took it`() = runBlocking {
        val meta = f.recordAndRun()
        f.clock.advance(Retention.WINDOW)
        f.core.runDueJobs(f.clock.now())
        assertFalse(f.fs.exists(f.dirOf(meta) / meta.parts.single().file))

        val audio = f.core.exportFile(meta.recordingId, ExportFormat.AUDIO)!!.toPath()

        assertEquals("${MetaWriter.baseName(meta)}.m4a", audio.name, "no title: the recording's own name")
        assertEquals(SEEDED_AUDIO, f.fs.read(audio) { readUtf8() })
    }

    @Test
    fun `the notes go out as text of their own and inside the Markdown, under the app's headings`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly")
        assertNull(f.core.exportFile(meta.recordingId, ExportFormat.NOTES), "no notes yet")
        fun note(id: String, kind: NoteKind, text: String, atSec: Double, due: String? = null) = StoredNote(
            id, kind, text, due = due, evidence = NoteEvidence(atSec, "hello 1"), provisional = false, createdAt = "2026-08-26T02:00:00.000Z",
        )
        val notes = NotesFile(
            recordingId = meta.recordingId, stage = NotesFile.FINAL, overview = "We ship.",
            items = listOf(
                note("01J9N0TE000000000000000001", NoteKind.TODO, "Send the deck", 65.0, due = "Monday"),
                note("01J9N0TE000000000000000002", NoteKind.DECISION, "Ship on Friday", 1.0),
            ),
            createdAt = "2026-08-26T02:00:00.000Z", updatedAt = "2026-08-26T02:00:00.000Z", finalizedAt = "2026-08-26T02:00:00.000Z",
        )
        f.fs.write(f.dirOf(meta) / NotesFile.name(MetaWriter.baseName(meta))) { writeUtf8(recJson.encodeToString(NotesFile.serializer(), notes)) }

        val text = f.core.exportFile(meta.recordingId, ExportFormat.NOTES)!!.toPath()
        assertEquals("2026-08-26 Weekly.notes.txt", text.name)
        assertEquals("We ship.\n\nDecisions\n- Ship on Friday [00:00:01]\n\nTo-dos\n- Send the deck · Monday [00:01:05]\n", f.fs.read(text) { readUtf8() })
        val md = f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.MD)!!.toPath()) { readUtf8() }
        assertTrue(
            "\n## Notes\n\nWe ship.\n\n### Decisions\n\n- Ship on Friday [00:00:01]\n\n### To-dos\n\n- Send the deck · Monday [00:01:05]\n\n## Transcript\n\n[00:00:00] hello 1" in md,
            md,
        )

        f.core.notes.setHeadings(NotesHeadings(notes = "노트", decisions = "결정", todos = "할 일"))
        assertTrue(f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.NOTES)!!.toPath()) { readUtf8() }.startsWith("We ship.\n\n결정\n"))
        assertTrue("\n## 노트\n\nWe ship.\n\n### 결정\n" in f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.MD)!!.toPath()) { readUtf8() })
    }

    @Test
    fun `nothing to export is null`() = runBlocking {
        val meta = f.record()

        assertNull(f.core.exportFile(meta.recordingId, ExportFormat.TXT), "no transcript yet")
        assertNull(f.core.exportFile("01J9N0THERE000000000000000", ExportFormat.AUDIO))
    }

    @Test
    fun `earlier exports are cleared by later ones`() = runBlocking {
        val meta = f.recordAndRun()
        val first = f.core.exportFile(meta.recordingId, ExportFormat.TXT)!!.toPath()
        val second = f.core.exportFile(meta.recordingId, ExportFormat.TXT)!!.toPath()
        assertTrue(f.fs.exists(first), "a share sheet may still be reading it")

        f.clock.advance(2.hours)
        f.core.exportFile(meta.recordingId, ExportFormat.SRT)

        assertFalse(f.fs.exists(first))
        assertFalse(f.fs.exists(second))
    }

    @Test
    fun `a long title is cut to what a file name holds in bytes, never inside a character`() {
        val korean = testMeta(title = "회".repeat(100))
        val name = RecordingExport.fileName(korean, "txt")
        assertTrue(name.encodeToByteArray().size <= 255, "${name.encodeToByteArray().size} bytes")
        assertEquals("2026-08-26 ${"회".repeat(80)}.txt", name, "80 characters of 3 bytes fill the 240 bytes beside the date and the extension")

        assertEquals(
            "2026-08-26 ${"회".repeat(79)}.txt", RecordingExport.fileName(testMeta(title = "회".repeat(79) + "😀"), "txt"),
            "4 more bytes would pass 240: the pair goes whole",
        )

        val emoji = testMeta(title = "a".repeat(99) + "😀b")
        assertEquals("2026-08-26 ${"a".repeat(99)}.m4a", RecordingExport.fileName(emoji, "m4a"), "the pair would end past 100 characters")
        val fits = testMeta(title = "😀".repeat(70))
        val cut = RecordingExport.fileName(fits, "srt")
        assertTrue(cut.encodeToByteArray().size <= 255)
        assertEquals("2026-08-26 ${"😀".repeat(50)}.srt", cut, "50 pairs are 100 characters and 200 bytes")

        assertEquals("2026-08-26 ${"a".repeat(100)}.md", RecordingExport.fileName(testMeta(title = "a".repeat(120)), "md"))
    }

    @Test
    fun `a title no file system would take is made safe`() {
        val meta = testMeta(title = "  a<b>c*?  .. ", timezone = "Not/AZone")

        assertEquals("2026-08-26 a b c.txt", RecordingExport.fileName(meta, "txt"))
    }
}
