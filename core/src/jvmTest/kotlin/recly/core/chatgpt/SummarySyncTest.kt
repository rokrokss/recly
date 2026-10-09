package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.transcribe.providerJson

/** docs/08 "Summaries": a summary goes to the recording's folder, and comes back from it on the other devices. */
class SummarySyncTest {
    private val f = CoreFixture()

    private fun summary(id: String, text: String, at: String = "2026-08-26T02:00:00.000Z", edited: String? = null) =
        Summary(id, text, "gpt-a", at, edited)

    private fun json(summary: Summary) = providerJson.encodeToString(Summary.serializer(), summary)

    private fun inFolder(base: String) = f.drive.byName(SummaryFile.name(base))?.content?.decodeToString()?.let(SummaryFile::decode)

    @Test
    fun `a summary made here goes up to the recording's folder`() = runBlocking {
        val meta = f.recordAndRun()
        f.core.summaries.save(summary(meta.recordingId, "Notes"))
        f.core.awaitPushes()
        val folderId = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!
        assertEquals("Notes", inFolder(MetaWriter.baseName(meta))?.text)
        assertEquals(folderId, f.drive.byName(SummaryFile.name(MetaWriter.baseName(meta)))!!.parents.single())
        assertEquals("2026-08-26T02:00:00.000Z", f.drive.files[folderId]!!.appProperties[SummaryFile.STAMP])
        assertTrue(f.core.recordings.pendingSummaries().isEmpty())
    }

    @Test
    fun `an edit replaces it here and in the folder`() = runBlocking {
        val meta = f.recordAndRun()
        f.core.summaries.save(summary(meta.recordingId, "Notes"))
        f.core.awaitPushes()
        f.clock.advance(1.minutes)
        val edited = (f.core.summaries.edit(meta.recordingId, "  My notes  ") as SummaryState.Ready).summary
        assertEquals("My notes", edited.text)
        assertEquals("gpt-a", edited.model, "still ChatGPT's summary, edited")
        f.core.awaitPushes()
        assertEquals(edited, inFolder(MetaWriter.baseName(meta)))
        val folderId = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!
        assertEquals(edited.editedAt, f.drive.files[folderId]!!.appProperties[SummaryFile.STAMP])
        assertEquals(SummaryState.Ready(edited), f.core.summaries.edit(meta.recordingId, "My notes"), "unchanged text writes nothing")
        assertEquals(SummaryState.Ready(edited), f.core.summaries.edit(meta.recordingId, "   "), "an empty summary is not a summary")
    }

    @Test
    fun `another device's summary is read at the next pull`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", folderProperties = mapOf(SummaryFile.STAMP to "2026-08-26T03:00:00.000Z"))
        f.drive.put(
            SummaryFile.name(MetaWriter.baseName(other.meta)), other.folderId,
            json(summary(other.recordingId, "From the phone", "2026-08-26T03:00:00.000Z")).encodeToByteArray(), SummaryFile.MIME,
        )
        f.core.pullRemoteRecordings(force = true)
        assertEquals("From the phone", (f.core.summaries.state(other.recordingId) as SummaryState.Ready).summary.text)
        assertTrue("remote.summary.read" in f.logger.events)
    }

    @Test
    fun `an edit here that has not gone up yet is kept over the folder's`() = runBlocking {
        val meta = f.recordAndRun()
        f.core.summaries.save(summary(meta.recordingId, "Notes"))
        f.core.awaitPushes()
        val folderId = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!
        val base = MetaWriter.baseName(meta)
        // Another device edits; this one edits too, offline.
        f.drive.files[f.drive.idOf(SummaryFile.name(base))!!]!!.content =
            json(summary(meta.recordingId, "Theirs", edited = "2026-08-26T02:10:00.000Z")).encodeToByteArray()
        f.drive.files[folderId]!!.appProperties += (SummaryFile.STAMP to "2026-08-26T02:10:00.000Z")
        f.drive.failNext(500, times = 10) { it.query["uploadType"] == "multipart" || it.method == "PATCH" }
        f.clock.advance(20.minutes)
        f.core.summaries.edit(meta.recordingId, "Mine")
        f.core.awaitPushes()
        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", (f.core.summaries.state(meta.recordingId) as SummaryState.Ready).summary.text)
        // Back online: the edit goes up.
        f.drive.clearFaults()
        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", inFolder(base)?.text)
    }

    @Test
    fun `a recording opened before a pull reads its folder's summary`() = runBlocking {
        val meta = f.recordAndRun()
        val folderId = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!
        f.drive.put(
            SummaryFile.name(MetaWriter.baseName(meta)), folderId,
            json(summary(meta.recordingId, "Made on the Mac")).encodeToByteArray(), SummaryFile.MIME,
        )
        assertEquals("Made on the Mac", (f.core.summaries.state(meta.recordingId) as SummaryState.Ready).summary.text)
    }
}
