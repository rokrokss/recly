package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import recly.core.drive.FolderMarker
import recly.core.drive.string
import recly.core.job.JobService
import recly.core.job.JobStatus
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.processing.ProcessingPlan
import recly.core.processing.ProcessingSettingsRepository
import recly.core.recording.AudioParts
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository

/** What `ReclyCore.retranscribe` did (docs/10 "Re-transcription"). */
sealed interface RetranscribeResult {
    /** Queued as [jobId]; it shows in the job observation like any other, flagged `Job.retranscription`. */
    data class Started(val jobId: String) : RetranscribeResult

    /** A job of this recording has not settled yet — it may be writing the transcript right now. */
    data object Busy : RetranscribeResult

    /** The audio is neither on this device nor anywhere it can be fetched from. */
    data object NoAudio : RetranscribeResult

    /** Transcription is off in the processing settings: there is nothing to run. */
    data object NoTranscriptionConfigured : RetranscribeResult

    /**
     * Not a finished recording with a folder of its own — still recording, importing, arriving from the
     * watch, being uploaded by another device, never uploaded — or no recording at all.
     */
    data object Unsupported : RetranscribeResult
}

/**
 * docs/10 "Re-transcription": transcription and publication again for a finished recording, with the
 * processing settings as they are now. Nothing is uploaded again — the audio is already in the
 * recording's folder, so the plan publishes into that folder ([ProcessingPlan.retranscription]) — and
 * a part the retention sweep took, or one that was never on this device, is fetched back first the way
 * playback fetches it. The transcript already there stays readable until the new one replaces it.
 */
internal class Retranscription(
    private val deps: CoreDeps,
    private val recordings: RecordingRepository,
    private val jobs: JobService,
    private val settings: ProcessingSettingsRepository,
    private val audio: AudioParts,
    private val marker: FolderMarker,
    private val outputs: suspend (String) -> List<JsonObject>,
) {
    suspend fun start(recordingId: String): RetranscribeResult {
        val record = recordings.get(recordingId) ?: return RetranscribeResult.Unsupported
        if (record.meta.status != RecordingStatus.FINALIZED) return RetranscribeResult.Unsupported
        val folderId = folderOf(record) ?: return RetranscribeResult.Unsupported
        val plan = plan(recordingId) ?: return RetranscribeResult.NoTranscriptionConfigured
        // Asked again, atomically, by the enqueue; this one only spares a download that would be refused.
        if (jobs.list().any { it.recordingId == recordingId && it.status !in SETTLED }) return RetranscribeResult.Busy
        if (!audioHere(record)) return RetranscribeResult.NoAudio
        val job = jobs.enqueueRetranscription(recordingId, plan) ?: return RetranscribeResult.Busy
        // From the start, not after the first step: the transcription is the long part, and the other
        // devices' lists should say so while it runs (docs/03 "Recordings from other devices").
        marker.mark(folderId, listOf(TranscribeRunner.TYPE))
        deps.logger.log(Logger.Level.INFO, "rec.retranscribe", mapOf("recordingId" to recordingId, "jobId" to job.id))
        return RetranscribeResult.Started(job.id)
    }

    /** The plan a retry of a re-transcription runs (docs/10 "Retry"): the current settings again. */
    suspend fun plan(recordingId: String): recly.core.model.Workflow? {
        val record = recordings.get(recordingId) ?: return null
        val folderId = folderOf(record) ?: return null
        return ProcessingPlan.retranscription(settings.initialize().document, folderId)
    }

    /** Where the recording's files are: the row's folder, or what its upload step reported. */
    private suspend fun folderOf(record: RecordingRecord): String? =
        record.driveFolderId ?: outputs(record.id).mapNotNull { it.string("folderId") }.lastOrNull()

    /** The parts the transcription reads — `mono`, else `mix` (docs/08) — all on this device, fetched if they have to be. */
    private suspend fun audioHere(record: RecordingRecord): Boolean {
        try {
            audio.load(record, outputs(record.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(Logger.Level.WARN, "rec.retranscribe.audio", mapOf("recordingId" to record.id), e)
            return false
        }
        val track = if (Track.MONO in record.meta.tracks) Track.MONO else Track.MIX
        val parts = record.meta.parts.filter { it.track == track }
        return parts.isNotEmpty() && parts.all { deps.fileSystem.exists(record.dir / it.file) }
    }

    private companion object {
        val SETTLED = setOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.SKIPPED_SHORT)
    }
}
