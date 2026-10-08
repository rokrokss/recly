@file:OptIn(ExperimentalTime::class)

package recly.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import recly.core.transcribe.TranscriptAvailability
import recly.core.transcribe.missingTranscriptAvailability
import recly.core.transcribe.LocalEngineStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.db.RecDatabase
import recly.core.drive.DriveFolderMarker
import recly.core.drive.DriveStore
import recly.core.job.EnqueueResult
import recly.core.job.Executor
import recly.core.job.JobService
import recly.core.job.JobStore
import recly.core.job.JobStatus
import recly.core.model.Step
import recly.core.model.wire
import recly.core.job.RunSummary
import recly.core.job.defaultRunners
import recly.core.model.Track
import recly.core.platform.CoreDeps
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTarget
import recly.core.privacy.TransferTargets
import recly.core.job.StepStatus
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
import recly.core.transcribe.RecordingResult
import recly.core.transcribe.RecordingResults
import recly.core.transfer.TransferReceiver
import recly.core.processing.ProcessingPlan
import recly.core.storage.CloudStorage
import recly.core.storage.StorageKind
import recly.core.model.Source
import recly.core.model.RecordingStatus
import recly.core.model.isoUtc
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * The shell opens the database: only it knows the file path and which SQLDelight driver its
 * platform ships (`NativeSqliteDriver` on Apple, `AndroidSqliteDriver` on Android). Bringing the
 * schema up to date is the factory's job too — creating it on a fresh file, migrating one an older
 * build left behind. The Android and native drivers do both once handed `RecDatabase.Schema`; the
 * JDBC one does neither, which is what `JvmRuntime` is for (docs/10 "Schema migrations").
 */
interface DriverFactory {
    fun create(): SqlDriver
}

/**
 * Everything docs/01 "What the core gives the shells" lists, assembled. A shell holds one of these for the
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

    /** Google Drive, and the app's iCloud folder where the shell has one (docs/03 "Storage location"). */
    private val storage: CloudStorage = CloudStorage.of(deps)

    val recordings: RecordingRepository = RecordingRepository(db, deps, storage)

    /** docs/05: this device's processing preferences, which every recording's plan is compiled from. */
    val processingSettings = recly.core.processing.ProcessingSettingsRepository(db, deps)
    @Throws(Throwable::class)
    suspend fun localEngineInfo(language: String): recly.core.transcribe.LocalEngineInfo = deps.localTranscription.status(language)

    @Throws(Throwable::class)
    suspend fun prepareLocalEngine(language: String): recly.core.transcribe.LocalEngineInfo {
        val info = deps.localTranscription.prepare(language)
        if (info.status == LocalEngineStatus.READY || info.status == LocalEngineStatus.WAITING) {
            // Quiescing keeps a pass that observed the missing model from parking after this scan.
            jobs.quiesced { resumeModelWaits(prepared = language) }
        }
        return info
    }

    /**
     * Releases the recordings waiting for a model that is here now: the one just [prepared], and any
     * other language whose model the engine reports ready — one app-managed model covers every
     * language, and Apple's per-locale assets may have arrived through another app.
     */
    private suspend fun resumeModelWaits(prepared: String? = null) {
        val ready = mutableMapOf<String, Boolean>()
        for (job in jobs.list().filter { it.status == JobStatus.NEEDS_MODEL }) {
            val blocked = jobs.steps(job.id).singleOrNull { it.status == StepStatus.NEEDS_MODEL } ?: continue
            val step = job.workflow?.steps?.find { it.id == blocked.stepId } as? Step.LocalTranscribe ?: continue
            val language = step.language.wire
            val here = language == prepared || ready.getOrPut(language) {
                deps.localTranscription.status(language).status == LocalEngineStatus.READY
            }
            if (here) jobStore.resumeModel(job.id, deps.clock.now())
        }
    }

    /**
     * docs/03 "Storage location": the user picked a local folder. The uploads and transcripts waiting for one
     * are let go now rather than at their next look, five minutes out; nothing else about them
     * changes. Returns how many jobs were let go — the shell runs the due jobs after it.
     */
    @Throws(Throwable::class)
    suspend fun resumeFolderWaits(): Int {
        val now = deps.clock.now()
        var resumed = 0
        for (job in jobs.list().filter { it.status == JobStatus.WAITING }) {
            val waiting = jobs.steps(job.id).any {
                it.status == StepStatus.PENDING &&
                    it.lastError?.let(CoreMessageRef::parse)?.message == CoreMessage.FOLDER_UNAVAILABLE
            }
            if (!waiting) continue
            jobStore.clearBackoff(job.id, now)
            resumed++
        }
        return resumed
    }

    @Throws(Throwable::class)
    suspend fun runLocalJobs(): RunSummary {
        try {
            resumeModelWaits()
            val first = jobs.runLocalJobs(deps.clock.now())
            if (first.alreadyRunning) return first
            localTranscription.awaitCurrent()
            val second = jobs.runLocalJobs(deps.clock.now())
            return RunSummary(jobIds = (first.jobIds + second.jobIds).distinct())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { localTranscription.cancelAll() }
            throw cancelled
        }
    }

    @Throws(Throwable::class)
    suspend fun localJobs(): List<recly.core.job.Job> = jobs.localJobs()

    /** Writes the defaults on first use; recording and [enqueue] do this themselves too. */
    @Throws(Throwable::class)
    suspend fun initializeProcessing(): recly.core.processing.ProcessingSettingsState.Ready = processingSettings.initialize()

    /**
     * docs/05 "Secrets": the device's secret values. **Every shell writes secrets through this**, not
     * through [SecureStore] directly.
     */
    val secrets: SecretsRepository = SecretsRepository(deps)

    val transferConsents: TransferConsents = TransferConsents(db, deps)

    val transfer: TransferReceiver = TransferReceiver(db, recordings, deps)

    private val jobStore: JobStore = JobStore(db, deps)

    val localTranscription = recly.core.transcribe.LocalTranscriptionService(db, deps)

    init {
        recordings.localTranscription = localTranscription
        recordings.beforeCapture = { meta ->
            if (meta.source != Source.WATCH) {
                processingSettings.capture(meta.recordingId)
                // An import is transcoded, not captured: it does not hold the on-device engine back.
                if (meta.status == RecordingStatus.RECORDING && meta.source != Source.IMPORT) {
                    localTranscription.captureStarted(meta.recordingId)
                }
            }
        }
        recordings.afterCapture = { localTranscription.captureEnded(it) }
    }

    private val driveJobAccess = recly.core.drive.DriveJobAccess(deps, jobStore)

    private val folderMarker = DriveFolderMarker(storage, deps)

    val jobs: JobService =
        JobService(
            deps,
            jobStore,
            recordings,
            Executor(
                deps,
                jobStore,
                recordings,
                defaultRunners(db, deps, localTranscription),
                marker = folderMarker,
                transferConsents = transferConsents,
                prepare = { driveJobAccess.prepare() },
                requireAccess = { driveJobAccess.requireAccess(it) },
            ).also { recordings.executor = it },
            planForRerun = { job ->
                if (job.retranscription) retranscription.plan(job.recordingId)
                else ProcessingPlan.compile(processingSettings.refreeze(job.recordingId))
            },
        )

    private val driveStore: DriveStore = DriveStore(db, deps)

    private val results: RecordingResults = RecordingResults(storage, deps)

    private val audio: AudioParts = AudioParts(storage, recordings, deps)

    private val exports = recly.core.recording.RecordingExport(
        deps, recordings, { results(it).transcript }, { audio(it, null) },
    )

    /**
     * docs/08 "Exports": one file of the recording for a share sheet — its transcript as text, Markdown,
     * SubRip or WebVTT, or its audio as one `.m4a` — written to a cache directory and named for people
     * (`2026-08-26 Weekly meeting.srt`). Null when there is nothing in that format: no transcript, or audio
     * that is neither here nor fetchable. The files are removed by a later export.
     */
    @Throws(Throwable::class)
    suspend fun exportFile(recordingId: String, format: recly.core.recording.ExportFormat): String? =
        exports.export(recordingId, format)

    private val retranscription = recly.core.transcribe.Retranscription(
        deps, recordings, jobs, processingSettings, audio, folderMarker, ::outputs,
    )

    private val remote: RemoteRecordings = RemoteRecordings(
        storage,
        recordings,
        deps,
        icloudChosen = { processingSettings.storage() == StorageKind.ICLOUD },
        transcriptsChanged = { resultChanges.value++ },
    )

    private val searchIndex = recly.core.recording.RecordingSearch(recordings, deps)

    /**
     * docs/10 "Search": the recordings whose title or transcript holds [query], newest first, at most
     * [limit] — case, Latin accents and full-width Latin ignored. Transcripts count once they are on
     * this device: this device's own, and another device's once opened or cached by a pull. Runs off
     * the caller's thread.
     */
    @Throws(Throwable::class)
    suspend fun search(query: String, limit: Int): List<recly.core.recording.SearchHit> = searchIndex.search(query, limit)

    /**
     * The first words of the transcripts held on this device, for the list rows on screen: by recording id,
     * at most 120 characters, cut at a word where possible and ended with `…`. A recording without a
     * transcript here is not in the map. Reads through [search]'s cache (docs/10 "Search"), off the caller's
     * thread; ask again when a recording's jobs or transcript change.
     */
    @Throws(Throwable::class)
    suspend fun previews(recordingIds: List<String>): Map<String, String> = searchIndex.previews(recordingIds)

    /**
     * docs/03 "Recordings from other devices": reads the recordings other devices uploaded into this device's list,
     * and drops the ones they have since deleted. Every job pass does this too ([runDueJobs]); a
     * ledger that has just come on screen calls it itself, with [force] to skip the pass throttle.
     * Never throws — a device without an account gets a summary that says so.
     */
    suspend fun pullRemoteRecordings(force: Boolean = false): PullSummary = remote.pull(force)

    /** A successful OAuth connection resumes only work belonging to this verified Drive owner. */
    @Throws(Throwable::class)
    suspend fun reconnectDrive(): Int = jobs.quiesced { driveJobAccess.reconnect() }

    /**
     * docs/03 "Titles": the detail screen's rename. Written locally at once — the list shows it on
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
     * docs/03 "Metadata": the highlight editor's save — the whole list, for this device's recordings and
     * another device's alike. Written locally at once (`recordings.observe()` shows it) and carried to
     * the folder's `meta.json` in the background right away when it can be, otherwise by the next job pass, the way
     * [rename] carries a title. While recording, marks go through `recordings.addHighlight` instead.
     * Returns false when there is nothing to write to. Never throws.
     */
    suspend fun setHighlights(recordingId: String, atSecs: List<Double>): Boolean {
        val written = try {
            recordings.setHighlights(recordingId, atSecs)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(Logger.Level.WARN, "rec.highlights.failed", mapOf("recordingId" to recordingId), e)
            false
        }
        if (written) pushes.launch { remote.pushMeta() }
        return written
    }

    /**
     * docs/08 "Result files": the transcript of one recording, for the detail screen — the local copy
     * the step left, or Drive's when this device did not run it.
     */
    @Throws(Throwable::class)
    suspend fun results(recordingId: String): RecordingResult = readResults(recordingId, repair = false)

    /** An explicit retry may replace an unreadable local transcript with a validated Drive copy. */
    @Throws(Throwable::class)
    suspend fun retryResults(recordingId: String): RecordingResult = readResults(recordingId, repair = true)

    private suspend fun readResults(recordingId: String, repair: Boolean): RecordingResult {
        val record = recordings.get(recordingId)
            ?: return RecordingResult(availability = TranscriptAvailability.UNAVAILABLE)
        val result = results.load(record, outputs(recordingId), repair)
        if (result.transcript != null || result.availability == TranscriptAvailability.UNAVAILABLE) return result
        val related = jobs.list().filter { it.recordingId == recordingId }
        return result.copy(availability = missingTranscriptAvailability(record, related, related.flatMap { jobs.steps(it.id) }))
    }

    /**
     * docs/08 "Editing": changes the transcript of one recording — its text, who says what, what the
     * speakers are called — and saves it here at once, then in the background in the recording's folder (Drive marks it
     * `edited`), now or with the next job pass. Works for other devices' recordings too. Refused while a
     * transcription of the recording is queued or running ([EditResult.Busy]): it would write over the
     * edit. [observeResults] emits the edited transcript.
     */
    @Throws(Throwable::class)
    suspend fun editTranscript(recordingId: String, edit: recly.core.transcribe.TranscriptEdit): recly.core.transcribe.EditResult =
        // One edit at a time, and none while a pull writes a transcript it read: each edit applies to the
        // newest transcript, and a pull never writes over one (docs/08 "Editing").
        remote.transcriptWrite { applyEdit(recordingId, edit) }

    private suspend fun applyEdit(recordingId: String, edit: recly.core.transcribe.TranscriptEdit): recly.core.transcribe.EditResult {
        val record = recordings.get(recordingId) ?: return recly.core.transcribe.EditResult.NoTranscript
        val unsettled = jobs.list().any {
            it.recordingId == recordingId && it.status !in setOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.SKIPPED_SHORT)
        }
        if (unsettled || localTranscription.isRunning(recordingId)) return recly.core.transcribe.EditResult.Busy
        val current = results(recordingId).transcript ?: return recly.core.transcribe.EditResult.NoTranscript
        val edited = try {
            recly.core.transcribe.TranscriptEdits.apply(current, edit, deps.clock.now().isoUtc())
        } catch (e: IllegalArgumentException) {
            return recly.core.transcribe.EditResult.Invalid(e.message ?: "invalid edit")
        }
        if (edited == current) return recly.core.transcribe.EditResult.Edited(current)
        transcriptWriter.writeLocal(record, edited, markdown = record.storage == StorageKind.FOLDER)
        recordings.transcriptPending(recordingId)
        resultChanges.value++
        deps.logger.log(Logger.Level.INFO, "rec.transcript.edited", mapOf("recordingId" to recordingId))
        // The edit is saved once it is on disk; storage gets it in the background (or by the next pass).
        pushes.launch { remote.pushTranscripts() }
        return recly.core.transcribe.EditResult.Edited(edited)
    }

    /** Where an edit's push to storage runs, so the screen that saved it does not wait for the network. */
    private val pushes = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Tests: wait for the background pushes [editTranscript] and [setHighlights] started. */
    internal suspend fun awaitPushes() {
        pushes.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }

    /** Bumped when a transcript changes on disk with no job or row to say so — an edit, a pull's refresh. */
    private val resultChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private val transcriptWriter = recly.core.transcribe.TranscriptWriter(deps)

    /** Only result data changes: shells keep their player and reading position while collecting. */
    fun observeResults(recordingId: String): Flow<RecordingResult> = combine(
        jobs.observe().map { all -> all.filter { it.recordingId == recordingId } }.distinctUntilChanged(),
        jobs.observeSteps(recordingId).distinctUntilChanged(),
        recordings.observe().map { recordings.get(recordingId) }.distinctUntilChanged(),
        localTranscription.changes,
        resultChanges,
    ) { _, _, _, _, _ -> Unit }.map {
        try {
            results(recordingId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            RecordingResult(availability = TranscriptAvailability.UNAVAILABLE)
        }
    }.distinctUntilChanged().catch {
        emit(RecordingResult(availability = TranscriptAvailability.UNAVAILABLE))
    }

    private val audioImport = recly.core.recording.AudioImport(deps, recordings) { enqueue(it) }

    /**
     * docs/03 "Naming rules": makes a recording of a file the user picked — audio or video — titled after
     * its name without the extension, started at [startedAt] (the file's own date, when the shell has
     * one) or now. While [importer] transcodes, the row is in the list ([recly.core.recording.RecordingRecord.importing],
     * `recordings.observe()`); once every part is there the recording is finalized and its fixed plan
     * queued, like any other. A failure leaves nothing behind and says why ([recly.core.recording.ImportResult.Failed]).
     */
    @Throws(Throwable::class)
    suspend fun importAudio(
        sourcePath: String,
        displayName: String,
        startedAt: Instant?,
        importer: recly.core.recording.AudioImporter,
    ): recly.core.recording.ImportResult = audioImport.import(sourcePath, displayName, startedAt, importer)

    /**
     * For a recorder's crash recovery, before it touches a row with `source: import` still `recording`:
     * true when it was left by a killed process and is now gone with its files; false while this process
     * is importing it — leave it alone. Recovery never finalizes an import (docs/03 "Naming rules").
     */
    @Throws(Throwable::class)
    suspend fun dropAbandonedImport(recordingId: String): Boolean = audioImport.dropAbandoned(recordingId)

    /**
     * docs/10 "Re-transcription": transcribes a finished recording again with the processing settings as they
     * are now — mode, provider, language, vocabulary — and publishes the result over the transcript in
     * the recording's folder and here. This device's own recordings and other devices' alike; the audio
     * is fetched back first when it is not here. Edits to the transcript are replaced: the shell asks
     * before calling. Progress is the job's, flagged [recly.core.job.Job.retranscription].
     */
    @Throws(Throwable::class)
    suspend fun retranscribe(recordingId: String): recly.core.transcribe.RetranscribeResult =
        retranscription.start(recordingId)

    /**
     * The audio of one recording, for the detail screen to play: the `mix` track if it has one and
     * `mono` otherwise, in part order. A part the retention sweep has already taken is fetched
     * back from Drive and kept ([recly.core.job.Retention]); one that never got there is named in
     * [RecordingAudio.missing] instead. [progress] hears how far that trip is, in bytes.
     *
     * `@Throws` for the same reason [runDueJobs] has it: a fetch needs the account, and what a
     * missing token or a refusing network says has to reach the shell rather than end the process.
     */
    @Throws(Throwable::class)
    suspend fun audio(recordingId: String, progress: recly.core.recording.AudioFetchProgress? = null): RecordingAudio {
        val record = recordings.get(recordingId)
            ?: return RecordingAudio(Track.MONO, emptyList(), emptyList())
        return audio.load(record, outputs(recordingId), progress)
    }

    /**
     * Whether Drive holds every part of this recording (docs/03 "Retention · deletion"): true once some job's
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

    /**
     * The fixed plan (docs/05), compiled from the settings the recording froze when it started. A
     * finished recording that froze none — a watch recording, at its verified receipt — freezes
     * the current ones here. [RecordingMeta.workflowId][recly.core.model.RecordingMeta.workflowId]
     * is not read.
     */
    @Throws(Throwable::class)
    suspend fun enqueue(recordingId: String): EnqueueResult {
        val record = recordings.get(recordingId) ?: return EnqueueResult.NoWorkflow
        if (record.meta.status == RecordingStatus.FINALIZED && !record.remote) processingSettings.capture(recordingId)
        val plan = processingSettings.recordingSnapshot(recordingId)?.let(ProcessingPlan::compile)
        return jobs.enqueue(recordingId, plan)
    }

    /**
     * What the platform scheduler calls. The pull rides on it because every shell already runs a
     * pass on a schedule and refreshes its ledger when one ends (docs/11 A5, docs/12 "Runner"): a
     * recording another device finished shows up here by the next pass.
     */
    @Throws(Throwable::class)
    suspend fun runDueJobs(now: Instant = deps.clock.now()): RunSummary {
        resumeConsentedJobs()
        try {
            val summary = jobs.runDueJobs(now)
            if (summary.alreadyRunning) return summary
            localTranscription.awaitCurrent()
            val next = jobs.runDueJobs(deps.clock.now())
            remote.pull()
            return RunSummary(jobIds = (summary.jobIds + next.jobIds).distinct())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            withContext(NonCancellable) { localTranscription.cancelAll() }
            throw e
        }
    }

    /** All unapproved destinations still ahead of a parked job, for one grouped consent screen. */
    @Throws(Throwable::class)
    suspend fun pendingTransferTargets(): List<TransferTarget> {
        val targets = mutableListOf<TransferTarget>()
        for (job in jobs.list().filter { it.status == JobStatus.NEEDS_CONSENT }) {
            val defined = job.workflow?.steps.orEmpty().associateBy { it.id }
            for (run in jobStore.stepsOf(job.id)) {
                if (run.status in setOf(StepStatus.PENDING, StepStatus.NEEDS_CONSENT)) {
                    defined[run.stepId]?.let(TransferTargets::forStep)?.let(targets::add)
                }
            }
        }
        return transferConsents.missing(targets)
    }

    /** Permission cannot reset successful steps, failed onError:continue steps, or retry budgets. */
    @Throws(Throwable::class)
    suspend fun resumeConsentedJobs() {
        for (job in jobs.list().filter { it.status == JobStatus.NEEDS_CONSENT }) {
            val defined = job.workflow?.steps.orEmpty().associateBy { it.id }
            val blocked = jobStore.stepsOf(job.id).filter { it.status == StepStatus.NEEDS_CONSENT }
            if (blocked.isEmpty()) continue
            val targets = blocked.mapNotNull { defined[it.stepId]?.let(TransferTargets::forStep) }
            if (transferConsents.missing(targets).isEmpty()) {
                jobStore.resumeConsent(job.id, deps.clock.now())
            }
        }
    }

    /**
     * "Disconnect" (docs/03 "Sign out vs Disconnect"), the local half of it: the `tokens` namespace of
     * [SecureStore], completed job records and the Drive folder cache. Unfinished jobs keep their steps and
     * resume state, parked until the same Drive owner is verified on reconnection. Nothing in Drive is
     * touched — those files are the user's own (docs/03), and this never calls `files.delete`.
     *
     * The `remote/ignored` suppression keys go too — they name folders of the account being disconnected — so
     * a re-connect shows what Drive has, the way a fresh device does.
     *
     * What it does **not** touch is this device's own configuration: the processing settings and
     * the `secrets` namespace both stay. Neither is derived from the account — they are per-device
     * and there is nothing to fetch them back from — so deleting them would be losing the user's
     * work over a decision about Drive access.
     *
     * The recordings and their `recording`/`part` rows stay unless [alsoDeleteRecordings]: an
     * original that has not been uploaded yet is not deleted by a decision about an account
     * (principle 3, "not deleted before the ack"). The dialog says how many are in that state and
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
        localTranscription.cancelAll()
        remote.disconnected {
            // The recordings first, and one at a time through the transactional [RecordingRepository
            // .delete]. Nothing of the queue is in flight in here, so a job left RUNNING goes with its
            // recording; one an on-device transcription would not let go of is Busy and stays.
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
            jobStore.disconnectDrive()
            driveJobAccess.clear()
            deps.tokenProvider.invalidate()
            deps.secureStore.clear(SecureStore.TOKENS)
            // Drive's copies only: a recording in the iCloud folder is not this account's to forget
            // (docs/03 "Storage location").
            recordings.synced().filterValues { StorageKind.ofId(it) == StorageKind.DRIVE }.keys
                .forEach { recordings.forgetDriveCopy(it) }
            driveStore.forgetAllFolders()
            // The "Delete local only" memory (docs/03 "Recordings from other devices") is about this account's folders, and
            // a device that starts over with an account starts over with its list.
            recordings.clearIgnored(StorageKind.DRIVE)
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
}

/**
 * What "Disconnect" (docs/03) managed. [busyRecordings] are the ones the deletion answered
 * [DeleteResult.Busy][recly.core.recording.DeleteResult.Busy] for: they and their queue rows are
 * still here, and the screen has to say so — disconnecting again takes them.
 */
data class DisconnectResult(
    val deletedRecordings: Int,
    val busyRecordings: List<String>,
)
