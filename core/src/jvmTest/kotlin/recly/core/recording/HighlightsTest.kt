@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.testing.CoreFixture
import recly.core.testing.START
import recly.core.testing.testMeta
import recly.core.testing.testPart
import recly.core.transfer.AcceptMetaResult

/** docs/03 "Metadata": marks made while recording, edited later, and carried wherever the meta goes. */
class HighlightsTest {
    private val f = CoreFixture()

    private fun metaOnDisk(meta: RecordingMeta): RecordingMeta =
        recJson.decodeFromString(f.fs.read(f.dirOf(meta) / MetaWriter.metaFileName(MetaWriter.baseName(meta))) { readUtf8() })

    private fun metaOnDrive(meta: RecordingMeta): RecordingMeta =
        recJson.decodeFromString(f.drive.byName(MetaWriter.metaFileName(MetaWriter.baseName(meta)))!!.content.decodeToString())

    @Test
    fun `a mark made while recording is in the row and the file, once per second, and survives the stop`() = runBlocking {
        val meta = testMeta(parts = emptyList())
        f.core.recordings.create(meta, f.dirOf(meta))

        assertTrue(f.core.recordings.addHighlight(meta.recordingId, 12.0))
        assertFalse(f.core.recordings.addHighlight(meta.recordingId, 12.6), "a double tap is one mark")
        assertTrue(f.core.recordings.addHighlight(meta.recordingId, 3.0))
        assertFalse(f.core.recordings.addHighlight(meta.recordingId, -1.0))
        f.core.recordings.addPart(meta.recordingId, testPart(meta, 1))
        f.core.recordings.finalize(meta.recordingId, START, 900.0)

        assertEquals(listOf(3.0, 12.0), f.core.recordings.get(meta.recordingId)!!.meta.highlights.map { it.atSec })
        assertEquals(listOf(3.0, 12.0), metaOnDisk(meta).highlights.map { it.atSec })
        assertEquals(emptyMap(), f.core.recordings.pendingMeta(), "a recording still going has nothing to send yet")
    }

    @Test
    fun `a meta without highlights is written without the field`() = runBlocking {
        val meta = f.record()

        assertFalse("highlights" in f.fs.read(f.dirOf(meta) / MetaWriter.metaFileName(MetaWriter.baseName(meta))) { readUtf8() })
    }

    @Test
    fun `highlights edited after the upload reach the meta on Drive`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly")

        assertTrue(f.core.setHighlights(meta.recordingId, listOf(30.0, 5.0, 5.4)))

        assertEquals(listOf(5.0, 30.0), metaOnDrive(meta).highlights.map { it.atSec })
        assertEquals(emptyMap(), f.core.recordings.pendingMeta())
    }

    @Test
    fun `highlights that cannot reach Drive stay pending and go with the next pull`() = runBlocking {
        val meta = f.recordAndRun()
        f.drive.failNext(500) { it.uploadType == "media" }

        assertTrue(f.core.setHighlights(meta.recordingId, listOf(1.0)))
        assertEquals(setOf(meta.recordingId), f.core.recordings.pendingMeta().keys)
        assertEquals(emptyList(), metaOnDrive(meta).highlights)

        f.core.pullRemoteRecordings(force = true)

        assertEquals(listOf(1.0), metaOnDrive(meta).highlights.map { it.atSec })
        assertEquals(emptyMap(), f.core.recordings.pendingMeta())
    }

    @Test
    fun `another device's recording takes highlights too`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000")
        f.core.pullRemoteRecordings(force = true)

        assertTrue(f.core.setHighlights(other.recordingId, listOf(42.0)))

        val onDrive = recJson.decodeFromString<RecordingMeta>(
            f.drive.files.getValue(f.drive.idOf(MetaWriter.metaFileName(MetaWriter.baseName(other.meta)))!!).content.decodeToString(),
        )
        assertEquals(listOf(42.0), onDrive.highlights.map { it.atSec })
        assertEquals(other.meta.parts, onDrive.parts, "the rest of their meta is left as it was")
    }

    /**
     * docs/03 "Watch → phone transfer contract": the watch records through the same core, and the phone
     * files the meta the watch sent — so a mark made on the wrist is on the phone's row.
     */
    @Test
    fun `highlights made on a watch arrive on the phone`() = runBlocking {
        val watch = CoreFixture(engine = null, platform = Platform.WEAROS)
        val draft = testMeta(source = Source.WATCH, parts = emptyList())
        val watchDir = "/data/recordings/${MetaWriter.baseName(draft)}".toPath()
        watch.core.recordings.create(draft, watchDir)
        watch.core.recordings.addHighlight(draft.recordingId, 61.0)
        val part = testPart(draft, 1)
        watch.fs.write(watchDir / part.file) { writeUtf8("audio") }
        watch.core.recordings.addPart(
            draft.recordingId,
            part.copy(bytes = 5, sha256 = PartHasher.sha256(watch.fs, watchDir / part.file)),
        )
        watch.core.recordings.finalize(draft.recordingId, START, 900.0)
        val sentMeta = watch.fs.read(watchDir / MetaWriter.metaFileName(MetaWriter.baseName(draft))) { readUtf8() }

        val phone = CoreFixture(platform = Platform.ANDROID)
        val incoming = "/incoming/${part.file}".toPath()
        phone.fs.createDirectories(incoming.parent!!)
        phone.fs.write(incoming) { writeUtf8("audio") }
        phone.core.transfer.acceptPart(draft.recordingId, 1, Track.MONO, PartHasher.sha256(phone.fs, incoming), incoming)
        assertIs<AcceptMetaResult.Complete>(phone.core.transfer.acceptMeta(sentMeta))

        val received = phone.core.recordings.get(draft.recordingId)!!.meta
        assertEquals(RecordingStatus.FINALIZED, received.status)
        assertEquals(listOf(61.0), received.highlights.map { it.atSec })
    }

    @Test
    fun `a placeholder another device is still uploading takes none`() = runBlocking {
        val id = "01J9PH0NE20000000000000000"
        f.drive.putFolder("20260826T010000Z_phone_01J9PH0N", "root", mapOf("recordingId" to id))
        f.core.pullRemoteRecordings(force = true)

        assertTrue(f.core.recordings.get(id)!!.remoteUploading)
        assertFalse(f.core.setHighlights(id, listOf(1.0)))
    }
}
