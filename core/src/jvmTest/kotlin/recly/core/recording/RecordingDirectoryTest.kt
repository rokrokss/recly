package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.DriveApi
import recly.core.model.Platform
import recly.core.model.RecordingStatus
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta
import recly.core.transcribe.RecordingResults
import recly.core.transcribe.TranscriptAvailability

class RecordingDirectoryTest {
    private val oldRoot = "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/app.recly".toPath()
    private val newRoot = "/var/mobile/Containers/Data/Application/NEW/Library/Application Support/app.recly".toPath()

    @Test
    fun anUpgradeReadsTheExistingTranscriptAndAudioFromTheNewContainerOffline() = runBlocking {
        val fs = FakeFileSystem()
        val deps = testDeps(fileSystem = fs, platform = Platform.IOS, dataDir = newRoot)
        val db = inMemoryDatabase()
        val repository = RecordingRepository(db, deps)
        val meta = testMeta(status = RecordingStatus.FINALIZED)
        val base = MetaWriter.baseName(meta)
        val oldDir = oldRoot / "recordings" / base
        val newDir = newRoot / "recordings" / base
        // An absolute row and files copied to the new container reproduce a pre-fix upgrade.
        repository.create(meta, oldDir)
        fs.createDirectories(newDir)
        fs.write(newDir / "$base.meta.json") { write(fs.read(oldDir / "$base.meta.json") { readByteArray() }) }
        fs.write(newDir / "audio.m4a") { writeUtf8("existing audio") }
        fs.write(newDir / "$base.transcript.json") {
            writeUtf8("""{"schema":1,"recordingId":"${meta.recordingId}","track":"mono","language":"en","provider":{"name":"assemblyai"},"createdAt":"${meta.startedAt}","durationSec":1.0,"speakers":[{"id":"S1"}],"segments":[{"start":0.0,"end":1.0,"speaker":"S1","text":"Saved before updating"}]}""")
        }
        fs.deleteRecursively(oldRoot)

        val record = repository.get(meta.recordingId)!!
        assertEquals(newDir, record.dir)
        assertEquals(newDir, repository.list(10).single().dir)
        assertEquals(newDir, repository.observeAudio(meta.recordingId).first()!!.dir)
        assertEquals("existing audio", fs.read(record.dir / "audio.m4a") { readUtf8() })
        val result = RecordingResults(DriveApi(deps), deps).load(record, emptyList())
        assertEquals(TranscriptAvailability.READY, result.availability)
        assertEquals("Saved before updating", result.transcript!!.segments.single().text)

        assertTrue(repository.rename(meta.recordingId, "Renamed after updating"))
        assertFalse(fs.exists(oldRoot), "renaming must not recreate an obsolete container")
        assertTrue(fs.read(newDir / "$base.meta.json") { readUtf8() }.contains("Renamed after updating"))
        assertTrue(repository.delete(meta.recordingId, deleteDrive = false) is DeleteResult.Deleted)
        assertFalse(fs.exists(newDir), "deletion must target the current container too")
    }

    @Test
    fun relativeLocationsSurviveRepeatedRootChangesOnEveryPlatform() = runBlocking {
        for (platform in Platform.entries) {
            val fs = FakeFileSystem()
            val db = inMemoryDatabase()
            val meta = testMeta(status = RecordingStatus.FINALIZED)
            val base = MetaWriter.baseName(meta)
            val roots = (0..3).map { update ->
                if (platform == Platform.WINDOWS) {
                    "C:\\Users\\test\\AppData\\Local\\Recly-$update".toPath()
                } else {
                    "/updates/${platform.name}/container-$update/app.recly".toPath()
                }
            }
            var currentRoot = roots.first()
            val repository = RecordingRepository(db, testDeps(fileSystem = fs, platform = platform, dataDir = currentRoot))
            val dir = currentRoot / "recordings" / base
            repository.create(meta, dir)
            fs.write(dir / "audio.m4a") { writeUtf8("existing audio") }
            assertEquals("recordings/$base", db.recQueries.selectRecordingById(meta.recordingId).executeAsOne().dir)

            for (nextRoot in roots.drop(1)) {
                val previousDir = currentRoot / "recordings" / base
                val nextDir = nextRoot / "recordings" / base
                fs.createDirectories(nextDir)
                for (file in fs.list(previousDir)) fs.copy(file, nextDir / file.name)
                fs.deleteRecursively(currentRoot)

                val reopened = RecordingRepository(db, testDeps(fileSystem = fs, platform = platform, dataDir = nextRoot))
                val record = reopened.get(meta.recordingId)!!
                assertEquals(nextDir, record.dir, "$platform at $nextRoot")
                assertEquals(nextDir, reopened.list(10).single().dir)
                assertEquals("existing audio", fs.read(record.dir / "audio.m4a") { readUtf8() })
                assertTrue(reopened.rename(meta.recordingId, "Updated title"))
                assertFalse(fs.exists(currentRoot), "writing must not recreate an obsolete root")
                assertTrue(fs.read(nextDir / "$base.meta.json") { readUtf8() }.contains("Updated title"))
                assertEquals("recordings/$base", db.recQueries.selectRecordingById(meta.recordingId).executeAsOne().dir)
                currentRoot = nextRoot
            }
        }
    }

    @Test
    fun watchAndSimulatorContainersAreRebasedWithoutChangingTheRecordingFolderName() {
        val watchRoot = "/var/mobile/Containers/Data/Application/NEW/Library/Application Support/app.recly.watch".toPath()
        val watch = RecordingDirectory(watchRoot, Platform.WATCHOS)
        assertEquals(watchRoot / "recordings" / "watch-transfer-id", watch.resolve(
            "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/app.recly.watch/recordings/watch-transfer-id"))
        val phone = RecordingDirectory(newRoot, Platform.IOS)
        assertEquals(newRoot / "recordings" / "take", phone.resolve(
            "/Users/test/Library/Developer/CoreSimulator/Devices/DEVICE/data/Containers/Data/Application/OLD/Library/Application Support/app.recly/recordings/take"))
    }

    @Test
    fun externalPathsAndOtherAppsAreNeverRebased() {
        val phone = RecordingDirectory(newRoot, Platform.IOS)
        for (path in listOf(
            "/backups/recordings/take",
            "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/another.app/recordings/take",
            "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/app.recly/recordings/take/nested",
        )) assertEquals(path.toPath(), phone.resolve(path))
        val legacy = oldRoot / "recordings" / "take"
        for (platform in listOf(Platform.MACOS, Platform.ANDROID, Platform.WEAROS, Platform.WINDOWS)) {
            assertEquals(legacy, RecordingDirectory(newRoot, platform).resolve(legacy.toString()))
        }
    }
}
