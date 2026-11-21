@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.drive

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import recly.core.db.RecDatabase
import recly.core.job.StepOutcome
import recly.core.job.JobStore
import recly.core.message.CoreMessage
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.processing.ProcessingPlan
import recly.core.processing.ProcessingSettingsRepository
import recly.core.recording.RecordingRepository
import recly.core.storage.StorageKind

/** docs/03 "Storage location": upload the closed audio parts of a recording that is still running. */
internal class RecordingBackup(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val recordings: RecordingRepository,
    private val settings: ProcessingSettingsRepository,
    private val jobs: JobStore,
) {
    private val queries = db.recQueries
    private val upload = DriveUploadRunner.create(db, deps)

    suspend fun run(id: String, intervalMinutes: Int) = withContext(deps.io) {
        val interval = intervalMinutes.takeIf { it in intervals } ?: return@withContext
        val record = recordings.get(id) ?: return@withContext
        if (record.meta.status != RecordingStatus.RECORDING || record.remote || record.meta.parts.isEmpty()) return@withContext
        val startedAt = Instant.parse(record.meta.startedAt)
        val elapsedMinutes = (deps.clock.now() - startedAt).inWholeMinutes
        if (elapsedMinutes < interval) return@withContext
        val dueAt = startedAt + (elapsedMinutes / interval * interval).minutes
        val last = queries.kvGet(lastKey(id)).executeAsOneOrNull()?.let { recJson.decodeFromString<Checkpoint>(it) }
        if (last != null && dueAt <= Instant.parse(last.at)) return@withContext

        val document = settings.recordingSnapshot(id) ?: return@withContext
        val workflow = ProcessingPlan.compile(document)
        val step = workflow.steps.filterIsInstance<Step.DriveUpload>().first()
        if (step.store == StorageKind.FOLDER) return@withContext
        // A watch delivery can contain a staged part before its progress manifest arrives.
        val parts = if (record.meta.source == Source.WATCH) record.meta.parts.filter { it.durationSec > 0 }
        else record.meta.parts
        // Watch progress may arrive before the next audio file. Keep this interval open for that file.
        if (parts.size <= (last?.partCount ?: 0)) return@withContext
        if (parts.maxOf { it.startOffsetSec + it.durationSec } < workflow.minDurationSec) return@withContext
        val snapshot = record.copy(meta = record.meta.copy(parts = parts))

        try {
            val state = queries.kvGet(stateKey(id)).executeAsOneOrNull()?.let { recJson.parseToJsonElement(it) as JsonObject }
            val runner = if (step.store == StorageKind.DRIVE) {
                if (!jobs.driveConnected()) return@withContext
                val api = DriveApi.pinned(deps, deps.tokenProvider.accessToken())
                val account = api.accountId()
                currentCoroutineContext().ensureActive()
                val bound = queries.kvGet(driveAccountKey(id)).executeAsOneOrNull()
                if (bound != null && bound != account) return@withContext
                if (bound == null) queries.kvSet(driveAccountKey(id), account)
                DriveUploadRunner.create(db, deps, api)
            } else upload
            val outcome = runner.checkpoint(snapshot, workflow, step, state) { saved ->
                currentCoroutineContext().ensureActive()
                queries.kvSet(stateKey(id), saved.toString())
            }
            // Once the files are staged, iCloud continues independently of this process.
            if (outcome is StepOutcome.Done ||
                outcome is StepOutcome.Waiting && outcome.reason == CoreMessage.ICLOUD_UPLOADING.code()
            ) {
                currentCoroutineContext().ensureActive()
                queries.kvSet(lastKey(id), recJson.encodeToString(Checkpoint(dueAt.isoUtc(), parts.size)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            deps.logger.log(Logger.Level.WARN, "rec.backup.failed", mapOf("recordingId" to id), error)
        }
    }

    @Serializable
    private data class Checkpoint(val at: String, val partCount: Int)

    companion object {
        private val intervals = setOf(15, 30, 60, 120, 240, 480, 720, 1440)
        fun driveAccountKey(id: String) = "recording/backup/account/$id"
        fun stateKey(id: String) = "recording/backup/state/$id"
        private fun lastKey(id: String) = "recording/backup/last/$id"
        fun keys(id: String) = listOf(driveAccountKey(id), stateKey(id), lastKey(id))
    }
}
