package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.testing.CoreFixture
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/** docs/10 "Search". */
class RecordingSearchTest {
    private val f = CoreFixture()

    private fun transcript(recordingId: String, vararg texts: String, createdAt: String = "2026-08-29T03:10:00.000Z") = Transcript(
        recordingId = recordingId, track = Track.MONO, language = "en", provider = TranscriptProvider("assemblyai"),
        createdAt = createdAt, durationSec = 900.0, speakers = listOf(TranscriptSpeaker("S1")),
        segments = texts.mapIndexed { index, text -> TranscriptSegment(index * 10.0, index * 10.0 + 5, "S1", text) },
    )

    @Test
    fun `titles and this device's transcripts are searched, ignoring case and accents`() = runBlocking {
        f.recordAndRun(title = "Weekly Résumé review")

        val byTitle = f.core.search("RESUME", 10).single()
        assertTrue(byTitle.matchesInTitle)
        assertEquals(listOf(SearchRange(7, 6)), byTitle.titleRanges)

        val byText = f.core.search("hello 2", 10).single()
        assertFalse(byText.matchesInTitle)
        assertEquals(listOf(SearchSnippet(1.0, "hello 2", listOf(SearchRange(0, 7)))), byText.snippets)
        assertEquals(emptyList(), f.core.search("nothing like it", 10))
        assertEquals(emptyList(), f.core.search("   ", 10))
    }

    @Test
    fun `full-width Latin finds its ASCII, and a long segment is cut around the match`() = runBlocking {
        val meta = f.recordAndRun()
        val long = "x".repeat(200) + " the budget line " + "y".repeat(200)
        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, long))

        val snippet = f.core.search("ＢＵＤＧＥＴ", 10).single().snippets.single()

        assertTrue(snippet.text.startsWith("…") && snippet.text.endsWith("…"))
        val range = snippet.ranges.single()
        assertEquals("budget", snippet.text.substring(range.offset, range.offset + range.length))
    }

    @Test
    fun `an edit is found at the next search`() = runBlocking {
        val meta = f.recordAndRun()
        assertEquals(emptyList(), f.core.search("roadmap", 10))

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(1, "the roadmap"))

        assertEquals(meta.recordingId, f.core.search("roadmap", 10).single().recordingId)
    }

    @Test
    fun `another device's transcript is read by the pull and searched`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", transcript = { transcript(it.recordingId, "the quarterly budget") })

        f.core.pullRemoteRecordings(force = true)

        val hit = f.core.search("budget", 10).single()
        assertEquals(other.recordingId, hit.recordingId)
        assertEquals(1, f.drive.requests.count { it.method == "GET" && it.query["alt"] == "media" && it.path.endsWith(f.drive.idOf(TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta)))!!) })

        f.clock.advance(kotlin.time.Duration.parse("10m"))
        f.core.pullRemoteRecordings(force = true)
        assertEquals(1, f.drive.requests.count { it.query["alt"] == "media" && it.path.endsWith(f.drive.idOf(TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta)))!!) }, "read once")
    }

    @Test
    fun `a transcript changed on another device is read again when its folder says so`() = runBlocking {
        val other = f.otherDevice(
            "01J9PH0NE10000000000000000",
            transcript = { transcript(it.recordingId, "first words") },
            folderProperties = mapOf("transcriptAt" to "2026-08-29T03:10:00.000Z"),
        )
        f.core.pullRemoteRecordings(force = true)
        assertEquals(1, f.core.search("first", 10).size)

        val name = TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta))
        val edited = transcript(other.recordingId, "edited words").copy(editedAt = "2026-08-30T00:00:00.000Z")
        f.drive.overwrite(f.drive.idOf(name)!!, recJson.encodeToString(edited).encodeToByteArray())
        f.drive.files.getValue(other.folderId).appProperties += mapOf("transcriptAt" to "2026-08-30T00:00:00.000Z")
        f.core.pullRemoteRecordings(force = true)

        assertEquals(emptyList(), f.core.search("first", 10))
        assertEquals(1, f.core.search("edited", 10).size)
        assertEquals("edited words", f.core.results(other.recordingId).transcript!!.segments.single().text)
    }

    @Test
    fun `this device's newer transcript is never replaced by an older one in its folder`() = runBlocking {
        val meta = f.recordAndRun()
        val folder = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!
        f.drive.files.getValue(folder).appProperties += mapOf("transcriptAt" to "2000-01-01T00:00:00.000Z")

        f.core.pullRemoteRecordings(force = true)

        assertEquals("hello 1", f.core.results(meta.recordingId).transcript!!.segments.first().text)
    }

    @Test
    fun `the limit counts recordings`() = runBlocking {
        f.record(id = "01J9AAAAAA0000000000000000", title = "plan one")
        f.record(id = "01J9BBBBBB0000000000000000", title = "plan two", startedAt = "2026-08-26T02:00:00.000Z")

        assertEquals(listOf("plan two"), f.core.search("plan", 1).map { it.title })
        assertEquals(2, f.core.search("plan", 10).size)
    }
}
