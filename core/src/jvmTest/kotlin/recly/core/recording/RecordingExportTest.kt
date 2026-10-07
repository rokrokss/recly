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
    fun `a title no file system would take is made safe`() {
        val meta = testMeta(title = "  a<b>c*?  .. ", timezone = "Not/AZone")

        assertEquals("2026-08-26 a b c.txt", RecordingExport.fileName(meta, "txt"))
    }
}
