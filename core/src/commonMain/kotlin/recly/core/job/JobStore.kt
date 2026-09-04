@file:OptIn(ExperimentalTime::class)

package recly.core.job

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import recly.core.db.RecDatabase
import recly.core.drive.DriveUploadRunner
import recly.core.ids.Ulid
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.CoreDeps

/**
 * Durable job queue. Every public call runs on [CoreDeps.io] under one mutex: SQLDelight drivers
 * are not thread-safe on every platform, and the read-modify-write pairs below (enqueue, park,
 * recovery) must not interleave.
 */
class JobStore(
    private val db: RecDatabase,
    private val deps: CoreDeps,
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    /**
     * A job is unique per `(recordingId, workflowId)` (docs/01): running a workflow again on the
     * same recording resumes the existing job from its failed step instead of duplicating work.
     * A finished-with-failure job is re-armed only when the caller wants it [JobStatus.PENDING] —
     * re-enqueuing a `SKIPPED_SHORT` recording must not silently un-skip it.
     */
    suspend fun enqueue(
        recordingId: String,
        workflow: Workflow,
        now: Instant,
        status: JobStatus = JobStatus.PENDING,
    ): Job? = locked {
        db.transactionWithResult {
            val existing = queries.selectJobByRecordingAndWorkflow(recordingId, workflow.id).executeAsOneOrNull()
            if (existing != null) {
                val rerunnable = existing.status == JobStatus.FAILED.name ||
                    existing.status == JobStatus.SKIPPED_SHORT.name
                if (status == JobStatus.PENDING && rerunnable) resetRows(existing.id, now)
                return@transactionWithResult job(existing.id)!!
            }
            // The other half of the purge race: once the parts are claimed there is nothing left
            // for a new job to upload, so it is never created. See [claimPurge].
            val parts = queries.selectPartsByRecording(recordingId).executeAsList()
            if (parts.isNotEmpty() && parts.all { it.deleted == 1L }) {
                return@transactionWithResult null
            }
            val jobId = Ulid.generate(fixed(now))
            queries.insertJob(
                jobId,
                recordingId,
                workflow.id,
                recJson.encodeToString(workflow),
                status.name,
                now.isoUtc(),
                now.isoUtc(),
                null,
            )
            workflow.steps.forEachIndexed { index, step ->
                queries.insertStepRun(
                    Ulid.generate(fixed(now)),
                    jobId,
                    step.id,
                    index.toLong(),
                    StepStatus.PENDING.name,
                    0,
                    null,
                    null,
                    null,
                    null,
                )
            }
            job(jobId)!!
        }
    }

    suspend fun get(jobId: String): Job? = locked { job(jobId) }

    /** The same rows [observeJobs] emits, read once. */
    suspend fun list(): List<Job> = locked { queries.selectJobs().executeAsList().map { it.toJob() } }

    /** A job whose snapshot did not decode is never due: nothing here can run it (docs/10). */
    suspend fun selectDue(now: Instant): List<Job> = locked {
        queries.selectDueJobs(now.isoUtc()).executeAsList().map { it.toJob() }.filter { it.workflow != null }
    }

    /** The recordings [Retention] has anything to sweep: a part of theirs is still on disk. */
    suspend fun recordingsWithLiveParts(): List<String> = locked {
        queries.selectRecordingsWithLiveParts().executeAsList()
    }

    /**
     * Decides the purge rule and claims the parts in one transaction. Deciding from a snapshot and
     * deleting afterwards would let a job enqueued in between lose its audio: the claim has to be
     * the same atomic step as the decision, and [enqueue] refuses to create a job once it is made.
     *
     * Purge only when every job of the recording is DONE (a FAILED one keeps its parts for
     * `retry()`, docs/03), no job of the recording has a snapshot this build cannot read, and some
     * DONE job uploaded all of them.
     *
     * @param oldEnough the retention half of the rule ([Retention.WINDOW]), decided in here rather
     * than before the call for the same reason as the rest of it: a part fetched back from Drive
     * while this ran would otherwise be deleted by an answer that predates it. It is handed the
     * files of the parts still on disk and the newest `updated_at` of the recording's jobs.
     */
    suspend fun claimPurge(
        recordingId: String,
        oldEnough: (files: List<String>, latestUpdate: Instant) -> Boolean,
    ): PurgeClaim = locked {
        db.transactionWithResult {
            val jobs = queries.selectJobsByRecording(recordingId).executeAsList()
            val parts = queries.selectPartsByRecording(recordingId).executeAsList()
            // An adopted recording (docs/03 "다른 기기의 녹음") has no job and needs none: Drive held
            // every part before the row existed, and what is on disk is a fetched cache. Only the
            // file clock ages it.
            val adopted = queries.selectRecordingById(recordingId).executeAsOneOrNull()?.remote == 1L
            if (!(adopted && jobs.isEmpty())) retainReason(jobs, parts)?.let { return@transactionWithResult it }
            val live = parts.filter { it.deleted == 0L }
            val latestUpdate = jobs.maxOfOrNull { Instant.parse(it.updated_at) } ?: Instant.DISTANT_PAST
            if (!oldEnough(live.map { it.file_ }, latestUpdate)) {
                return@transactionWithResult PurgeClaim.WITHIN_WINDOW
            }
            queries.markPartsDeleted(recordingId)
            PurgeClaim.CLAIMED
        }
    }

    /**
     * "Does Drive hold every part of this recording?" (docs/03 "보관 · 삭제") — what the delete
     * dialog and the disconnect warning lead with, because audio that exists only here is the part
     * of a deletion nothing anywhere else can give back. Reading it off "is a part file still on
     * disk" was right until the parts became a cache with a window on it ([Retention]), and is not
     * any more.
     *
     * Deliberately **not** [claimPurge]'s rule, which answers a different question — "may the local
     * files go?" — and is stricter about it: a job that has not finished, or one whose snapshot
     * this build cannot read, may still need the parts here, but neither says anything about what
     * Drive already holds. All this asks is that some job [uploadedEveryPart], so an upload that
     * landed and a webhook that failed afterwards still counts.
     */
    suspend fun uploaded(recordingId: String): Boolean = locked {
        db.transactionWithResult {
            if (queries.selectRecordingById(recordingId).executeAsOneOrNull()?.remote == 1L) {
                return@transactionWithResult true
            }
            val parts = queries.selectPartsByRecording(recordingId).executeAsList()
            queries.selectJobsByRecording(recordingId).executeAsList().any { uploadedEveryPart(it, parts) }
        }
    }

    /** The same answer for every recording at once: a list screen asks once, not once per row. An
     * adopted recording (docs/03 "다른 기기의 녹음") is in Drive by definition — that is where it came
     * from. */
    suspend fun uploadedRecordings(): Set<String> = locked {
        db.transactionWithResult {
            queries.selectJobs().executeAsList()
                .groupBy { it.recording_id }
                .filter { (recordingId, jobs) ->
                    val parts = queries.selectPartsByRecording(recordingId).executeAsList()
                    jobs.any { uploadedEveryPart(it, parts) }
                }
                .keys + queries.selectAdoptedRecordings().executeAsList().map { it.id }
        }
    }

    /**
     * Why a recording's parts are still needed here, or null when they are not — the retention
     * question, and the strict one: every job of it is DONE (a FAILED one keeps its parts for
     * `retry()`, docs/03), none has a snapshot this build cannot read, and some of them uploaded
     * all of them. A recording with no job at all has nothing that ever tried, so it is retained
     * too. [uploaded] is the other question and answers it on its own terms.
     */
    private fun retainReason(jobs: List<recly.core.db.Job>, parts: List<recly.core.db.Part>): PurgeClaim? = when {
        jobs.isEmpty() || jobs.any { it.status != JobStatus.DONE.name } -> PurgeClaim.OTHER_JOBS_PENDING
        // docs/10 "잡 스냅샷": a snapshot nothing here can decode says nothing about what its job
        // still has to do — an updated build reads the same row and runs it, and `DONE` in that
        // row is a claim this build cannot check. A sibling that did upload everything is no
        // evidence about *this* job, and the parts are the only copy of the audio.
        jobs.any { it.workflowOrNull() == null } -> PurgeClaim.SNAPSHOT_UNREADABLE
        jobs.none { uploadedEveryPart(it, parts) } -> PurgeClaim.UPLOAD_NOT_SUCCEEDED
        else -> null
    }

    suspend fun stepsOf(jobId: String): List<StepRun> = locked {
        queries.selectStepRunsByJob(jobId).executeAsList().map { it.toStepRun() }
    }

    suspend fun updateJob(jobId: String, status: JobStatus, nextRunAt: Instant?, now: Instant): Unit = locked {
        queries.updateJobStatus(status.name, nextRunAt?.isoUtc(), now.isoUtc(), jobId)
    }

    /**
     * The executor's claim on a job: it goes `RUNNING` only if the row is still there, and the
     * check and the write are one transaction. `RecordingRepository.delete` refuses a recording
     * with a `RUNNING` job inside a transaction of its own, and SQLite has a single writer — so
     * the two orders are the only two there are, and a recording can never be deleted out from
     * under a run. Returns false when the job went with its recording since `selectDue`.
     */
    suspend fun claimRunning(jobId: String, now: Instant): Boolean = locked {
        db.transactionWithResult {
            if (queries.selectJobById(jobId).executeAsOneOrNull() == null) {
                return@transactionWithResult false
            }
            queries.updateJobStatus(JobStatus.RUNNING.name, null, now.isoUtc(), jobId)
            true
        }
    }

    /** Writes everything the executor owns. `state_json` is deliberately untouched — only the step
     * itself writes that, through [saveStepState], and it must survive a failed attempt. */
    suspend fun updateStep(step: StepRun): Unit = locked { writeStep(step) }

    /**
     * Parking a job is two rows that must agree: a crash between them would leave a job that is
     * either run too early or never again. One transaction, so recovery never sees half of it.
     */
    suspend fun park(step: StepRun, status: JobStatus, nextRunAt: Instant?, now: Instant): Unit = locked {
        db.transaction {
            writeStep(step)
            queries.updateJobStatus(status.name, nextRunAt?.isoUtc(), now.isoUtc(), step.jobId)
        }
    }

    /**
     * The [park] of docs/10 "Drive 용량 초과", plus the one thing that park does not do: `state_json`
     * is dropped in the same transaction, so a job that comes back cannot resume a resumable
     * session that has since expired.
     */
    suspend fun parkNeedsSpace(step: StepRun, now: Instant): Unit = locked {
        db.transaction {
            queries.clearStepState(step.id)
            writeStep(step)
            queries.updateJobStatus(JobStatus.NEEDS_SPACE.name, null, now.isoUtc(), step.jobId)
        }
    }

    suspend fun saveStepState(stepRunId: String, state: JsonObject): Unit = locked {
        queries.updateStepState(state.toString(), stepRunId)
    }

    /**
     * Part of a step's answer, written while the step is still running. `output_json` is never
     * cleared by a later write (see `updateStepRun`), so what is put here survives the failure or
     * the park that follows — which is the whole point of writing it early.
     */
    suspend fun saveStepOutput(stepRunId: String, output: JsonObject): Unit = locked {
        queries.updateStepOutput(output.toString(), stepRunId)
    }

    /** Manual re-run: failed, parked and half-finished steps go back to `PENDING` with a fresh
     * retry budget, while `state_json` stays so a partial upload is not thrown away. */
    suspend fun resetForRerun(jobId: String, now: Instant): Unit = locked { resetRows(jobId, now) }

    /**
     * "Upload now" on a job that is only waiting out a backoff. Unlike [resetForRerun] nothing is
     * forgiven: the steps keep their `attempts` and their `last_error`, and only the clock is
     * dropped. The user asked for it to run *now*, not for another full retry budget — a job that
     * has spent its attempts must still be able to reach its `onError` (docs/10).
     */
    suspend fun clearBackoff(jobId: String, now: Instant): Unit = locked {
        db.transaction {
            queries.clearStepBackoff(jobId)
            queries.updateJobStatus(JobStatus.PENDING.name, null, now.isoUtc(), jobId)
        }
    }

    /**
     * Crash recovery: a process killed mid-step leaves rows in `RUNNING`, which no query would
     * pick up again. Attempts are left alone — the step never got to report a failure — and a job
     * whose steps are still waiting out a backoff comes back as `WAITING`, not `PENDING`, so the
     * recovery cannot cancel a backoff the paired write never got to record.
     */
    suspend fun recoverRunning(now: Instant): Unit = locked {
        db.transaction {
            val running = queries.selectRunningJobIds().executeAsList()
            queries.recoverRunningStepRuns()
            running.forEach { jobId ->
                val next = queries.latestPendingAttempt(jobId).executeAsOneOrNull()?.next_attempt_at
                val status = if (next == null) JobStatus.PENDING else JobStatus.WAITING
                queries.updateJobStatus(status.name, next, now.isoUtc(), jobId)
            }
        }
    }

    /**
     * "연결 해제" (docs/03): the whole queue goes, recordings and their parts do not. The jobs of
     * [keepRecordings] stay — those are the recordings a `RUNNING` job would not let go of, and
     * deleting the rows that run is written against would orphan it.
     */
    suspend fun deleteAll(keepRecordings: List<String> = emptyList()): Unit = locked {
        db.transaction {
            queries.deleteStepRunsExcept(keepRecordings)
            queries.deleteJobsExcept(keepRecordings)
        }
    }

    fun observeJobs(): Flow<List<Job>> =
        queries.selectJobs().asFlow().mapToList(deps.io).map { rows -> rows.map { it.toJob() } }

    /**
     * True when the job's `drive.upload` steps all succeeded **and** between them they sent every
     * part the recording has. A succeeded step is not the same claim: what was actually sent is
     * in the outputs (`files[] {part, track}`), and that is what is matched against the `part`
     * rows.
     */
    private fun uploadedEveryPart(job: recly.core.db.Job, parts: List<recly.core.db.Part>): Boolean {
        // An unreadable snapshot proves nothing about what was uploaded, and the parts are the only
        // copy of the audio: without proof they stay.
        val workflow = job.workflowOrNull() ?: return false
        val uploads = workflow.steps.filterIsInstance<Step.DriveUpload>().map { it.id }.toSet()
        if (uploads.isEmpty()) return false
        val runs = queries.selectStepRunsByJob(job.id).executeAsList().filter { it.step_id in uploads }
        if (runs.size != uploads.size || runs.any { it.status != StepStatus.SUCCEEDED.name }) return false
        val sent = runs.flatMap { sentParts(it.output_json) }.toSet()
        return parts.all { (it.part to it.track) in sent }
    }

    /**
     * The `{part, track}` pairs one succeeded `drive.upload` output names. `meta` is not one of
     * them: `includeMeta` reports `meta.json` as part 0 of a track no `part` row ever has.
     */
    private fun sentParts(outputJson: String?): List<Pair<Long, String>> {
        val output = outputJson?.let { recJson.parseToJsonElement(it) as? JsonObject } ?: return emptyList()
        return (output["files"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { file ->
                val part = (file["part"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val track = (file["track"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@mapNotNull null
                if (track == DriveUploadRunner.META_KEY) null else part to track
            }
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    private fun writeStep(step: StepRun) {
        queries.updateStepRun(
            step.status.name,
            step.attempts.toLong(),
            step.nextAttemptAt?.isoUtc(),
            step.lastError,
            step.output?.toString(),
            step.id,
        )
    }

    private fun resetRows(jobId: String, now: Instant) {
        db.transaction {
            queries.resetStepRuns(jobId)
            queries.updateJobStatus(JobStatus.PENDING.name, null, now.isoUtc(), jobId)
        }
    }

    private fun job(jobId: String): Job? = queries.selectJobById(jobId).executeAsOneOrNull()?.toJob()

    /**
     * Per-job isolation (docs/10 "잡 스냅샷"): a snapshot a newer app wrote can name a step type
     * this build's serializer has never heard of, and letting that throw would take `observeJobs`
     * — the whole list — down with it. So the one job reads as [JobStatus.FAILED] with a reason,
     * every other job loads normally, and `workflow_json` is left exactly as it is: the row still
     * says what the newer app queued, and an updated build decodes it and runs it.
     */
    private fun recly.core.db.Job.toJob(): Job {
        val decoded = workflowOrNull()
        return Job(
            id = id,
            recordingId = recording_id,
            workflowId = workflow_id,
            workflow = decoded,
            status = if (decoded == null) JobStatus.FAILED else JobStatus.valueOf(status),
            createdAt = Instant.parse(created_at),
            updatedAt = Instant.parse(updated_at),
            nextRunAt = next_run_at?.let(Instant::parse),
            snapshotError = if (decoded == null) snapshotError(workflow_json) else null,
        )
    }

    private fun recly.core.db.Job.workflowOrNull(): Workflow? = try {
        recJson.decodeFromString<Workflow>(workflow_json)
    } catch (_: SerializationException) {
        null
    }

    /**
     * Names the step the snapshot could not be read for, by decoding the steps one at a time: the
     * first one that fails is the one to report, and its `type` is what the person is told to
     * update the app for. Anything else — a snapshot that is not a workflow at all — is a parser
     * complaint the shell shows as it stands.
     */
    private fun snapshotError(json: String): String {
        val steps = try {
            (recJson.parseToJsonElement(json) as? JsonObject)?.get("steps") as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        steps.orEmpty().forEach { element ->
            val type = (element as? JsonObject)?.get("type")?.let { it as? JsonPrimitive }
                ?.takeIf { it.isString }?.content
                ?: return@forEach
            try {
                recJson.decodeFromJsonElement<Step>(element)
            } catch (_: SerializationException) {
                return CoreMessage.UNSUPPORTED_STEP.code(type)
            }
        }
        return CoreMessage.STEP_FAILED.code("the stored workflow snapshot did not decode")
    }

    private fun recly.core.db.Step_run.toStepRun(): StepRun = StepRun(
        id = id,
        jobId = job_id,
        stepId = step_id,
        ordinal = ordinal.toInt(),
        status = StepStatus.valueOf(status),
        attempts = attempts.toInt(),
        nextAttemptAt = next_attempt_at?.let(Instant::parse),
        lastError = last_error,
        state = state_json?.let { recJson.parseToJsonElement(it) as JsonObject },
        output = output_json?.let { recJson.parseToJsonElement(it) as JsonObject },
    )

    /** [Ulid] wants a ticking clock; enqueue already fixed the instant it is writing. */
    private fun fixed(now: Instant) = object : kotlin.time.Clock {
        override fun now(): Instant = now
    }
}

/** Outcome of [JobStore.claimPurge]; every retained case carries the `rec.retained` reason. */
enum class PurgeClaim { CLAIMED, OTHER_JOBS_PENDING, SNAPSHOT_UNREADABLE, UPLOAD_NOT_SUCCEEDED, WITHIN_WINDOW }
