@file:OptIn(ExperimentalTime::class)

package recly.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import recly.core.db.RecDatabase
import recly.core.drive.DriveApi
import recly.core.drive.DriveStore
import recly.core.job.EnqueueResult
import recly.core.job.Executor
import recly.core.job.JobService
import recly.core.job.JobStore
import recly.core.job.RunSummary
import recly.core.job.defaultRunners
import recly.core.model.Track
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.platform.SecureStore
import recly.core.platform.clear
import recly.core.recording.AudioParts
import recly.core.recording.DeleteResult
import recly.core.recording.PullSummary
import recly.core.recording.RecordingAudio
import recly.core.recording.RecordingRepository
import recly.core.recording.RemoteRecordings
import recly.core.secrets.SecretsRepository
import recly.core.sync.DeviceDefaultStore
import recly.core.sync.WorkflowRepository
import recly.core.sync.WorkflowStore
import recly.core.transcribe.RecordingResult
import recly.core.transcribe.RecordingResults
import recly.core.transfer.TransferReceiver

/**
 * The shell opens the database: only it knows the file path and which SQLDelight driver its
 * platform ships (`NativeSqliteDriver` on Apple, `AndroidSqliteDriver` on Android). Bringing the
 * schema up to date is the factory's job too — creating it on a fresh file, migrating one an older
 * build left behind. The Android and native drivers do both once handed `RecDatabase.Schema`; the
 * JDBC one does neither, which is what `JvmRuntime` is for (docs/10 "스키마 마이그레이션").
 */
interface DriverFactory {
    fun create(): SqlDriver
}

/**
 * Everything docs/01 "코어가 셸에 주는 것" lists, assembled. A shell holds one of these for the
 * lifetime of the process and never builds the pieces itself.
 *
 * Deliberately concrete: this surface is what Swift sees, so no generics and no Kotlin-only types
 * leak through it.
 */
class ReclyCore(
    val deps: CoreDeps,
    driverFactory: DriverFactory,
) {
    private val db: RecDatabase = RecDatabase(driverFactory.create())

    val recordings: RecordingRepository = RecordingRepository(db, deps)

    private val workflowStore: WorkflowStore = WorkflowStore(db, deps)

    private val deviceDefaults: DeviceDefaultStore = DeviceDefaultStore(db, deps)

    val workflows: WorkflowRepository = WorkflowRepository(workflowStore, deviceDefaults, deps)

    /**
     * docs/05 "시크릿": the device's secret values. **Every shell writes secrets through this**, not
     * through [SecureStore] directly.
     */
    val secrets: SecretsRepository = SecretsRepository(deps)

    val transfer: TransferReceiver = TransferReceiver(db, recordings, deps)

    private val jobStore: JobStore = JobStore(db, deps)

    val jobs: JobService =
        JobService(
            deps,
            jobStore,
            recordings,
            Executor(deps, jobStore, recordings, defaultRunners(db, deps), live = { workflows.current() }),
        )

    private val driveStore: DriveStore = DriveStore(db, deps)

    private val results: RecordingResults = RecordingResults(DriveApi(deps), deps)

    private val audio: AudioParts = AudioParts(DriveApi(deps), recordings, deps)

    private val remote: RemoteRecordings = RemoteRecordings(DriveApi(deps), recordings, deps)

    /**
     * docs/03 "다른 기기의 녹음": reads the recordings other devices uploaded into this device's list,
     * and drops the ones they have since deleted. Every job pass does this too ([runDueJobs]); a
     * ledger that has just come on screen calls it itself, with [force] to skip the pass throttle.
     * Never throws — a device without an account gets a summary that says so.
     */
    suspend fun pullRemoteRecordings(force: Boolean = false): PullSummary = remote.pull(force)

    /**
     * docs/03 "제목": the detail screen's rename. Written locally at once — the list shows it on
     * `recordings.observe()` — and pushed to Drive (the folder's `description` and `meta.json`)
     * right away when the account and the network allow, otherwise by the next job pass. Returns
     * false when there is nothing to rename. Never throws: a push that fails is a pending write,
     * not an error the screen has to show.
     */
    suspend fun rename(recordingId: String, title: String?): Boolean {
        if (!recordings.rename(recordingId, title)) return false
        remote.pushTitles()
        return true
    }

    /**
     * docs/08 "결과 파일": the transcript of one recording, for the detail screen — the local copy
     * the step left, or Drive's when this device did not run it.
     */
    suspend fun results(recordingId: String): RecordingResult {
        val record = recordings.get(recordingId) ?: return RecordingResult()
        return results.load(record, outputs(recordingId))
    }

    /**
     * The audio of one recording, for the detail screen to play: the `mix` track if it has one and
     * `mono` otherwise, in part order. A part the retention sweep has already taken is fetched
     * back from Drive and kept ([recly.core.job.Retention]); one that never got there is named in
     * [RecordingAudio.missing] instead.
     *
     * `@Throws` for the same reason [runDueJobs] has it: a fetch needs the account, and what a
     * missing token or a refusing network says has to reach the shell rather than end the process.
     */
    @Throws(Throwable::class)
    suspend fun audio(recordingId: String): RecordingAudio {
        val record = recordings.get(recordingId)
            ?: return RecordingAudio(Track.MONO, emptyList(), emptyList())
        return audio.load(record, outputs(recordingId))
    }

    /**
     * Whether Drive holds every part of this recording (docs/03 "보관 · 삭제"): true once some job's
     * `drive.upload` steps have all succeeded. What the delete dialog and the disconnect warning
     * lead with is the audio that exists only here, and since the local parts became a cache with a
     * window on it ([recly.core.job.Retention]) "the file is still on disk" no longer answers that
     * — this does. Whether those local files may *go* is a different question, and the sweep's own.
     *
     * `@Throws` for the same reason [runDueJobs] has it: on Kotlin/Native an undeclared exception
     * out of an exported suspend function ends the process rather than reaching the caller.
     */
    @Throws(Throwable::class)
    suspend fun uploaded(recordingId: String): Boolean = jobStore.uploaded(recordingId)

    /** The same question for the whole list, asked once (see [uploaded]). */
    @Throws(Throwable::class)
    suspend fun uploadedRecordings(): Set<String> = jobStore.uploadedRecordings()

    /** Every step output of every job of one recording, oldest job first. */
    private suspend fun outputs(recordingId: String): List<JsonObject> = jobs.list()
        .filter { it.recordingId == recordingId }
        .sortedBy { it.createdAt }
        .flatMap { jobs.steps(it.id) }
        .mapNotNull { it.output }

    /** The definition a job runs with is this device's own document, seeded on first use (docs/05). */
    suspend fun enqueue(recordingId: String, chosenWorkflowId: String? = null): EnqueueResult =
        jobs.enqueue(recordingId, workflows.current(), chosenWorkflowId, workflows.deviceDefault())

    /**
     * What the platform scheduler calls. The pull rides on it because every shell already runs a
     * pass on a schedule and refreshes its ledger when one ends (docs/11 A5, docs/12 "실행기"): a
     * recording another device finished shows up here by the next pass.
     */
    @Throws(Throwable::class)
    suspend fun runDueJobs(now: Instant = deps.clock.now()): RunSummary {
        val summary = jobs.runDueJobs(now)
        remote.pull()
        return summary
    }

    /**
     * "연결 해제" (docs/03 "로그아웃 vs 연결 해제"), the local half of it: the `tokens` namespace of
     * [SecureStore], the queue (`job`, `step_run`) and the Drive folder cache. Nothing in Drive is
     * touched — those files are the user's own (docs/03), and this never calls `files.delete`.
     *
     * The `remote/ignored` rows go too — they name folders of the account being disconnected — so
     * a re-connect shows what Drive has, the way a fresh device does.
     *
     * What it does **not** touch is this device's own configuration: the workflow document, the
     * device-default pointer and the `secrets` namespace all stay. None of them is derived from the
     * account any more — they are per-device and there is nothing to fetch them back from — so
     * deleting them would be losing the user's work over a decision about Drive access.
     *
     * The recordings and their `recording`/`part` rows stay unless [alsoDeleteRecordings]: an
     * original that has not been uploaded yet is not deleted by a decision about an account
     * (principle 3, "ack 전에 지우지 않는다"). The dialog says how many are in that state and
     * offers this flag as a separate answer.
     *
     * **Revoking the grant is the shell's job**, not this one: it is a platform SDK call
     * (GoogleSignIn `disconnect()`, Android's `AuthorizationClient`, the Windows revoke endpoint)
     * and the core has no way to make it. Call it alongside this, not instead of it.
     *
     * All of it runs behind [Executor.quiesced][recly.core.job.Executor.quiesced]: a run already in
     * flight stops after the step it is on, and nothing is cleared until it has returned, so this
     * cannot pull the queue rows or the tokens out from under a job that is still using them.
     */
    @Throws(Throwable::class)
    suspend fun disconnect(alsoDeleteRecordings: Boolean): DisconnectResult = jobs.quiesced {
        // The recordings first, and one at a time through the transactional [RecordingRepository
        // .delete]: a recording whose job is RUNNING refuses, and its queue rows have to survive
        // this so the run it is in the middle of still has something to write to.
        var deleted = 0
        val busy = mutableListOf<String>()
        if (alsoDeleteRecordings) {
            recordings.list(Int.MAX_VALUE).forEach {
                when (recordings.delete(it.id, deleteDrive = false)) {
                    is DeleteResult.Deleted -> deleted++
                    DeleteResult.Busy -> busy += it.id
                    DeleteResult.NotFound -> Unit
                }
            }
        }
        // Before the namespace, not after: the shell's provider holds the access token in memory
        // too, and emptying the store under it would leave that copy to be handed to the next run.
        deps.tokenProvider.invalidate()
        deps.secureStore.clear(SecureStore.TOKENS)
        jobStore.deleteAll(keepRecordings = busy)
        driveStore.forgetAllFolders()
        // The "로컬만 삭제" memory (docs/03 "다른 기기의 녹음") is about this account's folders, and
        // a device that starts over with an account starts over with its list.
        recordings.clearIgnored()
        deps.logger.log(
            Logger.Level.INFO,
            "auth.disconnect",
            mapOf(
                "alsoDeleteRecordings" to alsoDeleteRecordings,
                "deletedRecordings" to deleted,
                "busyRecordings" to busy.size,
            ),
        )
        DisconnectResult(deleted, busy)
    }
}

/**
 * What "연결 해제" (docs/03) managed. [busyRecordings] are the ones a `RUNNING` job would not let
 * go of: they and their queue rows are still here, and the screen has to say so — disconnecting
 * again once the job has finished takes them.
 */
data class DisconnectResult(
    val deletedRecordings: Int,
    val busyRecordings: List<String>,
)
