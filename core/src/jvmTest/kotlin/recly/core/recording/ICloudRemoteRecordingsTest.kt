@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.mockTransport
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.AuthRequiredException
import recly.core.platform.TokenProvider
import recly.core.storage.CloudStorage
import recly.core.storage.StorageKind
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.FakeUbiquityContainer
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta

/**
 * docs/03 "저장 위치": the shared list out of the app's iCloud folder. A recording another device
 * uploaded there becomes a row like a Drive one; because iCloud brings files in no order, a meta
 * whose parts are not all there yet is still an upload in flight; and a storage that was not listed
 * drops nothing of its own.
 */
class ICloudRemoteRecordingsTest {

    @Test
    fun `a recording another device put in the iCloud folder is adopted, its parts found by path`() = runBlocking<Unit> {
        val h = Harness()
        val up = h.uploaded(OTHER, title = "아이클라우드 회의")

        assertEquals(1, h.remote.pull(force = true).adopted)

        val row = assertNotNull(h.recordings.get(OTHER))
        assertTrue(row.remote)
        assertEquals(StorageKind.ICLOUD, row.storage)
        assertEquals(up.folder, row.icloudFolderPath)
        assertNull(row.driveFolderUrl, "an iCloud folder has no Drive link")
        assertEquals("아이클라우드 회의", row.meta.title)
        assertEquals(
            up.meta.parts.map { "icloud:${up.folder}/${it.file}" }.toSet(),
            h.recordings.driveFileIds(OTHER).values.toSet(),
        )
    }

    @Test
    fun `nothing is read from iCloud while it is not the chosen storage`() = runBlocking<Unit> {
        val h = Harness(chosen = false)
        h.uploaded(OTHER)

        assertEquals("auth", h.remote.pull(force = true).skipped)

        assertNull(h.recordings.get(OTHER))
        assertTrue(h.container.downloads.isEmpty())
    }

    @Test
    fun `a meta that came before its parts is an upload still going, completed when they land`() = runBlocking<Unit> {
        val h = Harness()
        val up = h.uploaded(OTHER, parts = 1)

        h.remote.pull(force = true)
        assertTrue(assertNotNull(h.recordings.get(OTHER)).remoteUploading)

        h.container.arrive("${up.folder}/${up.meta.parts[1].file}", SEEDED_AUDIO.encodeToByteArray())
        h.later()
        h.remote.pull(force = true)

        val row = assertNotNull(h.recordings.get(OTHER))
        assertFalse(row.remoteUploading)
        assertEquals(RecordingStatus.FINALIZED, row.meta.status)
    }

    @Test
    fun `a title renamed on another device comes from its folder file, and one renamed here goes to both files`() = runBlocking<Unit> {
        val h = Harness()
        val up = h.uploaded(OTHER, title = "처음")
        h.remote.pull(force = true)

        h.container.writeText(
            "${up.folder}/${up.base}.folder.json",
            h.properties(OTHER, "다른 기기에서 바꿈"),
        )
        h.later()
        h.remote.pull(force = true)
        assertEquals("다른 기기에서 바꿈", h.recordings.get(OTHER)!!.meta.title)

        assertTrue(h.recordings.rename(OTHER, "여기서 바꿈"))
        h.remote.pushTitles()

        assertTrue("여기서 바꿈" in h.container.text("${up.folder}/${up.base}.folder.json"))
        assertEquals("여기서 바꿈", recJson.decodeFromString<RecordingMeta>(h.container.text("${up.folder}/${up.base}.meta.json")).title)
        assertTrue(h.recordings.pendingTitles().isEmpty())
    }

    @Test
    fun `a folder deleted in iCloud takes its row, and a Drive row stays while Drive was not listed`() = runBlocking<Unit> {
        val h = Harness()
        val up = h.uploaded(OTHER)
        h.remote.pull(force = true)
        val driveMeta = h.finalized(DRIVE_ROW)
        assertTrue(h.recordings.adopt(driveMeta, "1DriveFolderId", emptyMap()))

        h.container.delete(up.folder)
        h.later()
        val summary = h.remote.pull(force = true)

        assertEquals(1, summary.dropped)
        assertNull(h.recordings.get(OTHER))
        assertNotNull(h.recordings.get(DRIVE_ROW), "Drive was signed out, so its rows are not judged")
    }

    @Test
    fun `an adopted part plays from iCloud, brought down and checked`() = runBlocking<Unit> {
        val h = Harness()
        val up = h.uploaded(OTHER)
        h.remote.pull(force = true)

        val audio = h.audio.load(h.recordings.get(OTHER)!!, emptyList())

        assertEquals(2, audio.paths.size)
        assertTrue(audio.missing.isEmpty())
        assertEquals(up.meta.parts.map { "${up.folder}/${it.file}" }, h.container.downloads.filter { it.endsWith(".m4a") })
    }

    @Test
    fun `iCloud out of reach with no Drive account is a pull that did not run`() = runBlocking<Unit> {
        val h = Harness()
        h.uploaded(OTHER)
        h.container.available = false

        assertEquals("auth", h.remote.pull(force = true).skipped)
        assertNull(h.recordings.get(OTHER))
    }

    private class Uploaded(val folder: String, val base: String, val meta: RecordingMeta)

    private class Harness(chosen: Boolean = true) {
        val clock = FakeClock()
        val fs = FakeFileSystem(clock)
        val container = FakeUbiquityContainer(fs, clock)
        val drive = FakeDrive()
        val deps = testDeps(
            clock = clock,
            fileSystem = fs,
            logger = FakeLogger(),
            tokenProvider = NoAccount,
            transport = mockTransport(drive, fs),
            ubiquity = container,
        )
        val db = inMemoryDatabase()
        val storage = CloudStorage.of(deps)
        val recordings = RecordingRepository(db, deps, storage)
        val remote = RemoteRecordings(storage, recordings, deps, icloudChosen = { chosen })
        val audio = AudioParts(storage, recordings, deps)

        /** Another pull, a minute on: what changed in the folder since the last one is listed. */
        fun later() = clock.advance(1.minutes)

        /** What another device's upload leaves in the iCloud folder: the folder file, [parts] of
         * the two parts, and the meta — in no particular order, as iCloud brings them. */
        fun uploaded(id: String, title: String? = null, parts: Int = 2): Uploaded {
            val meta = finalized(id, title)
            val base = MetaWriter.baseName(meta)
            val folder = "recly/memo/2026-08/$base"
            container.arrive("$folder/$base.folder.json", properties(id, title).encodeToByteArray())
            container.arrive("$folder/$base.meta.json", recJson.encodeToString(meta).encodeToByteArray())
            meta.parts.take(parts).forEach { container.arrive("$folder/${it.file}", SEEDED_AUDIO.encodeToByteArray()) }
            return Uploaded(folder, base, meta)
        }

        fun properties(id: String, title: String?): String = buildJsonObject {
            put("createdTime", clock.now().isoUtc())
            title?.let { put("description", it) }
            putJsonObject("appProperties") {
                put("recordingId", id)
                put("workflowId", "W")
            }
        }.toString()

        fun finalized(id: String, title: String? = null): RecordingMeta {
            val bare = testMeta(recordingId = id, source = Source.PHONE, title = title)
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

    private object NoAccount : TokenProvider {
        override suspend fun accessToken(): String = throw AuthRequiredException("no account")

        override suspend fun invalidate() = Unit
    }

    private companion object {
        val OTHER = "01J9CXDXX".padEnd(26, '0')
        val DRIVE_ROW = "01J9DRVXX".padEnd(26, '0')
    }
}
