@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.DriveApi
import recly.core.drive.ScriptedTokenProvider
import recly.core.drive.mockTransport
import recly.core.ids.Ulid
import recly.core.job.EnqueueResult
import recly.core.job.Executor
import recly.core.job.JobService
import recly.core.job.JobStore
import recly.core.job.Retention
import recly.core.job.defaultRunners
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.AuthRequiredException
import recly.core.platform.TokenProvider
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testDocument
import recly.core.testing.testMeta
import recly.core.testing.testWorkflow

/**
 * docs/03 "다른 기기의 녹음": Drive is the shared list. A folder another device uploaded — stamped
 * with its `recordingId`, `meta.json` last — becomes a row here with no job and no audio; one that
 * disappears from Drive takes its row with it; nothing this device made is touched.
 */
class RemoteRecordingsTest {

    @Test
    fun `a recording another device uploaded becomes a row with its parts purged and their file ids`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, source = Source.PHONE)

        val summary = h.remote.pull()

        assertEquals(PullSummary(adopted = 1, dropped = 0), summary)
        val record = assertNotNull(h.recordings.get(phone.recordingId))
        assertTrue(record.remote)
        assertEquals(phone.folderId, record.driveFolderId)
        assertEquals(phone.meta, record.meta, "the meta is the one the phone wrote")
        assertEquals("/data/recordings/${phone.recordingId}".toPath(), record.dir)
        assertTrue(h.fs.exists(record.dir / MetaWriter.metaFileName(MetaWriter.baseName(phone.meta))))
        assertEquals(listOf(1L, 1L), h.partRows(phone.recordingId).map { it.deleted }, "no audio is here")
        assertEquals(
            phone.meta.parts.map { phone.fileIds.getValue(it.file) },
            h.partRows(phone.recordingId).map { it.drive_file_id },
        )
        assertEquals(listOf("remote.adopt", "remote.pull"), h.logger.events)
    }

    @Test
    fun `the row sits in the list by its start time among this device's own`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1, startedAt = "2026-08-26T01:00:00.000Z")
        val earlier = h.uploaded(id = PHONE_1, startedAt = "2026-08-26T00:30:00.000Z")
        val later = h.uploaded(id = PHONE_2, startedAt = "2026-08-26T02:00:00.000Z")

        h.remote.pull()

        assertEquals(
            listOf(later.recordingId, mine.recordingId, earlier.recordingId),
            h.recordings.list(10).map { it.id },
        )
        assertFalse(h.recordings.get(mine.recordingId)!!.remote)
    }

    @Test
    fun `a second pull adopts nothing and a listing is all it costs`() = runBlocking {
        val h = Harness()
        h.uploaded(id = PHONE_1)
        h.remote.pull()
        val before = h.drive.requests.size

        val summary = h.remote.pull(force = true)

        assertEquals(PullSummary(0, 0), summary)
        assertEquals(1, h.drive.requests.size - before, "one files.list, no children, no downloads")
    }

    /**
     * `meta.json` goes up last (docs/03), so a folder without one is an upload still going — and
     * waiting for it would leave the user staring at nothing for the length of that upload. The row
     * appears at once, built out of the listing alone, and the meta landing replaces it.
     */
    @Test
    fun `a folder whose meta has not landed yet is a row that says another device is uploading`() = runBlocking {
        val h = Harness()
        val inFlight = h.uploaded(id = PHONE_1, title = "theirs", withMeta = false)

        assertEquals(PullSummary(adopted = 1, dropped = 0), h.remote.pull())

        val row = assertNotNull(h.recordings.get(PHONE_1))
        assertTrue(row.remote)
        assertTrue(row.remoteUploading)
        assertFalse(row.receiving, "nothing is coming from a watch")
        assertEquals(inFlight.folderId, row.driveFolderId)
        assertEquals(Source.PHONE, row.meta.source, "read off the folder's name")
        assertEquals("theirs", row.meta.title, "read off the folder's description")
        // The start time is the id's own: a ULID carries the millisecond it was made (docs/01).
        assertEquals(assertNotNull(Ulid.timestamp(PHONE_1)).isoUtc(), row.meta.startedAt)
        assertEquals(emptyList(), row.meta.parts)
        assertEquals(emptyList(), h.partRows(PHONE_1))
        assertTrue(h.fs.exists(row.dir / MetaWriter.metaFileName(MetaWriter.baseName(row.meta))))
        assertEquals(listOf("remote.adopt.provisional", "remote.pull"), h.logger.events)

        // The meta lands: the placeholder becomes the recording, parts and all.
        h.drive.put(inFlight.metaName, inFlight.folderId, recJson.encodeToString(inFlight.meta).encodeToByteArray())
        assertEquals(PullSummary(adopted = 1, dropped = 0), h.remote.pull(force = true))

        val completed = assertNotNull(h.recordings.get(PHONE_1))
        assertTrue(completed.remote)
        assertFalse(completed.remoteUploading)
        assertEquals(inFlight.meta, completed.meta)
        assertEquals(listOf(1L, 1L), h.partRows(PHONE_1).map { it.deleted })
        assertEquals(
            inFlight.meta.parts.map { inFlight.fileIds.getValue(it.file) },
            h.partRows(PHONE_1).map { it.drive_file_id },
        )
    }

    /** A device that gave up mid-upload leaves a folder that never fills. It is not "in flight". */
    @Test
    fun `a folder that has stood empty for a day is not adopted`() = runBlocking {
        val h = Harness()
        h.uploaded(id = PHONE_1, withMeta = false)
        h.clock.advance(RemoteRecordings.ABANDONED_AFTER + 1.minutes)

        assertEquals(PullSummary(0, 0), h.remote.pull())
        assertNull(h.recordings.get(PHONE_1))
    }

    @Test
    fun `a provisional row whose folder is still empty a day later is dropped`() = runBlocking {
        val h = Harness()
        val inFlight = h.uploaded(id = PHONE_1, withMeta = false)
        val kept = h.uploaded(id = PHONE_2)
        h.remote.pull()
        assertNotNull(h.recordings.get(PHONE_1))

        h.clock.advance(RemoteRecordings.ABANDONED_AFTER + 1.minutes)
        val summary = h.remote.pull(force = true)

        assertEquals(1, summary.dropped)
        assertNull(h.recordings.get(PHONE_1))
        assertFalse(h.fs.exists("/data/recordings/$PHONE_1".toPath()), "its directory went with it")
        assertNotNull(h.recordings.get(kept.recordingId))
        assertEquals(emptyList(), h.drive.deleted, "nothing on Drive was touched")
        assertTrue(inFlight.folderId in h.drive.files.keys)
    }

    /** The upload was cancelled, or the user deleted the half-written folder: same drop path. */
    @Test
    fun `a provisional row whose folder Drive no longer lists is dropped`() = runBlocking {
        val h = Harness()
        val inFlight = h.uploaded(id = PHONE_1, withMeta = false)
        h.remote.pull()
        h.drive.trashed += inFlight.folderId

        assertEquals(PullSummary(adopted = 0, dropped = 1), h.remote.pull(force = true))
        assertNull(h.recordings.get(PHONE_1))
    }

    /** The half-written folder of a recording this device already has is not a second recording. */
    @Test
    fun `a folder with no meta is never adopted over a row this device made`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.uploaded(id = mine.recordingId, withMeta = false)

        assertEquals(PullSummary(0, 0), h.remote.pull())

        val record = assertNotNull(h.recordings.get(mine.recordingId))
        assertFalse(record.remote)
        assertEquals(mine.meta.parts, record.meta.parts)
    }

    @Test
    fun `a recording this device made is never adopted over, whatever Drive says about it`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        // The same id under a folder, as if this device's own upload had finished elsewhere.
        h.uploaded(id = mine.recordingId, title = "not mine")

        assertEquals(PullSummary(0, 0, retitled = 1), h.remote.pull())

        val record = h.recordings.get(mine.recordingId)!!
        assertFalse(record.remote)
        assertEquals(mine.meta.parts, record.meta.parts)
        assertEquals("not mine", record.meta.title, "the folder's description is the title everywhere")
        assertEquals(listOf(0L, 0L), h.partRows(mine.recordingId).map { it.deleted }, "the audio here is still here")
    }

    @Test
    fun `an adopted recording that Drive no longer lists is dropped, and only that one`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        val kept = h.uploaded(id = PHONE_1)
        val gone = h.uploaded(id = PHONE_2)
        h.remote.pull()
        h.drive.trashed += gone.folderId

        val summary = h.remote.pull(force = true)

        assertEquals(PullSummary(adopted = 0, dropped = 1), summary)
        assertNull(h.recordings.get(gone.recordingId))
        assertFalse(h.fs.exists("/data/recordings/${gone.recordingId}".toPath()), "its directory went with it")
        assertNotNull(h.recordings.get(kept.recordingId))
        assertNotNull(h.recordings.get(mine.recordingId), "this device's own row is not Drive's to drop")
        assertEquals(emptyList(), h.drive.deleted, "nothing on Drive was touched")
    }

    /** A re-run into another path leaves two folders with one id: the newest complete one wins. */
    @Test
    fun `two folders with the same id resolve to the newest complete one`() = runBlocking {
        val h = Harness()
        val old = h.uploaded(id = PHONE_1, title = "first run")
        val rerun = h.uploaded(id = PHONE_1, title = "second run", withMeta = false)

        h.remote.pull()

        val record = h.recordings.get(old.recordingId)!!
        assertEquals(old.folderId, record.driveFolderId, "the newer folder is not complete yet")
        assertEquals("first run", record.meta.title)
        assertTrue(rerun.folderId != old.folderId)
    }

    @Test
    fun `a meta that names another recording is refused`() = runBlocking {
        val h = Harness()
        val lying = h.uploaded(id = PHONE_1, metaId = OTHER)

        assertEquals(PullSummary(0, 0), h.remote.pull())

        assertNull(h.recordings.get(lying.recordingId))
        assertEquals(listOf("remote.meta.mismatch", "remote.pull"), h.logger.events)
    }

    @Test
    fun `a device without an account pulls nothing and says so quietly`() = runBlocking {
        val h = Harness(signedIn = false)
        h.uploaded(id = PHONE_1)

        val summary = h.remote.pull()

        assertEquals("auth", summary.skipped)
        assertEquals(listOf("remote.pull.skipped"), h.logger.events)
        assertEquals(0, h.drive.requests.size)
    }

    @Test
    fun `a pull within the interval is skipped unless forced`() = runBlocking {
        val h = Harness()
        h.remote.pull()
        h.uploaded(id = PHONE_1)

        assertEquals("throttled", h.remote.pull().skipped)
        h.clock.advance(RemoteRecordings.MIN_INTERVAL - 1.minutes)
        assertEquals("throttled", h.remote.pull().skipped)
        assertEquals(PullSummary(1, 0), h.remote.pull(force = true))
    }

    @Test
    fun `a Drive that fails leaves the rows as they were and the failure in the summary`() = runBlocking {
        val h = Harness()
        val kept = h.uploaded(id = PHONE_1)
        h.remote.pull()
        h.drive.failNext(500) { it.method == "GET" && it.path == "/drive/v3/files" }

        val summary = h.remote.pull(force = true)

        assertNotNull(summary.skipped)
        assertEquals(0, summary.dropped)
        assertNotNull(h.recordings.get(kept.recordingId), "a listing that failed drops nothing")
        assertEquals("remote.pull.failed", h.logger.events.last())
    }

    // What the rest of the core makes of an adopted row.

    @Test
    fun `an adopted recording counts as uploaded and never gets a job`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.remote.pull()

        assertTrue(h.jobStore.uploaded(phone.recordingId))
        assertEquals(setOf(phone.recordingId), h.jobStore.uploadedRecordings())
        assertEquals(EnqueueResult.PartsPurged, h.jobs.enqueue(phone.recordingId, h.document, null, h.workflow.id))
        assertEquals(emptyList(), h.jobStore.list())
    }

    @Test
    fun `playing an adopted recording fetches its parts by their Drive ids`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.remote.pull()
        val record = h.recordings.get(phone.recordingId)!!

        val audio = h.audio.load(record, emptyList())

        assertEquals(emptyList(), audio.missing)
        assertEquals(phone.meta.parts.map { record.dir / it.file }, audio.paths)
        assertEquals(SEEDED_AUDIO, h.fs.read(audio.paths.first()) { readUtf8() })
        assertEquals(listOf(0L, 0L), h.partRows(phone.recordingId).map { it.deleted })
    }

    /** The fetched copy is a cache like any other (ADR-017): the sweep takes it back after a week. */
    @Test
    fun `the fetched parts of an adopted recording are swept after the window without a job`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.remote.pull()
        val record = h.recordings.get(phone.recordingId)!!
        h.audio.load(record, emptyList())

        h.retention.sweep(h.clock.now() + 1.days)
        assertEquals(listOf(0L, 0L), h.partRows(phone.recordingId).map { it.deleted }, "within the window")

        h.retention.sweep(h.clock.now() + Retention.WINDOW + 1.days)
        assertEquals(listOf(1L, 1L), h.partRows(phone.recordingId).map { it.deleted })
        assertTrue(phone.meta.parts.none { h.fs.exists(record.dir / it.file) })
    }

    @Test
    fun `deleting an adopted recording from Drive too deletes its folder`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.remote.pull()

        val result = h.recordings.delete(phone.recordingId, deleteDrive = true)

        assertEquals(DeleteResult.Deleted(driveDeleted = true), result)
        assertEquals(listOf(phone.folderId), h.drive.deleted)
        assertNull(h.recordings.get(phone.recordingId))
    }

    /**
     * "로컬만 삭제" keeps the Drive folder, and a folder that is listed is one a pull would adopt: the
     * recording the user just removed from this list would be straight back in it, as "another
     * device's". The kept folder is remembered instead, for as long as Drive lists it.
     */
    @Test
    fun `a recording deleted here while keeping its Drive folder does not come back`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        assertEquals(DeleteResult.Deleted(driveDeleted = false), h.recordings.delete(mine.recordingId, deleteDrive = false))
        val folderId = assertNotNull(h.drive.idOf(MetaWriter.baseName(mine.meta)))

        assertEquals(PullSummary(0, 0), h.remote.pull())
        assertNull(h.recordings.get(mine.recordingId))
        assertEquals(mapOf(mine.recordingId to folderId), h.recordings.ignored())

        // Once the folder is gone from Drive there is nothing left to keep out.
        h.drive.trashed += folderId
        h.remote.pull(force = true)
        assertEquals(emptyMap(), h.recordings.ignored())
    }

    /** "연결 해제" starts the device over; what Drive has is the list again. */
    @Test
    fun `forgetting the kept folders lets the next pull adopt them`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        h.recordings.delete(mine.recordingId, deleteDrive = false)

        h.recordings.clearIgnored()
        assertEquals(PullSummary(1, 0), h.remote.pull())

        assertTrue(h.recordings.get(mine.recordingId)!!.remote)
    }

    /** The row followed the older folder; when that one goes, the re-run's folder is what is left. */
    @Test
    fun `an adopted row whose folder was replaced by a re-run moves to the new folder`() = runBlocking {
        val h = Harness()
        val old = h.uploaded(id = PHONE_1, title = "first run")
        h.remote.pull()
        val rerun = h.uploaded(id = old.recordingId, title = "second run")
        h.drive.trashed += old.folderId

        val summary = h.remote.pull(force = true)

        assertEquals(PullSummary(adopted = 1, dropped = 1), summary)
        val record = h.recordings.get(old.recordingId)!!
        assertEquals(rerun.folderId, record.driveFolderId)
        assertEquals("second run", record.meta.title)
    }

    @Test
    fun `an adopted recording's title cannot be changed here`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()

        assertFalse(h.recordings.updateTitle(phone.recordingId, "mine"))
        assertEquals("theirs", h.recordings.get(phone.recordingId)!!.meta.title)
    }

    /** The meta names paths this device creates and deletes: one that is not this recording's, or
     * not a name the rules give, is refused before any of that. */
    @Test
    fun `a meta whose id or file names are not the schema's is refused`() = runBlocking {
        val h = Harness()
        val crafted = h.uploaded(id = "../../etc", metaId = "../../etc")
        val renamed = h.uploaded(id = PHONE_2, partFile = { "../../${'$'}it" })

        assertEquals(PullSummary(0, 0), h.remote.pull())

        assertNull(h.recordings.get(crafted.recordingId))
        assertNull(h.recordings.get(renamed.recordingId))
        assertEquals(listOf("remote.meta.mismatch", "remote.meta.mismatch", "remote.pull"), h.logger.events)
        assertFalse(h.fs.exists("/data/recordings/../../etc".toPath()))
    }

    /** The row followed the older folder because the newer one was still uploading. */
    @Test
    fun `an adopted row moves to a newer folder once that one completes`() = runBlocking {
        val h = Harness()
        val old = h.uploaded(id = PHONE_1, title = "first run")
        val rerun = h.uploaded(id = PHONE_1, title = "second run", withMeta = false)
        h.remote.pull()
        assertEquals(old.folderId, h.recordings.get(PHONE_1)!!.driveFolderId)

        h.drive.put(rerun.metaName, rerun.folderId, recJson.encodeToString(rerun.meta).encodeToByteArray())
        val summary = h.remote.pull(force = true)

        assertEquals(PullSummary(adopted = 1, dropped = 1), summary)
        val record = h.recordings.get(PHONE_1)!!
        assertEquals(rerun.folderId, record.driveFolderId)
        assertEquals("second run", record.meta.title)
    }

    /** A tombstone written while a pull is between its reads is still honoured by the adopt itself. */
    @Test
    fun `adopt refuses a recording the user deleted here while keeping its folder`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        h.recordings.delete(mine.recordingId, deleteDrive = false)

        assertFalse(h.recordings.adopt(mine.meta, "any-folder", emptyMap()))
        assertNull(h.recordings.get(mine.recordingId))
    }

    /** "연결 해제" clears the queue rows the folder id used to live in; the row remembers it now. */
    @Test
    fun `a recording deleted here after its queue rows were cleared still keeps its folder out`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        val folderId = assertNotNull(h.recordings.get(mine.recordingId)!!.driveFolderId, "the upload remembered the folder")
        h.jobStore.deleteAll()

        h.recordings.delete(mine.recordingId, deleteDrive = false)

        assertEquals(mapOf(mine.recordingId to folderId), h.recordings.ignored())
        assertEquals(PullSummary(0, 0), h.remote.pull())
        assertNull(h.recordings.get(mine.recordingId))
    }

    // The pending marker (docs/03 "다른 기기의 녹음"): what the device running the job says is left.

    @Test
    fun `the folder's marker says what the other device still has to do`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.mark(phone.folderId, "transcribe,webhook")

        h.remote.pull()

        assertEquals(setOf("transcribe", "webhook"), h.recordings.get(PHONE_1)!!.remotePending)

        // The device finished: the marker is emptied, and so is the row.
        h.mark(phone.folderId, "")
        h.remote.pull(force = true)
        assertEquals(emptySet(), h.recordings.get(PHONE_1)!!.remotePending)
    }

    /** Nothing has refreshed it for longer than any provider result can take (docs/08): the device
     * that wrote it is gone, and the row must not say "전사 중" forever. */
    @Test
    fun `a marker nobody has refreshed for eight hours is not read`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.mark(phone.folderId, "transcribe")
        h.remote.pull()
        assertEquals(setOf("transcribe"), h.recordings.get(PHONE_1)!!.remotePending)

        h.clock.advance(RemoteRecordings.MARKER_TTL + 1.minutes)
        h.remote.pull(force = true)

        assertEquals(emptySet(), h.recordings.get(PHONE_1)!!.remotePending)
    }

    /** A marker without a time on it says nothing about how old it is, so it is not believed. */
    @Test
    fun `a marker with no timestamp is not read`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.drive.files.getValue(phone.folderId).appProperties += mapOf("pending" to "transcribe")

        h.remote.pull()

        assertEquals(emptySet(), h.recordings.get(PHONE_1)!!.remotePending)
    }

    /** This device's own job rows are the truth about its own recordings; a marker is not. */
    @Test
    fun `a row this device made never takes a marker off Drive`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        h.mark(h.recordings.get(mine.recordingId)!!.driveFolderId!!, "transcribe")

        h.remote.pull(force = true)

        assertEquals(emptySet(), h.recordings.get(mine.recordingId)!!.remotePending)
    }

    // How often a pull runs (docs/03 "다른 기기의 녹음").

    @Test
    fun `a pull waits half a minute rather than two while another device is working`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1)
        h.remote.pull()
        h.clock.advance(RemoteRecordings.FAST_INTERVAL + 1.seconds)
        assertEquals("throttled", h.remote.pull().skipped, "nothing is in flight: the wait is two minutes")

        h.mark(phone.folderId, "transcribe")
        h.remote.pull(force = true)
        assertEquals(setOf("transcribe"), h.recordings.get(PHONE_1)!!.remotePending)

        h.clock.advance(RemoteRecordings.FAST_INTERVAL + 1.seconds)
        assertNull(h.remote.pull().skipped, "a row that says 전사 중 has to stop saying it soon")
    }

    @Test
    fun `a provisional row is enough to shorten the wait`() = runBlocking {
        val h = Harness()
        h.uploaded(id = PHONE_1, withMeta = false)
        h.remote.pull()

        h.clock.advance(RemoteRecordings.FAST_INTERVAL + 1.seconds)

        assertNull(h.remote.pull().skipped)
    }

    /**
     * The ledgers watch the job table for their own recordings and `recordings.observe()` for the
     * rows a pull writes — which is every row this lane is about, since none of them has a job here.
     */
    @Test
    fun `a provisional adoption, its completion and a marker change all reach the ledger`() = runBlocking {
        val h = Harness()
        val inFlight = h.uploaded(id = PHONE_1, withMeta = false)
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Unconfined) { h.recordings.observe().collect { emissions.send(Unit) } }
        assertNotNull(withTimeoutOrNull(TIMEOUT) { emissions.receive() }, "the list as it is now")

        h.remote.pull()
        assertNotNull(withTimeoutOrNull(TIMEOUT) { emissions.receive() }, "the provisional row")

        h.drive.put(inFlight.metaName, inFlight.folderId, recJson.encodeToString(inFlight.meta).encodeToByteArray())
        h.remote.pull(force = true)
        assertNotNull(withTimeoutOrNull(TIMEOUT) { emissions.receive() }, "the completion")

        while (emissions.tryReceive().isSuccess) Unit
        h.mark(inFlight.folderId, "transcribe")
        h.remote.pull(force = true)
        assertEquals(setOf("transcribe"), h.recordings.get(PHONE_1)!!.remotePending)
        assertNotNull(withTimeoutOrNull(TIMEOUT) { emissions.receive() }, "the marker")
        watcher.cancel()
    }

    // Titles (docs/03 "제목").

    @Test
    fun `a rename here reaches the folder's description and its meta on Drive`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        val folderId = h.recordings.get(mine.recordingId)!!.driveFolderId!!

        assertTrue(h.recordings.rename(mine.recordingId, " 주간 회의 "))
        h.remote.pushTitles()

        assertEquals("주간 회의", h.recordings.get(mine.recordingId)!!.meta.title)
        assertEquals("주간 회의", h.drive.files.getValue(folderId).description)
        val metaOnDrive = h.drive.byName(MetaWriter.metaFileName(MetaWriter.baseName(mine.meta)))!!
        assertEquals("주간 회의", recJson.decodeFromString<RecordingMeta>(metaOnDrive.content.decodeToString()).title)
        assertEquals(emptyMap(), h.recordings.pendingTitles())
    }

    @Test
    fun `a rename of an adopted recording is pushed the same way`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()

        assertTrue(h.recordings.rename(phone.recordingId, "ours"))
        h.remote.pushTitles()

        assertEquals("ours", h.drive.files.getValue(phone.folderId).description)
        assertEquals("ours", h.recordings.get(phone.recordingId)!!.meta.title)
    }

    @Test
    fun `a rename that cannot reach Drive stays pending and goes with the next pull`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()
        h.drive.failNext(500) { it.method == "PATCH" }

        h.recordings.rename(phone.recordingId, "ours")
        h.remote.pushTitles()
        assertEquals(mapOf(phone.recordingId to "ours"), h.recordings.pendingTitles())
        assertEquals("theirs", h.drive.files.getValue(phone.folderId).description)

        h.remote.pull(force = true)
        assertEquals(emptyMap(), h.recordings.pendingTitles())
        assertEquals("ours", h.drive.files.getValue(phone.folderId).description)
        assertEquals("ours", h.recordings.get(phone.recordingId)!!.meta.title, "the pull did not undo a pending rename")
    }

    @Test
    fun `a title renamed on another device is read back from the folder's description`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()
        h.drive.files.getValue(h.recordings.get(mine.recordingId)!!.driveFolderId!!).description = "renamed on the phone"
        h.drive.files.getValue(phone.folderId).description = "renamed on the Mac"

        val summary = h.remote.pull(force = true)

        assertEquals(2, summary.retitled)
        assertEquals("renamed on the phone", h.recordings.get(mine.recordingId)!!.meta.title)
        assertEquals("renamed on the Mac", h.recordings.get(phone.recordingId)!!.meta.title)
        assertEquals("renamed on the Mac", h.fs.read(h.recordings.get(phone.recordingId)!!.dir / phone.metaName) { readUtf8() }.let {
            recJson.decodeFromString<RecordingMeta>(it).title
        })
    }

    /** A folder without a description says nothing about the title; it does not clear one. */
    @Test
    fun `a folder with no description leaves the title alone`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()
        h.drive.files.getValue(phone.folderId).description = null

        assertEquals(0, h.remote.pull(force = true).retitled)
        assertEquals("theirs", h.recordings.get(phone.recordingId)!!.meta.title)
    }

    /** Drive answers with the fields asked for and nothing else: the title has to be one of them. */
    @Test
    fun `the folder listing asks Drive for the description`() = runBlocking {
        val h = Harness()

        h.remote.pull()

        val listing = h.drive.requests.single { it.method == "GET" && it.path == "/drive/v3/files" }
        assertTrue("description" in listing.query.getValue("fields"), listing.query.getValue("fields"))
        // `appProperties has { key=… }` without a value is a 400 on the real Drive (2026-09-04).
        assertFalse("has {" in listing.query.getValue("q"), listing.query.getValue("q"))
    }

    @Test
    fun `a meta whose start time cannot be parsed is refused without ending the pull`() = runBlocking {
        val h = Harness()
        val bad = h.uploaded(id = PHONE_1, withMeta = false)
        val crafted = recJson.encodeToString(bad.meta).replace(bad.meta.startedAt, "yesterday-ish")
        h.drive.put(bad.metaName, bad.folderId, crafted.encodeToByteArray())
        val good = h.uploaded(id = PHONE_2)

        val summary = h.remote.pull()

        assertEquals(PullSummary(adopted = 1, dropped = 0), summary)
        assertNotNull(h.recordings.get(good.recordingId))
        assertNull(h.recordings.get(PHONE_1))
    }

    /** A rename that never reached Drive must not be pushed onto a folder adopted back later. */
    @Test
    fun `deleting a recording drops the rename it had pending`() = runBlocking {
        val h = Harness()
        val phone = h.uploaded(id = PHONE_1, title = "theirs")
        h.remote.pull()
        h.drive.failNext(500) { it.method == "PATCH" }
        h.recordings.rename(phone.recordingId, "ours")
        h.remote.pushTitles()
        assertEquals(mapOf(phone.recordingId to "ours"), h.recordings.pendingTitles())

        h.recordings.delete(phone.recordingId, deleteDrive = false)

        assertEquals(emptyMap(), h.recordings.pendingTitles())
    }

    /** The folder exists but its meta is not up yet: the upload in flight may still write the old
     * title into it, so the rename stays pending until the meta is there to correct. */
    @Test
    fun `a rename stays pending until the folder has a meta to update`() = runBlocking {
        val h = Harness()
        val inFlight = h.uploaded(id = PHONE_1, title = "theirs", withMeta = false)
        // Adopted by hand as if from an earlier, complete listing; the folder then lost its meta.
        h.recordings.adopt(inFlight.meta, inFlight.folderId, emptyMap())

        h.recordings.rename(PHONE_1, "ours")
        h.remote.pushTitles()

        assertEquals("ours", h.drive.files.getValue(inFlight.folderId).description)
        assertEquals(mapOf(PHONE_1 to "ours"), h.recordings.pendingTitles())
    }

    /** A watch that resends a part after the phone uploaded must not make the phone forget the folder. */
    @Test
    fun `a watch resend keeps the folder the upload remembered`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        val folderId = h.recordings.get(mine.recordingId)!!.driveFolderId!!

        h.recordings.receive(mine.meta, h.recordings.get(mine.recordingId)!!.dir)

        val record = h.recordings.get(mine.recordingId)!!
        assertEquals(folderId, record.driveFolderId)
        assertFalse(record.remote)
    }

    /** A recording uploaded before the row kept its folder id: the listing tells the row, and the
     * rename that was waiting goes out with the same pull. */
    @Test
    fun `a recording uploaded before the folder column learns its folder and its rename goes out`() = runBlocking {
        val h = Harness()
        val mine = h.local(id = MINE_1)
        h.upload(mine)
        val folderId = h.recordings.get(mine.recordingId)!!.driveFolderId!!
        h.db.recQueries.updateRecordingFolder(null, mine.recordingId)
        assertNull(h.recordings.get(mine.recordingId)!!.driveFolderId)

        h.recordings.rename(mine.recordingId, "named later")
        h.remote.pushTitles()
        assertEquals(mapOf(mine.recordingId to "named later"), h.recordings.pendingTitles(), "nowhere to push yet")

        h.remote.pull(force = true)

        assertEquals(folderId, h.recordings.get(mine.recordingId)!!.driveFolderId)
        assertEquals("named later", h.drive.files.getValue(folderId).description)
        assertEquals(emptyMap(), h.recordings.pendingTitles())
    }

    /** A folder another device uploaded, as [FakeDrive] holds it. */
    class Uploaded(
        val recordingId: String,
        val folderId: String,
        val meta: RecordingMeta,
        val fileIds: Map<String, String>,
    ) {
        val metaName: String get() = MetaWriter.metaFileName(MetaWriter.baseName(meta))
    }

    class Local(val recordingId: String, val meta: RecordingMeta)

    private class Harness(signedIn: Boolean = true) {
        val drive = FakeDrive()
        val clock = FakeClock()
        val fs = FakeFileSystem(clock)
        val logger = FakeLogger()
        val deps = testDeps(
            clock = clock,
            fileSystem = fs,
            logger = logger,
            tokenProvider = if (signedIn) ScriptedTokenProvider() else NoAccount,
            transport = mockTransport(drive, fs),
        )
        val db = inMemoryDatabase()
        val recordings = RecordingRepository(db, deps)
        val jobStore = JobStore(db, deps)
        val jobs = JobService(deps, jobStore, recordings, Executor(deps, jobStore, recordings, defaultRunners(db, deps)))
        val retention = Retention(deps, jobStore, recordings)
        val audio = AudioParts(DriveApi(deps), recordings, deps)
        val remote = RemoteRecordings(DriveApi(deps), recordings, deps)
        val workflow = testWorkflow()
        val document = testDocument(workflow)

        /** The month folder every recording of the fake account shares (ADR-020). */
        private val month = drive.put("2026-08", "root", ByteArray(0), FakeDrive.FOLDER_MIME)

        /** What the device running the workflow leaves on the folder (docs/03 "다른 기기의 녹음"):
         * the types still to come, stamped with the moment it last said so. */
        fun mark(folderId: String, pending: String) {
            drive.files.getValue(folderId).appProperties +=
                mapOf("pending" to pending, "pendingAt" to clock.now().isoUtc())
        }

        /** What another device's `drive.upload` leaves: the `{base}/` folder stamped with the id,
         * the parts, and — unless [withMeta] is off — `meta.json` last. */
        fun uploaded(
            id: String,
            source: Source = Source.PHONE,
            startedAt: String = "2026-08-26T01:00:00.000Z",
            title: String? = null,
            withMeta: Boolean = true,
            metaId: String = id,
            /** What the meta calls each part file, given the name the rules would give it. */
            partFile: (String) -> String = { it },
        ): Uploaded {
            val meta = finalized(metaId, source, startedAt, title).let { m ->
                m.copy(parts = m.parts.map { it.copy(file = partFile(it.file)) })
            }
            val base = MetaWriter.baseName(meta)
            val folderId = drive.putFolder(
                name = base,
                parentId = month,
                appProperties = mapOf("recordingId" to id, "workflowId" to workflow.id),
                description = title,
            )
            val fileIds = meta.parts.associate { it.file to drive.put(it.file, folderId, SEEDED_AUDIO.encodeToByteArray()) }
            if (withMeta) {
                drive.put(MetaWriter.metaFileName(base), folderId, recJson.encodeToString(meta).encodeToByteArray())
            }
            return Uploaded(id, folderId, meta, fileIds)
        }

        /** A recording this device made itself, audio on disk and no job yet. */
        suspend fun local(id: String, startedAt: String = "2026-08-26T01:00:00.000Z"): Local {
            val meta = finalized(id, Source.DESKTOP, startedAt, null)
            val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
            fs.createDirectories(dir)
            meta.parts.forEach { fs.write(dir / it.file) { writeUtf8(SEEDED_AUDIO) } }
            recordings.create(meta, dir)
            return Local(id, meta)
        }

        fun partRows(recordingId: String) = db.recQueries.selectPartsByRecording(recordingId).executeAsList()

        /** This device's own upload of [local], through the real runner: the folder it leaves is
         * stamped like any other device's. */
        suspend fun upload(local: Local) {
            assertTrue(jobs.enqueue(local.recordingId, document, null, workflow.id) is EnqueueResult.Enqueued)
            jobs.runDueJobs(clock.now())
            assertEquals(setOf(local.recordingId), jobStore.uploadedRecordings())
        }

        private fun finalized(id: String, source: Source, startedAt: String, title: String?): RecordingMeta {
            val bare = testMeta(recordingId = id, source = source, startedAt = startedAt, title = title)
            val base = MetaWriter.baseName(bare)
            return bare.copy(
                parts = (1..2).map { number ->
                    Part(
                        part = number,
                        track = Track.MONO,
                        file = MetaWriter.partFileName(base, number, Track.MONO),
                        bytes = SEEDED_AUDIO.length.toLong(),
                        sha256 = SEEDED_AUDIO_SHA256,
                        startOffsetSec = (number - 1) * 900.0,
                        durationSec = 900.0,
                    )
                },
                endedAt = "2026-08-26T01:30:00.000Z",
                durationSec = 1800.0,
                status = RecordingStatus.FINALIZED,
            )
        }
    }

    private companion object {
        /** Ids the meta schema accepts: 26 of the ULID alphabet (no I, L, O, U). */
        fun ulid(tag: String): String = ("01J9$tag").padEnd(26, '0')

        /** Long enough that a slow machine does not fail a test about an emission arriving. */
        const val TIMEOUT: Long = 2_000

        val PHONE_1 = ulid("PH0NE1")
        val PHONE_2 = ulid("PH0NE2")
        val MINE_1 = ulid("M1NE1")
        val OTHER = ulid("0THER")
    }

    private object NoAccount : TokenProvider {
        override suspend fun accessToken(): String = throw AuthRequiredException("no account")

        override suspend fun invalidate() = Unit
    }
}
