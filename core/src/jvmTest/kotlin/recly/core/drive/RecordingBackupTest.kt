@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.recJson
import recly.core.platform.TokenProvider
import recly.core.recording.MetaWriter
import recly.core.storage.StorageKind
import recly.core.testing.CoreFixture
import recly.core.testing.FakeUbiquityContainer
import recly.core.testing.FakeDrive
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.seedFiles
import recly.core.testing.testMeta
import recly.core.testing.testPart

class RecordingBackupTest {
    @Test
    fun `backs up completed parts on schedule and reuses them at stop`() = runBlocking {
        val fixture = CoreFixture(engine = null)
        val meta = fixture.startRecording()
        val part = meta.parts.single()

        fixture.clock.advance(59.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        assertFalse(fixture.drive.files.values.any { it.name == part.file })

        fixture.clock.advance(1.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        val audio = fixture.drive.files.entries.single { it.value.name == part.file }
        val folderId = audio.value.parents.single()
        val base = MetaWriter.baseName(meta)
        val metaName = MetaWriter.metaFileName(base)
        val progressName = "$base.progress.json"
        val progress = fixture.drive.files.entries.single { it.value.name == progressName }
        assertEquals(folderId, progress.value.parents.single())
        assertEquals(listOf(part), progress.value.manifest().parts)
        assertEquals(RecordingStatus.RECORDING, progress.value.manifest().status)
        assertFalse(fixture.drive.files.values.any { it.name == metaName })
        assertFalse(fixture.core.uploaded(meta.recordingId))
        assertTrue(fixture.core.jobs.list().isEmpty())

        fixture.drive.requests.clear()
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        assertTrue(fixture.drive.requests.isEmpty())

        val nextPart = testPart(meta, 2).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)
        seedFiles(fixture.fs, fixture.dirOf(meta), meta.copy(parts = listOf(part, nextPart)))
        fixture.core.recordings.addPart(meta.recordingId, nextPart)
        fixture.clock.advance(60.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        val updated = fixture.drive.files.entries.single { it.value.name == progressName }
        assertEquals(progress.key, updated.key)
        assertEquals(listOf(part, nextPart), updated.value.manifest().parts)

        fixture.drive.files.getValue(folderId).name = "Renamed in Drive"
        fixture.core.recordings.finalize(meta.recordingId, fixture.clock.now(), durationSec = 7200.0)
        fixture.core.enqueue(meta.recordingId)
        fixture.drain()
        assertEquals(audio.key, fixture.drive.files.entries.single { it.value.name == part.file }.key)
        val final = fixture.drive.files.values.single { it.name == metaName }
        assertEquals(folderId, final.parents.single())
        assertEquals(RecordingStatus.FINALIZED, final.manifest().status)
        assertTrue(fixture.core.uploaded(meta.recordingId))
    }

    @Test
    fun `stages iCloud audio at the selected interval and leaves completion to the final upload`() = runBlocking {
        val fixture = CoreFixture(engine = null, ubiquity = ::FakeUbiquityContainer)
        fixture.core.initializeProcessing()
        fixture.core.processingSettings.setStorage(StorageKind.ICLOUD)
        val meta = fixture.startRecording()
        val part = meta.parts.single()
        val container = fixture.deps.ubiquity as FakeUbiquityContainer

        fixture.clock.advance(15.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 15)
        val partPath = container.entries.keys.single { it.endsWith(part.file) }
        val copy = container.entries.getValue(partPath)
        val progress = container.entries.keys.single { it.endsWith(".progress.json") }
        assertEquals(listOf(part), recJson.decodeFromString<RecordingMeta>(container.text(progress)).parts)
        assertFalse(container.entries.keys.any { it.endsWith(".meta.json") })
        assertFalse(fixture.core.uploaded(meta.recordingId))
        assertTrue(fixture.drive.requests.isEmpty())

        container.settle()
        fixture.clock.advance(1.minutes)
        fixture.core.recordings.finalize(meta.recordingId, fixture.clock.now(), durationSec = 900.0)
        fixture.core.enqueue(meta.recordingId)
        fixture.drain(1)
        container.settle()
        fixture.drain()
        assertEquals(copy.modifiedAt, container.entries.getValue(partPath).modifiedAt)
        assertTrue(container.entries.keys.any { it.endsWith(".meta.json") })
        assertTrue(fixture.core.uploaded(meta.recordingId))
    }

    @Test
    fun `a different Google account cannot continue the checkpoint or take its final upload`() = runBlocking {
        val fixture = CoreFixture(engine = null)
        val meta = fixture.startRecording()

        fixture.clock.advance(60.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        val part = meta.parts.single()
        assertTrue(fixture.drive.files.values.any { it.name == part.file })

        fixture.drive.accountId = "another-account"
        fixture.core.reconnectDrive()
        val nextPart = testPart(meta, 2).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)
        seedFiles(fixture.fs, fixture.dirOf(meta), meta.copy(parts = listOf(part, nextPart)))
        fixture.core.recordings.addPart(meta.recordingId, nextPart)
        fixture.drive.requests.clear()
        fixture.clock.advance(60.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)
        assertTrue(fixture.drive.requests.all { it.method == "GET" })
        assertFalse(fixture.drive.files.values.any { it.name == nextPart.file })

        fixture.core.recordings.finalize(meta.recordingId, fixture.clock.now(), durationSec = 7200.0)
        fixture.core.enqueue(meta.recordingId)
        fixture.drain()
        assertFalse(fixture.core.uploaded(meta.recordingId))
        assertFalse(fixture.drive.files.values.any { it.name.endsWith(".meta.json") })
    }

    @Test
    fun `an OAuth change during a checkpoint cannot replace its verified token`() = runBlocking {
        var token = "account-a"
        val tokens = object : TokenProvider {
            override suspend fun accessToken() = token
            override suspend fun invalidate() = Unit
        }
        val fixture = CoreFixture(engine = null, tokenProvider = tokens)
        val meta = fixture.startRecording()
        fixture.drive.hold += { request ->
            if (!request.path.endsWith("/about")) token = "account-b"
        }
        fixture.clock.advance(60.minutes)
        fixture.core.backUpRecording(meta.recordingId, intervalMinutes = 60)

        assertEquals("account-b", token)
        assertTrue(fixture.drive.files.values.any { it.name == meta.parts.single().file })
        assertTrue(fixture.drive.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer account-a" })
    }

    private fun FakeDrive.Entry.manifest(): RecordingMeta = recJson.decodeFromString(content.decodeToString())

    private suspend fun CoreFixture.startRecording(): RecordingMeta {
        val meta = testMeta(startedAt = clock.now().toString())
        val part = testPart(meta, 1).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)
        val dir = dirOf(meta)
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta.copy(parts = listOf(part)))
        core.recordings.addPart(meta.recordingId, part)
        return meta.copy(parts = listOf(part))
    }
}
