@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import kotlin.time.ExperimentalTime
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepContext
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Step
import recly.core.model.Track
import recly.core.recording.RecordingRecord
import recly.core.testing.FakeClock
import recly.core.testing.FakeLogger
import recly.core.testing.FakeWebhook
import recly.core.testing.MapSecureStore
import recly.core.testing.START
import recly.core.testing.STEP_RUN_ID
import recly.core.testing.testDeps
import recly.core.testing.testMeta
import recly.core.testing.testWorkflow

/** A finalized single-track recording — enough for a payload that satisfies the docs/04 schema. */
internal fun finalizedMeta(): RecordingMeta = testMeta(
    title = "주간 회의",
    parts = listOf(
        Part(
            part = 1,
            track = Track.MONO,
            file = "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a",
            bytes = 3_601_234,
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a0a",
            startOffsetSec = 0.0,
            durationSec = 900.0,
        ),
    ),
).copy(
    endedAt = "2026-08-26T01:45:00.000Z",
    durationSec = 2700.0,
    status = RecordingStatus.FINALIZED,
)

/** A [WebhookRunner] wired to a [FakeWebhook] over a real [recly.core.drive.KtorTransport]. */
internal class WebhookHarness(
    val secrets: MapSecureStore = MapSecureStore(),
) {
    val webhook = FakeWebhook()
    val fs = FakeFileSystem()
    val clock = FakeClock()
    val logger = FakeLogger()

    private val meta = finalizedMeta()

    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        secureStore = secrets,
        transport = webhook.transport(fs),
    )

    val runner = WebhookRunner(deps)

    fun step(secretRef: String? = null): Step.Webhook =
        Step.Webhook(id = "notify", url = webhook.url, secretRef = secretRef)

    suspend fun run(step: Step.Webhook = step()): StepOutput {
        val workflow = testWorkflow(steps = listOf(step))
        return done(
            StepContext(
                job = Job(
                    id = "01J9JOB0000000000000000000",
                    recordingId = meta.recordingId,
                    workflowId = workflow.id,
                    workflow = workflow,
                    status = JobStatus.RUNNING,
                    createdAt = START,
                    updatedAt = START,
                    nextRunAt = null,
                ),
                workflow = workflow,
                stepRunId = STEP_RUN_ID,
                step = step,
                recording = RecordingRecord(meta.recordingId, meta, "/data/rec".toPath()),
                prior = emptyMap(),
                state = null,
                saveState = {},
                saveOutput = {},
                deps = deps,
            ),
        )
    }

    /** The webhook step is synchronous: it either succeeds or throws. */
    private suspend fun done(ctx: StepContext): StepOutput = (runner.run(ctx) as StepOutcome.Done).output
}
