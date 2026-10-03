@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.DriverFactory
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.job.JobStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.RecordingMeta
import recly.core.processing.ProcessingSaveResult
import recly.core.recording.MetaWriter
import recly.core.testing.FakeClock
import recly.core.testing.START
import recly.core.testing.inMemoryDriver
import recly.core.testing.seedFiles
import recly.core.testing.testDeps
import recly.core.testing.testMeta
import recly.core.testing.testPart
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalTranscriptionEngine
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.LocalTranscriptionResult
import recly.core.transcribe.SttSegment
import recly.core.transcribe.TranscribeRunner

/**
 * docs/03 "Storage location" — Local folder, end to end through the facade the shells use: a recording made
 * with the local folder chosen waits while none is picked, goes the moment one is, and ends up in it
 * with its transcript — as Markdown too — without a Google account or a network request.
 */
class FolderStorageTest {
    private val clock = FakeClock()
    private val fs = FakeFileSystem(clock)
    private var root: String? = null
    private val driver = inMemoryDriver()

    /** No transport: a request to anywhere would throw. */
    private val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        locale = "ko",
        localTranscription = Engine(),
        localFolder = PathFolder(fs) { root },
    )
    private val core = ReclyCore(deps, object : DriverFactory { override fun create() = driver })
    private val meta: RecordingMeta =
        testMeta(title = "주간 회의", parts = listOf(testPart(testMeta(), 1), testPart(testMeta(), 2)))
    private val base = MetaWriter.baseName(meta)
    private val dir = "/data/recordings/$base".toPath()

    @Test
    fun `a recording waits for a folder, goes once one is picked, and lands with its transcript`() = runBlocking<Unit> {
        core.initializeProcessing()
        assertIs<ProcessingSaveResult.Saved>(core.processingSettings.setStorage(StorageKind.FOLDER))
        val jobId = record()

        core.runDueJobs(clock.now())
        val waiting = core.jobs.list().single()
        assertEquals(JobStatus.WAITING, waiting.status)
        val upload = core.jobs.steps(jobId).first()
        assertEquals(CoreMessage.FOLDER_UNAVAILABLE, CoreMessageRef.parse(upload.lastError!!)!!.message)
        assertEquals(0, upload.attempts, "waiting for a folder spends nothing")

        fs.createDirectories(PICKED.toPath())
        root = PICKED
        assertEquals(1, core.resumeFolderWaits())
        core.runDueJobs(clock.now())
        core.runLocalJobs()
        core.runDueJobs(clock.now())

        assertEquals(JobStatus.DONE, core.jobs.list().single().status)
        val folder = folder()
        assertEquals(
            setOf(
                meta.parts[0].file, meta.parts[1].file, MetaWriter.metaFileName(base),
                TranscribeRunner.jsonFileName(base), TranscribeRunner.textFileName(base), TranscribeRunner.markdownFileName(base),
            ),
            fs.list(folder).map { it.name }.toSet(),
        )
        val markdown = fs.read(folder / TranscribeRunner.markdownFileName(base)) { readUtf8() }
        assertEquals(
            "---\ntitle: \"주간 회의\"\nrecordingId: ${meta.recordingId}\nstartedAt: ${meta.startedAt}\n---\n\n[00:00:00] hello\n",
            markdown,
        )
        assertEquals("recly", core.recordings.get(meta.recordingId)!!.localFolderPath!!.substringBefore('/'))
        assertEquals(null, core.recordings.get(meta.recordingId)!!.driveFolderUrl, "a local folder has no web page")
    }

    @Test
    fun `the offline pass copies into the folder and publishes there, with no network pass at all`() = runBlocking<Unit> {
        fs.createDirectories(PICKED.toPath())
        root = PICKED
        core.initializeProcessing()
        core.processingSettings.setStorage(StorageKind.FOLDER)
        record()

        core.runLocalJobs()

        assertEquals(JobStatus.DONE, core.jobs.list().single().status)
        assertTrue(fs.exists(folder() / TranscribeRunner.markdownFileName(base)))
    }

    @Test
    fun `a rename rewrites the meta and the Markdown title in the folder`() = runBlocking<Unit> {
        fs.createDirectories(PICKED.toPath())
        root = PICKED
        core.initializeProcessing()
        core.processingSettings.setStorage(StorageKind.FOLDER)
        record()
        core.runDueJobs(clock.now())
        core.runLocalJobs()
        core.runDueJobs(clock.now())

        assertTrue(core.rename(meta.recordingId, "회고"))

        val folder = folder()
        assertTrue("\"title\":\"회고\"" in fs.read(folder / MetaWriter.metaFileName(base)) { readUtf8() })
        val markdown = fs.read(folder / TranscribeRunner.markdownFileName(base)) { readUtf8() }
        assertTrue(markdown.startsWith("---\ntitle: \"회고\"\n"), markdown)
    }

    @Test
    fun `a device without a local folder cannot choose one`() = runBlocking<Unit> {
        val plain = ReclyCore(testDeps(clock = clock), object : DriverFactory { override fun create() = inMemoryDriver() })
        plain.initializeProcessing()

        assertIs<ProcessingSaveResult.Invalid>(plain.processingSettings.setStorage(StorageKind.FOLDER))
        assertEquals(StorageKind.DRIVE, plain.processingSettings.storage())
    }

    private suspend fun record(): String {
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, 1800.0)
        return assertIs<EnqueueResult.Enqueued>(core.enqueue(meta.recordingId)).jobId
    }

    /** The recording's folder, wherever the month template put it. */
    private fun folder(): Path = fs.listRecursively(PICKED.toPath()).single { it.name == base }

    private class Engine : LocalTranscriptionEngine {
        override fun cancel() = Unit
        override suspend fun status(language: String) = LocalEngineInfo(LocalEngineStatus.READY, "fake-local", "test-1")
        override suspend fun prepare(language: String) = status(language)
        override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult {
            progress.checkpoint(SttSegment(0.0, 1.0, null, "hello"), 1.0)
            return LocalTranscriptionResult(emptyList())
        }
    }

    private companion object {
        const val PICKED = "/Users/me/Notes"
    }
}
