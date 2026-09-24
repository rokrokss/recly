@file:OptIn(kotlin.time.ExperimentalTime::class)
package recly.core.processing

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.*
import recly.core.db.RecDatabase
import recly.core.job.*
import recly.core.job.Job
import recly.core.model.*
import recly.core.recording.*
import recly.core.testing.*
import recly.core.transcribe.*
import recly.core.message.CoreMessage

class FixedProcessingTest {
    @Test fun `updating preferences leaves already queued speaker choices intact`() = runBlocking<Unit> {
        val h = Harness(); h.capture()
        val workflow = Workflow(ProcessingPlan.ID, "Previous", START.isoUtc(), steps = listOf(
            Step.DriveUpload("upload"), Step.Transcribe("speech", provider = "assemblyai", secretRef = "key", diarize = false),
            Step.TranscriptPublish("publish")))
        val job = h.store.enqueue(h.id, workflow, START)!!
        val before = h.store.stepsOf(job.id)
        val current = assertIs<ProcessingSettingsState.Ready>(h.core.initializeProcessing()).document
        h.core.processingSettings.save(current.settings, current.revision)
        h.core.processingSettings.capture(h.id)
        assertEquals(job.id, h.enqueue().id)
        assertEquals(workflow, h.store.get(job.id)!!.workflow)
        assertEquals(before, h.store.stepsOf(job.id))
    }

    @Test fun `capable local engines automatically receive diarization and preserve speaker labels`() = runBlocking<Unit> {
        val engine = object : Engine() {
            override suspend fun status(language: String) = LocalEngineInfo(LocalEngineStatus.READY, "fake-local", "test-1", true)
            override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult {
                assertTrue(request.diarize)
                return LocalTranscriptionResult(listOf(SttSegment(0.0, 1.0, "A", "hello"), SttSegment(1.0, 2.0, "B", "there")))
            }
        }
        val h = Harness(engine); h.core.initializeProcessing(); h.capture(); val ctx = h.context(h.enqueue())
        h.core.localTranscription.run(ctx); h.core.localTranscription.awaitCurrent()
        val done = assertIs<StepOutcome.Done>(h.core.localTranscription.run(ctx))
        val transcript = recJson.decodeFromString<Transcript>(h.fs.read(h.dir / done.output.json.getValue("resultFile").jsonPrimitive.content) { readUtf8() })
        assertEquals("identified", transcript.speakerIdentification)
        assertEquals(2, transcript.speakers.size)
        assertEquals(2, transcript.segments.map { it.speaker }.distinct().size)
    }

    @Test fun `model preparation resumes only matching local failures and preserves completed work`() = runBlocking<Unit> {
        val engine = Engine(); val h = Harness(engine)
        h.core.initializeProcessing(); h.capture(); val matching = h.enqueue()
        val runs = h.store.stepsOf(matching.id)
        val upload = runs[0].copy(status = StepStatus.SUCCEEDED, attempts = 1,
            output = buildJsonObject { put("folderId", "kept") })
        h.store.updateStep(upload)
        h.store.updateStep(runs[1].copy(status = StepStatus.FAILED, attempts = 1,
            lastError = CoreMessage.LOCAL_MODEL_REQUIRED.code()))
        val checkpoint = buildJsonObject { put("input", "preserved") }
        h.store.saveStepState(runs[1].id, checkpoint)
        h.store.updateJob(matching.id, JobStatus.FAILED, null, START)
        suspend fun failed(id: String, step: Step, error: String): Job {
            val workflow = Workflow(id, "Untouched", START.isoUtc(), steps = listOf(step))
            val job = h.store.enqueue(h.id, workflow, START)!!
            h.store.updateStep(h.store.stepsOf(job.id).single().copy(status = StepStatus.FAILED, attempts = 3, lastError = error))
            h.store.updateJob(job.id, JobStatus.FAILED, null, START)
            return h.store.get(job.id)!!
        }
        val english = failed("english", Step.LocalTranscribe("speech", language = Language.EN), CoreMessage.LOCAL_MODEL_REQUIRED.code())
        val external = failed("external", Step.Transcribe("speech", provider = "assemblyai", secretRef = "key"), "AUTH_REJECTED")
        val unrelated = failed("unrelated", Step.LocalTranscribe("speech"), CoreMessage.LOCAL_DIARIZATION_UNAVAILABLE.code())
        val untouched = listOf(english, external, unrelated).associateWith { h.store.stepsOf(it.id) }

        assertEquals(LocalEngineStatus.READY, h.core.prepareLocalEngine("ko").status)
        val resumed = h.store.stepsOf(matching.id)
        assertEquals(JobStatus.PENDING, h.store.get(matching.id)!!.status)
        assertEquals(upload, resumed[0])
        assertEquals(StepStatus.PENDING, resumed[1].status)
        assertEquals(0, resumed[1].attempts); assertNull(resumed[1].lastError)
        assertEquals(checkpoint, resumed[1].state)
        assertEquals(runs[2], resumed[2])
        for ((job, steps) in untouched) {
            assertEquals(job, h.store.get(job.id)); assertEquals(steps, h.store.stepsOf(job.id))
        }
        assertTrue(engine.requests.isEmpty(), "preparation does not run inference or network publication")
    }

    @Test fun `unprepared models and disconnected recordings are not resumed`() = runBlocking<Unit> {
        for (status in listOf(LocalEngineStatus.MODEL_REQUIRED, LocalEngineStatus.UNSUPPORTED, LocalEngineStatus.READY)) {
            val h = Harness(object : Engine() {
                override suspend fun prepare(language: String) = LocalEngineInfo(status, "fake-local", "test-1")
            })
            h.core.initializeProcessing(); h.capture(); val job = h.enqueue()
            val runs = h.store.stepsOf(job.id)
            h.store.updateStep(runs[0].copy(status = StepStatus.SUCCEEDED))
            h.store.updateStep(runs[1].copy(status = StepStatus.FAILED, lastError = CoreMessage.LOCAL_MODEL_REQUIRED.code()))
            h.store.updateJob(job.id, JobStatus.FAILED, null, START)
            if (status == LocalEngineStatus.READY) h.store.disconnectDrive()
            val before = h.store.get(job.id)
            val steps = h.store.stepsOf(job.id)
            h.core.prepareLocalEngine("ko")
            assertEquals(before, h.store.get(job.id)); assertEquals(steps, h.store.stepsOf(job.id))
        }
    }

    @Test fun `a watch recording freezes the settings at receipt and its old pick is ignored`() = runBlocking<Unit> {
        val h = Harness()
        val current = h.core.initializeProcessing().document
        val external = current.settings.copy(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL,
            external = ExternalTranscription("assemblyai", "key")))
        h.core.processingSettings.save(external, current.revision)
        val oldPick = "01J9WF00000000000000000000"
        h.capture(h.meta.copy(source = Source.WATCH, workflowId = oldPick))
        assertNull(h.core.processingSettings.recordingSnapshot(h.id), "a watch recording freezes nothing at capture")

        val job = h.enqueue()
        assertEquals(ProcessingPlan.ID, job.workflowId)
        assertIs<Step.Transcribe>(job.workflow!!.steps[1])
        assertEquals(oldPick, h.core.recordings.get(h.id)!!.meta.workflowId, "the metadata is left as it arrived")
        assertEquals(job.id, h.enqueue().id, "a second receipt finds the same job")
        assertEquals(1, h.core.jobs.list().size)
    }

    @Test fun `a recording imported from Drive gets no plan and no local work`() = runBlocking<Unit> {
        val h = Harness(); h.core.initializeProcessing()
        h.core.recordings.adopt(h.meta.copy(status = RecordingStatus.FINALIZED), "remote-folder", emptyMap())
        assertIs<EnqueueResult.PartsPurged>(h.core.enqueue(h.id))
        assertTrue(h.core.jobs.list().isEmpty())
        assertNull(h.core.processingSettings.recordingSnapshot(h.id))
    }

    @Test fun `settings frozen by an older build that no longer read leave the recording unqueued`() = runBlocking<Unit> {
        val h = Harness(); h.capture()
        h.db.recQueries.syncSet("processing/recording/" + h.id, "pending")
        assertIs<EnqueueResult.NoWorkflow>(h.core.enqueue(h.id))
        assertTrue(h.core.jobs.list().isEmpty())
        assertEquals("pending", h.db.recQueries.syncGet("processing/recording/" + h.id).executeAsOneOrNull(), "never replaced")
        assertTrue(h.fs.exists(h.dir), "the recording itself is untouched")
    }

    @Test fun `local waiting is not provider polling or user retry`() = runBlocking<Unit> {
        val h = Harness(Engine()); h.core.initializeProcessing(); h.capture(); val job = h.enqueue()
        val runs = h.store.stepsOf(job.id)
        assertFalse(StepReport.localPending(job.workflow, runs))
        h.store.updateStep(runs[0].copy(status = StepStatus.SUCCEEDED))
        assertTrue(StepReport.localPending(job.workflow, h.store.stepsOf(job.id)))
        assertNull(StepReport.waitingMinutes(h.store.stepsOf(job.id), START))
        h.store.updateStep(runs[1].copy(status = StepStatus.FAILED))
        assertFalse(StepReport.localPending(job.workflow, h.store.stepsOf(job.id)))
    }

    @Test fun `capture freezes settings while future captures use the new revision`() = runBlocking<Unit> {
        val h = Harness(Engine())
        val first = assertIs<ProcessingSettingsState.Ready>(h.core.initializeProcessing()).document
        h.capture()
        val next = first.settings.copy(transcription = first.settings.transcription.copy(mode = TranscriptionMode.OFF))
        assertIs<ProcessingSaveResult.Saved>(h.core.processingSettings.save(next, first.revision))
        val job = h.enqueue()
        assertIs<Step.LocalTranscribe>(job.workflow!!.steps[1])
        assertTrue((job.workflow!!.steps[1] as Step.LocalTranscribe).diarize)
        assertEquals(first, h.core.processingSettings.recordingSnapshot(h.id))
        h.core.recordings.delete(h.id, false)
        assertNull(h.core.processingSettings.recordingSnapshot(h.id))
    }

    @Test fun `local engine receives one joined file and durable unknown-speaker result avoids reinference`() = runBlocking<Unit> {
        val engine = Engine(); val h = Harness(engine)
        h.core.initializeProcessing(); h.capture(); val job = h.enqueue()
        val ctx = h.context(job)
        assertIs<StepOutcome.Waiting>(h.core.localTranscription.run(ctx))
        h.core.localTranscription.awaitCurrent()
        val done = assertIs<StepOutcome.Done>(h.core.localTranscription.run(ctx))
        assertEquals(1, engine.requests.size)
        assertFalse(engine.requests.single().diarize, "unsupported speaker separation must not block transcription")
        assertTrue(engine.requests.single().path.endsWith(".m4a"))
        assertFalse(h.fs.exists(engine.requests.single().path.toPath()), "joined input is cleaned up")
        val resultFile = done.output.json.getValue("resultFile").jsonPrimitive.content
        val transcript = recJson.decodeFromString<Transcript>(h.fs.read(h.dir / resultFile) { readUtf8() })
        assertEquals(2, transcript.schema); assertEquals("unavailable", transcript.speakerIdentification)
        assertTrue(transcript.speakers.isEmpty()); assertEquals("", transcript.segments.single().speaker)
        assertEquals("hello", transcript.segments.single().text)
        assertFalse(TranscriptNormalizer.text(transcript).contains("S1"))
    }

    @Test fun `offline executor never starts upload or publishes a local result`() = runBlocking<Unit> {
        val h = Harness(Engine()); h.core.initializeProcessing(); h.capture(); val job = h.enqueue()
        assertTrue(h.core.runLocalJobs().jobIds.isEmpty(), "original upload must wait for network")
        val upload = h.store.stepsOf(job.id).first()
        h.store.updateStep(upload.copy(status = StepStatus.SUCCEEDED, output = buildJsonObject { put("folderId", "folder") }))
        h.core.runLocalJobs()
        val runs = h.store.stepsOf(job.id)
        assertEquals(StepStatus.SUCCEEDED, runs[1].status)
        assertEquals(StepStatus.PENDING, runs[2].status, "network publication remains untouched")
        assertEquals(JobStatus.PENDING, h.store.get(job.id)!!.status)
    }

    @Test fun `native failure cleans joined input and surfaces failure on next queue pass`() = runBlocking<Unit> {
        val engine = Engine(fail = true); val h = Harness(engine)
        h.core.initializeProcessing(); h.capture(); val ctx = h.context(h.enqueue())
        h.core.localTranscription.run(ctx); h.core.localTranscription.awaitCurrent()
        assertFalse(h.fs.exists(engine.requests.single().path.toPath()))
        assertFailsWith<IllegalStateException> { h.core.localTranscription.run(ctx) }
    }

    @Test fun `delete cancels native processing and refuses a late checkpoint`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { progress.checkpoint(SttSegment(0.0, 1.0, null, "late"), 1.0) }
                }
            }
        }
        val h = Harness(engine); h.core.initializeProcessing(); h.capture(); val ctx = h.context(h.enqueue())
        h.core.localTranscription.run(ctx); withTimeout(5000) { entered.await() }
        assertIs<DeleteResult.Deleted>(h.core.recordings.delete(h.id, false))
        assertTrue(engine.cancelled); assertFalse(h.fs.exists(h.dir)); assertNull(h.core.recordings.get(h.id))
    }

    private open class Engine(private val fail: Boolean = false) : LocalTranscriptionEngine {
        val requests = mutableListOf<LocalTranscriptionRequest>()
        var cancelled = false
        override fun cancel() { cancelled = true }
        override suspend fun status(language: String) = LocalEngineInfo(LocalEngineStatus.READY, "fake-local", "test-1")
        override suspend fun prepare(language: String) = status(language)
        override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult {
            requests += request
            if (fail) error("native failure")
            progress.checkpoint(SttSegment(0.0, 1.0, null, "hello"), 1.0)
            return LocalTranscriptionResult(emptyList())
        }
    }
    private class Harness(engine: LocalTranscriptionEngine = UnavailableLocalTranscriptionEngine()) {
        val fs = FakeFileSystem()
        val driver = inMemoryDriver()
        val db = RecDatabase(driver)
        val deps = testDeps(locale = "ko", fileSystem = fs, localTranscription = engine)
        val core = ReclyCore(deps, object : DriverFactory { override fun create() = driver })
        val store = JobStore(db, deps)
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1), testPart(testMeta(), 2)))
        val id = meta.recordingId
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        suspend fun capture(value: RecordingMeta = meta) { core.recordings.create(value, dir); seedFiles(fs, dir, value); core.recordings.finalize(id, START, 1800.0) }
        suspend fun enqueue(): Job { val result = assertIs<EnqueueResult.Enqueued>(core.enqueue(id)); return store.get(result.jobId)!! }
        suspend fun context(job: Job): StepContext {
            val step = job.workflow!!.steps[1]; val run = store.stepsOf(job.id)[1]
            return StepContext(job, job.workflow!!, run.id, step, core.recordings.get(id)!!, emptyMap(), null, {}, {}, deps)
        }
    }
}
