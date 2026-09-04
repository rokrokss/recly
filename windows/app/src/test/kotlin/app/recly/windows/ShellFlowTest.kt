@file:OptIn(ExperimentalTime::class)

package app.recly.windows

import app.recly.windows.core.AppModule
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.helper.HelperClient
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.message
import app.recly.windows.jobs.Recents
import app.recly.windows.record.RecordingOutcome
import app.recly.windows.record.WindowsRecorder
import app.recly.windows.record.completeRecording
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.job.EnqueueResult
import recly.core.job.JobStatus
import recly.core.sync.WorkflowRepository

/**
 * The lane's own end-to-end path, with only the audio faked: record → finalize → enqueue → executor
 * → `NEEDS_AUTH`, and the line the tray reads off it. Nothing here is stubbed but the helper — the
 * core, the database, the executor and the token provider are the ones the app runs with, and it is
 * the *absence* of a sign-in (docs/06: no client id, no refresh token) that parks the job.
 *
 * The data directory is [DEV_FLOW] rather than a temp one on purpose: it is what the manual macOS
 * run copies into `~/Library/Application Support/app.recly.windows` to put a parked job in front of
 * the tray (`windows/app/README.md`).
 */
class ShellFlowTest {

    @Test
    fun `a recording with nobody signed in parks its job and the tray asks for a sign-in`() = runBlocking {
        val dir = File(DEV_FLOW).apply {
            deleteRecursively()
            mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val core = AppModule.build(dataDir = dir.absolutePath.toPath()).core
        // What `ShellModel.load` does on a PC that has never had a document: seed the docs/05
        // starter and point this device's default at 메모 (ADR-016).
        core.workflows.seed(WorkflowRepository.MEMO_ID)
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = WindowsRecorder(
            core = core,
            scope = scope,
            helper = {
                HelperClient(FakeHelperCommand.command("parts=1", "sec=2.0"), Dispatchers.IO, SilentLogger)
            },
            onFinalized = { finalized.complete(it) },
        )

        assertNotNull(recorder.start(workflowId = null))
        recorder.stop()
        val outcome = withTimeout(TIMEOUT_MS) { finalized.await() }

        // A skipped title: no name, and the job is queued all the same. The recording carries no
        // pick of its own, so ADR-016's second rule applies — this device's own default.
        assertIs<EnqueueResult.Enqueued>(completeRecording(core, outcome.recordingId, title = null))
        assertNull(assertNotNull(core.recordings.get(outcome.recordingId)).meta.title)

        core.runDueJobs()

        val job = core.jobs.list().single()
        // docs/06: `AuthRequiredException` parks the job instead of spending its retries.
        assertEquals(JobStatus.NEEDS_AUTH, job.status)
        assertEquals(outcome.recordingId, job.recordingId)
        assertEquals(Str.STATUS_SIGN_IN_NEEDED.message(), Recents.load(core).single().state)
        scope.cancel()
    }

    @Test
    fun `a title given after the stop is in the meta before the job exists`() = runBlocking {
        // docs/03: the name is asked for after the recording ends, and `updateTitle` refuses a
        // recording whose job has already read the meta — so the order is the whole rule.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val core = AppModule.build(
            dataDir = Files.createTempDirectory("recly-title").toString().toPath(),
        ).core
        core.workflows.seed(WorkflowRepository.MEMO_ID)
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = WindowsRecorder(
            core = core,
            scope = scope,
            helper = {
                HelperClient(FakeHelperCommand.command("parts=1", "sec=2.0"), Dispatchers.IO, SilentLogger)
            },
            onFinalized = { finalized.complete(it) },
        )

        assertNotNull(recorder.start(workflowId = null))
        recorder.stop()
        val outcome = withTimeout(TIMEOUT_MS) { finalized.await() }
        // The stop leaves no job behind: nothing is queued until the title dialog is answered.
        assertTrue(core.jobs.list().isEmpty())

        assertIs<EnqueueResult.Enqueued>(completeRecording(core, outcome.recordingId, "주간 회의"))

        assertEquals("주간 회의", assertNotNull(core.recordings.get(outcome.recordingId)).meta.title)
        assertEquals(1, core.jobs.list().size)
        scope.cancel()
    }

    private companion object {
        /** Relative to `windows/app`, which is where Gradle runs the tests from. */
        const val DEV_FLOW = "build/dev-flow"
        const val TIMEOUT_MS = 60_000L
    }
}
