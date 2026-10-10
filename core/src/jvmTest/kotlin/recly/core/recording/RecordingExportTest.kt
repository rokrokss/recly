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
    fun `the summary goes out as text of its own and inside the Markdown`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly")
        assertNull(f.core.exportFile(meta.recordingId, ExportFormat.SUMMARY), "no summary yet")
        f.core.summaries.save(recly.core.chatgpt.Summary(meta.recordingId, "Summary\nWe ship.\nAction items\n- Mina: notes [00:00:01]\n- Joon: deck", "gpt-a", "2026-08-26T02:00:00.000Z"))

        val text = f.core.exportFile(meta.recordingId, ExportFormat.SUMMARY)!!.toPath()
        assertEquals("2026-08-26 Weekly.summary.txt", text.name)
        assertEquals("Summary\nWe ship.\nAction items\n- Mina: notes [00:00:01]\n- Joon: deck\n", f.fs.read(text) { readUtf8() })
        val md = f.fs.read(f.core.exportFile(meta.recordingId, ExportFormat.MD)!!.toPath()) { readUtf8() }
        assertTrue(
            "\n## Summary\n\nSummary\n\nWe ship.\n\nAction items\n\n- Mina: notes [00:00:01]\n- Joon: deck\n\n## Transcript\n\n[00:00:00] hello 1" in md,
            md,
        )
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
