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
    fun `a summary line is found too`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly")
        f.core.summaries.save(recly.core.chatgpt.Summary(meta.recordingId, "Summary\n- The Budget moves to Q3.", "gpt-a", "2026-08-26T02:00:00.000Z"))

        val hit = f.core.search("budget", 10).single()
        assertEquals(SummaryMatch("The Budget moves to Q3.", listOf(SearchRange(4, 6))), hit.summary)
        assertEquals(emptyList(), hit.snippets)
        assertFalse(hit.matchesInTitle)
        assertEquals(null, f.core.search("hello 1", 10).single().summary, "a transcript hit says nothing of the summary")
    }

    @Test
    fun `a find bar finds in a text what the search found, at the text's own places`() {
        fun found(text: String, query: String) = RecordingSearch.findRanges(text, query).map { text.substring(it.offset, it.offset + it.length) }

        assertEquals(listOf("Résumé", "RESUME"), found("A Résumé, the RESUME", " resume "))
        assertEquals(listOf("ＢＵＤＧＥＴ", "budget"), found("ＢＵＤＧＥＴ and budget", "Budget"))
        assertEquals(listOf("abc"), found("x abc", "ＡＢＣ"))
        assertEquals(listOf("회의록", "회의록"), found("회의록 정리, 다음 회의록", "회의록"))
        assertEquals(listOf(SearchRange(0, 2), SearchRange(2, 2)), RecordingSearch.findRanges("aaaa", "aa"), "in order, not overlapping")
        assertEquals(emptyList(), RecordingSearch.findRanges("anything", "   "))
        assertEquals(emptyList(), RecordingSearch.findRanges("anything", ""))
        assertEquals(emptyList(), RecordingSearch.findRanges("Résumé", "resumes"))
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
    fun `a transcript that could not be read is read again by the next pull`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", transcript = { transcript(it.recordingId, "the quarterly budget") })
        val fileId = f.drive.idOf(TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta)))!!
        f.drive.failNext(500, times = 10) { it.query["alt"] == "media" && it.path.endsWith(fileId) }

        f.core.pullRemoteRecordings(force = true)
        assertEquals(emptyList(), f.core.search("budget", 10))

        f.drive.clearFaults()
        f.clock.advance(kotlin.time.Duration.parse("10m"))
        f.core.pullRemoteRecordings(force = true)
        assertEquals(other.recordingId, f.core.search("budget", 10).single().recordingId)
    }

    @Test
    fun `a folder that says it has a transcript is looked in again until it is there`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", folderProperties = mapOf("transcriptAt" to "2026-08-29T03:10:00.000Z"))
        val name = TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta))
        f.core.pullRemoteRecordings(force = true)

        val late = transcript(other.recordingId, "late words")
        f.drive.put(name, other.folderId, recJson.encodeToString(late).encodeToByteArray(), "application/json")
        f.clock.advance(kotlin.time.Duration.parse("10m"))
        f.core.pullRemoteRecordings(force = true)

        assertEquals(other.recordingId, f.core.search("late", 10).single().recordingId)
    }

    @Test
    fun `a folder with no transcript and no stamp is looked in once`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000")
        val name = TranscribeRunner.jsonFileName(MetaWriter.baseName(other.meta))
        fun lookups() = f.drive.requests.count { name in it.query["q"].orEmpty() }

        f.core.pullRemoteRecordings(force = true)
        assertEquals(1, lookups())
        f.clock.advance(kotlin.time.Duration.parse("10m"))
        f.core.pullRemoteRecordings(force = true)
        assertEquals(1, lookups(), "a folder without a transcript costs a pass nothing after the first")
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
    fun `a preview is the transcript's first words, and a recording without a transcript has none`() = runBlocking {
        val meta = f.recordAndRun()
        val bare = f.record(id = "01J9BBBBBB0000000000000000", startedAt = "2026-08-26T02:00:00.000Z")

        assertEquals(
            mapOf(meta.recordingId to "hello 1 hello 2"),
            f.core.previews(listOf(meta.recordingId, bare.recordingId, "01J9NOTHERE000000000000000", meta.recordingId)),
        )
        assertEquals(emptyMap(), f.core.previews(emptyList()))
    }

    @Test
    fun `a preview collapses whitespace and follows an edit`() = runBlocking {
        val meta = f.recordAndRun()
        assertEquals("hello 1 hello 2", f.core.previews(listOf(meta.recordingId))[meta.recordingId])

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "  the\n\nnew \t plan  "))

        assertEquals("the new plan hello 2", f.core.previews(listOf(meta.recordingId))[meta.recordingId])
    }

    @Test
    fun `a long preview is cut at a word, or at 120 characters when there is no word to cut at`() = runBlocking {
        val meta = f.recordAndRun()
        suspend fun preview() = f.core.previews(listOf(meta.recordingId)).getValue(meta.recordingId)

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "word ".repeat(30)))
        assertEquals(List(24) { "word" }.joinToString(" ") + "…", preview())

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "x".repeat(200)))
        assertEquals("x".repeat(120) + "…", preview())

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "ab " + "x".repeat(200)))
        assertEquals("ab " + "x".repeat(117) + "…", preview(), "a word boundary that early would leave almost nothing")

        f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(0, "a".repeat(112)))
        assertEquals("a".repeat(112) + " hello 2", preview(), "exactly 120 characters are not cut")
    }

    @Test
    fun `another device's transcript has a preview once a pull has read it, and a blank one has none`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", transcript = { transcript(it.recordingId, "the quarterly budget", "and more") })
        val blank = f.otherDevice("01J9PH0NE20000000000000000", transcript = { transcript(it.recordingId, " ", "\n") })

        f.core.pullRemoteRecordings(force = true)

        assertEquals(
            mapOf(other.recordingId to "the quarterly budget and more"),
            f.core.previews(listOf(other.recordingId, blank.recordingId)),
        )
    }

    @Test
    fun `the limit counts recordings`() = runBlocking {
        f.record(id = "01J9AAAAAA0000000000000000", title = "plan one")
        f.record(id = "01J9BBBBBB0000000000000000", title = "plan two", startedAt = "2026-08-26T02:00:00.000Z")

        assertEquals(listOf("plan two"), f.core.search("plan", 1).map { it.title })
        assertEquals(2, f.core.search("plan", 10).size)
    }
}
